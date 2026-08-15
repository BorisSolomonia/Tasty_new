package ge.tastyerp.common.infrastructure;

import ge.tastyerp.common.util.SimpleTtlCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** BOR-90: the periodic memory line must render and must include named-cache sizes. */
class MemoryDiagnosticsTest {

    @Test
    @DisplayName("Snapshot line reports heap, non-heap, threads and named cache sizes")
    void snapshotLineIncludesNamedCaches() {
        SimpleTtlCache<String, String> cache = SimpleTtlCache.named("test.diagnostics", 60_000, 4);
        cache.getOrCompute("a", () -> "1");
        cache.getOrCompute("b", () -> "2");

        String line = new MemoryDiagnostics().snapshotLine();

        assertTrue(line.startsWith("MEM heap "), line);
        assertTrue(line.contains("non-heap"), line);
        assertTrue(line.contains("threads"), line);
        assertTrue(line.contains("test.diagnostics=2"), line);
    }

    @Test
    @DisplayName("Anonymous caches are not registered; named ones are, and sizes track eviction")
    void registryTracksOnlyNamedCaches() {
        SimpleTtlCache<String, String> anonymous = new SimpleTtlCache<>(60_000, 4);
        anonymous.getOrCompute("x", () -> "y");
        SimpleTtlCache<String, String> named = SimpleTtlCache.named("test.registry", 60_000, 4);
        named.getOrCompute("k", () -> "v");

        assertTrue(SimpleTtlCache.registrySizes().containsKey("test.registry"));
        assertTrue(SimpleTtlCache.registrySizes().values().stream().allMatch(v -> v >= 0));
        named.invalidateAll();
        assertTrue(SimpleTtlCache.registrySizes().get("test.registry") == 0);
    }
}
