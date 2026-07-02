package ge.tastyerp.payment.service.audit;

import ge.tastyerp.common.util.SimpleTtlCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
