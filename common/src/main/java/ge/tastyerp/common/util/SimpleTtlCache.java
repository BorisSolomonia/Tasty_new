package ge.tastyerp.common.util;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Minimal thread-safe TTL cache (BOR-75), hardened in BOR-81/BOR-90.
 *
 * <p>Deliberately dependency-free (no Caffeine/Redis): the fleet is small
 * single-instance services and the cached values are a handful of date-range
 * keyed lists, so a ConcurrentHashMap with expiry timestamps is sufficient and
 * auditable.</p>
 *
 * <p><b>Integrity contract:</b> use ONLY for data that is immutable-in-practice
 * within the TTL window (e.g. RS.ge waybill history). Never cache user-editable
 * state (category overrides, real-entity flags, payments) — those must always
 * be read fresh so edits take effect immediately.</p>
 *
 * <p><b>Two guarantees added after the BOR-81/90 audit:</b></p>
 * <ol>
 *   <li><b>Single-flight loading.</b> Concurrent misses on the same key share
 *       one loader call; late arrivals wait for the in-flight load instead of
 *       starting their own. Before this, three operators opening the audit page
 *       within the same second on a cold cache tripled a 147-second RS.ge
 *       fetch and raced to write the same Firestore documents (finding B-9).</li>
 *   <li><b>Expiry frees memory.</b> An expired entry is dropped the moment it is
 *       observed (on a read of that key, or on any miss via
 *       {@link #sweepExpired()}), instead of staying strongly referenced until
 *       {@code maxEntries} other keys arrived. Cached values here are multi-year
 *       document feeds, so an expired-but-pinned entry was tens of megabytes
 *       of dead heap per distinct date range (BOR-90 finding M-3).</li>
 * </ol>
 *
 * <p>A loader that throws caches nothing; the exception propagates to every
 * caller waiting on that load, and the next call retries.</p>
 */
public final class SimpleTtlCache<K, V> {

    private record Entry<V>(V value, long expiresAtMillis) {}

    /**
     * Every cache created through {@link #named} registers here (weakly) so
     * {@code MemoryDiagnostics} can log sizes without each service wiring
     * anything. Entries whose cache has been collected are pruned on read.
     */
    private static final List<WeakReference<SimpleTtlCache<?, ?>>> REGISTRY = new CopyOnWriteArrayList<>();

    private final Map<K, Entry<V>> map = new ConcurrentHashMap<>();
    private final Map<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final int maxEntries;
    private final LongSupplier clock;
    private final String name;

    /**
     * @param ttlMillis  how long an entry stays valid
     * @param maxEntries safety bound; when exceeded, expired entries are purged
     *                   and, if still over, the whole cache is cleared (simple
     *                   and safe for small caches — avoids LRU bookkeeping)
     */
    public SimpleTtlCache(long ttlMillis, int maxEntries) {
        this(ttlMillis, maxEntries, System::currentTimeMillis);
    }

    /** Test seam: inject the clock so expiry can be exercised without sleeping. */
    public SimpleTtlCache(long ttlMillis, int maxEntries, LongSupplier clock) {
        this(null, ttlMillis, maxEntries, clock);
    }

    private SimpleTtlCache(String name, long ttlMillis, int maxEntries, LongSupplier clock) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be >= 1");
        }
        this.name = name;
        this.ttlMillis = ttlMillis;
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    /**
     * A cache that reports its size under {@code name} in the periodic memory
     * diagnostics log (BOR-90). Use this for any cache whose values are large.
     */
    public static <K, V> SimpleTtlCache<K, V> named(String name, long ttlMillis, int maxEntries) {
        SimpleTtlCache<K, V> cache = new SimpleTtlCache<>(name, ttlMillis, maxEntries, System::currentTimeMillis);
        REGISTRY.add(new WeakReference<>(cache));
        return cache;
    }

    /** Name given at construction, or null for anonymous caches. */
    public String name() {
        return name;
    }

    /** Snapshot of every live named cache: name → entry count. */
    public static Map<String, Integer> registrySizes() {
        Map<String, Integer> sizes = new java.util.TreeMap<>();
        for (WeakReference<SimpleTtlCache<?, ?>> ref : REGISTRY) {
            SimpleTtlCache<?, ?> cache = ref.get();
            if (cache == null) {
                REGISTRY.remove(ref);
                continue;
            }
            sizes.merge(cache.name, cache.size(), Integer::sum);
        }
        return sizes;
    }

    /** Get the cached value or compute, cache and return it (single-flight per key). */
    public V getOrCompute(K key, Supplier<V> loader) {
        long now = clock.getAsLong();
        Entry<V> cached = map.get(key);
        if (cached != null) {
            if (cached.expiresAtMillis() > now) {
                return cached.value();
            }
            // Free the expired value now rather than when the bound is hit.
            map.remove(key, cached);
        }

        CompletableFuture<V> mine = new CompletableFuture<>();
        CompletableFuture<V> existing = inFlight.putIfAbsent(key, mine);
        if (existing != null) {
            return join(existing);
        }
        try {
            // Any miss is already paying for a heavy load; a sweep over a
            // handful of entries is free by comparison and releases dead heap.
            sweepExpired(now);
            V value = loader.get();
            store(key, value, now);
            mine.complete(value);
            return value;
        } catch (Throwable t) {
            mine.completeExceptionally(t);
            throw t;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    private static <V> V join(CompletableFuture<V> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw e;
        }
    }

    private void store(K key, V value, long now) {
        if (map.size() >= maxEntries) {
            sweepExpired(now);
            if (map.size() >= maxEntries) {
                map.clear();
            }
        }
        map.put(key, new Entry<>(value, now + ttlMillis));
    }

    /** Drop every expired entry now. Safe to call from a scheduler. */
    public void sweepExpired() {
        sweepExpired(clock.getAsLong());
    }

    private void sweepExpired(long now) {
        map.entrySet().removeIf(e -> e.getValue().expiresAtMillis() <= now);
    }

    /** Drop everything (e.g. when underlying data is known to have changed). */
    public void invalidateAll() {
        map.clear();
    }

    /** Number of live (possibly expired-but-unswept) entries. */
    public int size() {
        return map.size();
    }
}
