package ge.tastyerp.waybill.service.rsge;

import ge.tastyerp.common.dto.waybill.WaybillType;
import ge.tastyerp.waybill.service.WaybillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Warms the RS.ge chunk cache after start-up (BOR-82 pass 2).
 *
 * <p>The chunk cache lives in memory, so every deploy empties it and the first
 * user after a deploy paid the full RS.ge sweep — 15–35 s on the debt page,
 * measured in production. This runs the two sweeps the debt overview and the
 * audit dashboards need (after-cutoff SALE and PURCHASE lists) once, on a
 * daemon thread, right after the service is ready. Failures are logged and
 * ignored: warming is an optimisation, and the next real request simply pays
 * the cost the old way.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RsGeWarmup {

    private final WaybillService waybillService;
    private final ge.tastyerp.waybill.service.InventoryMovementService inventoryMovementService;

    @Value("${rsge.warmup.enabled:true}")
    private boolean enabled;

    /**
     * Where the warm-up starts. The audit page opens on 2023-01-01 → today, so a
     * warm-up that stopped at the cutoff left the first audit request after
     * every deploy paying ~100 s of RS.ge reads (BOR-92 v6.1).
     */
    @Value("${rsge.warmup.start-date:2023-01-01}")
    private String startDate;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled) {
            log.info("RS.ge warm-up disabled (rsge.warmup.enabled=false)");
            return;
        }
        Thread t = new Thread(this::warm, "rsge-warmup");
        t.setDaemon(true);
        t.start();
    }

    private volatile boolean warmed;

    /**
     * Refresh-ahead: every few minutes rebuild the audit's default period, so
     * a waybill created or corrected on RS.ge reaches /audit within one refresh
     * interval plus the open-chunk TTL (2 min), without any reader paying the
     * ~35 s rebuild. The closed-chunk TTL (6 h) bounds how late a *backdated*
     * correction shows; today's and yesterday's chunks refetch every 2 minutes.
     */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${rsge.warmup.refresh-minutes:5}",
            initialDelayString = "${rsge.warmup.refresh-minutes:5}",
            timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    public void refresh() {
        if (!enabled || !warmed) {
            return;
        }
        long t0 = System.currentTimeMillis();
        try {
            inventoryMovementService.refreshRange(startDate, java.time.LocalDate.now().toString());
            log.info("Audit refresh-ahead done in {} ms", System.currentTimeMillis() - t0);
        } catch (Exception e) {
            log.warn("Audit refresh-ahead failed after {} ms (readers fall back to on-demand): {}",
                    System.currentTimeMillis() - t0, e.getMessage());
        }
    }

    void warm() {
        long t0 = System.currentTimeMillis();
        try {
            int sales = waybillService.getWaybills(null, startDate, null, false, WaybillType.SALE).size();
            int purchases = waybillService.getWaybills(null, startDate, null, false, WaybillType.PURCHASE).size();
            log.info("RS.ge warm-up complete: {} sale and {} purchase waybills since {} in {} ms",
                    sales, purchases, startDate, System.currentTimeMillis() - t0);
            // The audit page's default period: build its movements and document totals now,
            // so the first person to open /audit after a deploy does not pay for them.
            String today = java.time.LocalDate.now().toString();
            int lines = inventoryMovementService.getProductMovements(startDate, today).size();
            inventoryMovementService.getDocumentTotals(startDate, today);
            log.info("Audit warm-up complete: {} document lines for {}..{} in {} ms",
                    lines, startDate, today, System.currentTimeMillis() - t0);
            warmed = true;
        } catch (Exception e) {
            log.warn("RS.ge warm-up failed after {} ms (requests will fetch on demand): {}",
                    System.currentTimeMillis() - t0, e.getMessage());
        }
    }
}
