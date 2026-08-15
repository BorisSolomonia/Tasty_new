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

    @Value("${rsge.warmup.enabled:true}")
    private boolean enabled;

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

    void warm() {
        long t0 = System.currentTimeMillis();
        try {
            int sales = waybillService.getWaybills(null, null, null, true, WaybillType.SALE).size();
            int purchases = waybillService.getWaybills(null, null, null, true, WaybillType.PURCHASE).size();
            log.info("RS.ge warm-up complete: {} sale and {} purchase waybills since cutoff in {} ms",
                    sales, purchases, System.currentTimeMillis() - t0);
        } catch (Exception e) {
            log.warn("RS.ge warm-up failed after {} ms (requests will fetch on demand): {}",
                    System.currentTimeMillis() - t0, e.getMessage());
        }
    }
}
