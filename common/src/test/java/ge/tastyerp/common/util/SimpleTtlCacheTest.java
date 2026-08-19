package ge.tastyerp.common.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BOR-92: a bounded cache must evict the oldest entries when full, not wipe
 * itself — at 400 against ~440 RS.ge chunks every full-period sweep cleared
 * what the previous one had just cached, so the range could never be held.
 */
class SimpleTtlCacheTest {

    @Test
    void overflowEvictsTheEntriesClosestToExpiryNotEverything() {
        AtomicLong clock = new AtomicLong(1_000);
        SimpleTtlCache<String, String> cache = new SimpleTtlCache<>(60_000, 3, clock::get);
        AtomicInteger loads = new AtomicInteger();
        for (String k : new String[]{"a", "b", "c"}) {
            cache.getOrCompute(k, () -> { loads.incrementAndGet(); return k.toUpperCase(); });
            clock.addAndGet(10);
        }
        assertEquals(3, cache.size());
        cache.getOrCompute("d", () -> { loads.incrementAndGet(); return "D"; });   // full: 'a' (oldest) goes, the rest stay
        assertEquals(3, cache.size());
        assertEquals(4, loads.get());
        cache.getOrCompute("b", () -> { loads.incrementAndGet(); return "B2"; });
        cache.getOrCompute("c", () -> { loads.incrementAndGet(); return "C2"; });
        assertEquals(4, loads.get(), "b and c were still cached — only the oldest was evicted");
        assertEquals("A2", cache.getOrCompute("a", () -> { loads.incrementAndGet(); return "A2"; }), "a was the one evicted");
        assertEquals(5, loads.get());
    }

    @Test
    void putStoresWithoutALoaderAndRestartsTheTtl() {
        AtomicLong clock = new AtomicLong(0);
        SimpleTtlCache<String, String> cache = new SimpleTtlCache<>(1_000, 4, clock::get);
        cache.put("k", "fresh");
        AtomicInteger loads = new AtomicInteger();
        assertEquals("fresh", cache.getOrCompute("k", () -> { loads.incrementAndGet(); return "loaded"; }));
        assertEquals(0, loads.get());
        clock.set(1_500);
        assertEquals("loaded", cache.getOrCompute("k", () -> { loads.incrementAndGet(); return "loaded"; }), "expired after the TTL");
    }
}
