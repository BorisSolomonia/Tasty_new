package ge.tastyerp.payment.infrastructure.firebase;

import com.google.cloud.firestore.Firestore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Warms the Firestore gRPC channel at startup.
 *
 * The first Firestore call after boot pays channel establishment + credential
 * exchange (measured ~11s on the production VM), which otherwise lands on the
 * first user request. A single trivial read moves that cost to startup.
 *
 * Runs on a background thread after ApplicationReadyEvent so it never delays
 * startup or health checks; failure is logged and ignored (the app works
 * without the warm-up, just slower on first request).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FirestoreWarmup {

    private final Firestore firestore;

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        Thread warmupThread = new Thread(() -> {
            long start = System.currentTimeMillis();
            try {
                firestore.collection("audit_exceptions").limit(1).get().get();
                log.info("Firestore warm-up completed in {} ms", System.currentTimeMillis() - start);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Firestore warm-up interrupted");
            } catch (Exception e) {
                log.warn("Firestore warm-up failed (first user request will be slower): {}", e.getMessage());
            }
        }, "firestore-warmup");
        warmupThread.setDaemon(true);
        warmupThread.start();
    }
}
