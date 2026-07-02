package ge.tastyerp.common.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Minimal thread-safe TTL cache (BOR-75).
 *
 * Deliberately dependency-free (no Caffeine/Redis): the fleet is small
 * single-instance services and the cached values are a handful of date-range
 * keyed lists, so a ConcurrentHashMap with expiry timestamps is sufficient and
 * auditable.
 *
 * Integrity contract: use ONLY for data that is immutable-in-practice within
 * the TTL window (e.g. RS.ge waybill history). Never cache user-editable state
 * (category overrides, real-entity flags, payments) — those must always be
 * read fresh so edits take effect immediately.
 */
public final class SimpleTtlCache<K, V> {

    private record Entry<V>(V value, long expiresAtMillis) {}

    private final Map<K, Entry<V>> map = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final int maxEntries;

    /**
     * @param ttlMillis  how long an entry stays valid
     * @param maxEntries safety bound; when exceeded, expired entries are purged
     *                   and, if still over, the whole cache is cleared (simple
     *                   and safe for small caches — avoids LRU bookkeeping)
     */
    public SimpleTtlCache(long ttlMillis, int maxEntries) {
        this.ttlMillis = ttlMillis;
        this.maxEntries = maxEntries;
    }

    /** Get the cached value or compute, cache and return it. */
    public V getOrCompute(K key, Supplier<V> loader) {
        long now = System.currentTimeMillis();
        Entry<V> cached = map.get(key);
        if (cached != null && cached.expiresAtMillis() > now) {
            return cached.value();
        }
        V value = loader.get();
        if (map.size() >= maxEntries) {
            map.entrySet().removeIf(e -> e.getValue().expiresAtMillis() <= now);
            if (map.size() >= maxEntries) {
                map.clear();
            }
        }
        map.put(key, new Entry<>(value, now + ttlMillis));
        return value;
    }

    /** Drop everything (e.g. when underlying data is known to have changed). */
    public void invalidateAll() {
        map.clear();
    }

    public int size() {
        return map.size();
    }
}
