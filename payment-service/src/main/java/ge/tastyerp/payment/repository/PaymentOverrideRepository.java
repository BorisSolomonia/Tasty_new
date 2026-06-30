package ge.tastyerp.payment.repository;

import com.google.cloud.firestore.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * Firestore repository for manual payment overrides (BOR-74).
 *
 * Collection {@code payment_overrides}, document id = override key (a customer
 * id or a payment/transaction id). When {@code markedPaid} is true an authorized
 * user has explicitly settled the balance regardless of bank/API status.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class PaymentOverrideRepository {

    private static final String COLLECTION = "payment_overrides";

    private final Firestore firestore;

    /** Keys (customer/transaction ids) that have been manually marked as paid. */
    public Set<String> findMarkedPaidKeys() {
        Set<String> keys = new HashSet<>();
        try {
            QuerySnapshot snapshot = firestore.collection(COLLECTION)
                    .whereEqualTo("markedPaid", true)
                    .get().get();
            for (QueryDocumentSnapshot doc : snapshot.getDocuments()) {
                keys.add(doc.getId());
            }
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching payment overrides: {}", e.getMessage());
            Thread.currentThread().interrupt();
        }
        return keys;
    }

    public boolean isMarkedPaid(String key) {
        try {
            DocumentSnapshot doc = firestore.collection(COLLECTION).document(key).get().get();
            return doc.exists() && Boolean.TRUE.equals(doc.getBoolean("markedPaid"));
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error reading payment override {}: {}", key, e.getMessage());
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void setMarkedPaid(String key, boolean markedPaid, String note) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("key", key);
            data.put("markedPaid", markedPaid);
            data.put("note", note);
            data.put("updatedAt", com.google.cloud.Timestamp.now());
            firestore.collection(COLLECTION).document(key).set(data).get();
            log.debug("Set payment override {} markedPaid={}", key, markedPaid);
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error saving payment override {}: {}", key, e.getMessage());
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to save payment override", e);
        }
    }
}
