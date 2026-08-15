package ge.tastyerp.common.infrastructure;

import ge.tastyerp.common.util.SimpleTtlCache;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodic one-line memory summary in the service log (BOR-90).
 *
 * <p>The only diagnostic channel this deployment has is {@code docker compose
 * logs}: there is no metrics scraper, and Actuator is reachable solely from
 * inside the compose network. Before this, a container that was OOM-killed left
 * no trace of how memory had grown beforehand. This logs, every
 * {@code tasty.memory-diagnostics.interval-seconds} (default 300, 0 disables):</p>
 * <pre>
 * MEM heap 212/460 MB (46%) committed 300 | non-heap 118 MB | threads 41 | caches {movements=1, ...}
 * </pre>
 * <p>and escalates to WARN when the heap is above 90% of its maximum, so a
 * genuine leak shows as a rising series long before {@code exit 137}.
 * Dependency-free (JMX MXBeans, one daemon thread) so it lives in
 * {@code common} and is picked up by all three services' component scan.</p>
 */
@Slf4j
@Component
public class MemoryDiagnostics {

    private static final long MB = 1024L * 1024L;

    @Value("${tasty.memory-diagnostics.interval-seconds:300}")
    private long intervalSeconds;

    @Value("${tasty.memory-diagnostics.warn-heap-percent:90}")
    private int warnHeapPercent;

    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private ScheduledExecutorService scheduler;

    @PostConstruct
    void start() {
        if (intervalSeconds <= 0) {
            log.info("Memory diagnostics disabled (tasty.memory-diagnostics.interval-seconds<=0)");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "memory-diagnostics");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::logSnapshot, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("Memory diagnostics every {}s (max heap {} MB)", intervalSeconds,
                memory.getHeapMemoryUsage().getMax() / MB);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** Package-private so a test can render the line without a scheduler. */
    String snapshotLine() {
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();
        long max = heap.getMax() > 0 ? heap.getMax() : heap.getCommitted();
        int pct = max > 0 ? (int) (heap.getUsed() * 100 / max) : 0;
        Map<String, Integer> caches = SimpleTtlCache.registrySizes();
        return String.format("MEM heap %d/%d MB (%d%%) committed %d | non-heap %d MB | threads %d | caches %s",
                heap.getUsed() / MB, max / MB, pct, heap.getCommitted() / MB,
                nonHeap.getUsed() / MB, threads.getThreadCount(), caches);
    }

    int heapUsedPercent() {
        MemoryUsage heap = memory.getHeapMemoryUsage();
        long max = heap.getMax() > 0 ? heap.getMax() : heap.getCommitted();
        return max > 0 ? (int) (heap.getUsed() * 100 / max) : 0;
    }

    void logSnapshot() {
        try {
            String line = snapshotLine();
            if (heapUsedPercent() >= warnHeapPercent) {
                log.warn("{} — heap above {}% of max; if this persists after load subsides, "
                        + "capture /actuator/metrics/jvm.memory.used and the caches list above",
                        line, warnHeapPercent);
            } else {
                log.info(line);
            }
        } catch (RuntimeException e) {
            // Diagnostics must never take the service down.
            log.debug("Memory diagnostics failed: {}", e.toString());
        }
    }
}
