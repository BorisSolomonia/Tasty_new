package ge.tastyerp.payment.service.audit;

import ge.tastyerp.common.util.SimpleTtlCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BOR-75: behavior of the dependency-free TTL cache used for RS.ge movements. */
class SimpleTtlCacheTest {

    @Test
    @DisplayName("Second read within TTL is served from cache (loader called once)")
    void cachesWithinTtl() {
        SimpleTtlCache<String, String> cache = new SimpleTtlCache<>(60_000, 4);
        AtomicInteger loads = new AtomicInteger();

        assertEquals("v1", cache.getOrCompute("k", () -> "v" + loads.incrementAndGet()));
        assertEquals("v1", cache.getOrCompute("k", () -> "v" + loads.incrementAndGet()));
        assertEquals(1, loads.get());
    }

    @Test
    @DisplayName("Entry expires after TTL (loader called again)")
    void expiresAfterTtl() throws InterruptedException {
        SimpleTtlCache<String, String> cache = new SimpleTtlCache<>(30, 4);
        AtomicInteger loads = new AtomicInteger();

        cache.getOrCompute("k", () -> "v" + loads.incrementAndGet());
        Thread.sleep(60);
        assertEquals("v2", cache.getOrCompute("k", () -> "v" + loads.incrementAndGet()));
        assertEquals(2, loads.get());
    }

    @Test
    @DisplayName("invalidateAll forces a reload")
    void invalidateAll() {
        SimpleTtlCache<String, String> cache = new SimpleTtlCache<>(60_000, 4);
        AtomicInteger loads = new AtomicInteger();

        cache.getOrCompute("k", () -> "v" + loads.incrementAndGet());
        cache.invalidateAll();
        assertEquals(0, cache.size());
        assertEquals("v2", cache.getOrCompute("k", () -> "v" + loads.incrementAndGet()));
    }

    @Test
    @DisplayName("maxEntries bound never blocks writes")
    void boundedSize() {
        SimpleTtlCache<Integer, Integer> cache = new SimpleTtlCache<>(60_000, 3);
        for (int i = 0; i < 10; i++) {
            final int v = i;
            assertEquals(v, cache.getOrCompute(v, () -> v));
        }
        // Last written key is always retrievable without recompute.
        AtomicInteger loads = new AtomicInteger();
        assertEquals(9, cache.getOrCompute(9, () -> {
            loads.incrementAndGet();
            return 9;
        }));
        assertEquals(0, loads.get());
    }

    // ---- BOR-81 finding B-9 / BOR-90 finding M-3 regressions ----

    @Test
    @DisplayName("Concurrent misses on one key share a single load (no cache stampede)")
    void singleFlightUnderConcurrentMisses() throws Exception {
        SimpleTtlCache<String, String> cache = new SimpleTtlCache<>(60_000, 4);
        AtomicInteger loads = new AtomicInteger();
        int threads = 12;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch loaderEntered = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return cache.getOrCompute("range", () -> {
                        loads.incrementAndGet();
                        loaderEntered.countDown();
                        try {
                            Thread.sleep(150); // simulate the slow RS.ge fetch
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return "feed";
                    });
                }));
            }
            start.countDown();
            assertTrue(loaderEntered.await(5, TimeUnit.SECONDS));
            for (Future<String> f : results) {
                assertEquals("feed", f.get(5, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, loads.get(), "every concurrent miss must join the one in-flight load");
    }

    @Test
    @DisplayName("Reading an expired key frees it immediately (no pinning until the bound is hit)")
    void expiredEntryIsFreedOnRead() {
        AtomicLong now = new AtomicLong(1_000_000);
        SimpleTtlCache<String, String> cache = new SimpleTtlCache<>(1_000, 16, now::get);
        AtomicInteger loads = new AtomicInteger();

        cache.getOrCompute("a", () -> "v" + loads.incrementAndGet());
        assertEquals(1, cache.size());

        now.addAndGet(5_000); // well past TTL, no other keys written
        assertEquals("v2", cache.getOrCompute("a", () -> "v" + loads.incrementAndGet()));
        assertEquals(1, cache.size(), "the expired entry was replaced, not kept alongside");
        assertEquals(2, loads.get());
    }

    @Test
    @DisplayName("A miss on any key sweeps other expired keys; sweepExpired() does the same on demand")
    void expiredEntriesAreSweptOnMissAndOnDemand() {
        AtomicLong now = new AtomicLong(1_000_000);
        SimpleTtlCache<String, String> cache = new SimpleTtlCache<>(1_000, 16, now::get);

        cache.getOrCompute("jan", () -> "big-feed-1");
        cache.getOrCompute("feb", () -> "big-feed-2");
        assertEquals(2, cache.size());

        now.addAndGet(5_000);
        cache.getOrCompute("mar", () -> "big-feed-3"); // miss on a new key
        assertEquals(1, cache.size(), "jan and feb were expired and must be gone after any miss");

        now.addAndGet(5_000);
        cache.sweepExpired();
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("A throwing loader caches nothing and the next call retries")
    void loaderFailureIsNotCached() {
        SimpleTtlCache<String, String> cache = new SimpleTtlCache<>(60_000, 4);
        AtomicInteger loads = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> cache.getOrCompute("k", () -> {
            loads.incrementAndGet();
            throw new IllegalStateException("RS.ge down");
        }));
        assertEquals(0, cache.size());
        assertEquals("ok", cache.getOrCompute("k", () -> {
            loads.incrementAndGet();
            return "ok";
        }));
        assertEquals(2, loads.get());
    }
}
