package ge.tastyerp.config.repository;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Persists the SHARED "unreal / exception" customer set in a single Firestore
 * doc (config/unreal_customers, field "ids"). RS.ge issues waybills for
 * customers that are not real business partners; flagging them here removes
 * their sales/purchases from the Real Totals and buckets their debt as an
 * exception in Audit Control.
 *
 * Canonical (leading-zero-stripped) ids so it is robust to TIN variants.
 * Data access only — no business logic.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class UnrealCustomerRepository {

    private static final String COLLECTION = "config";
    private static final String DOCUMENT = "unreal_customers";
    private static final String FIELD = "ids";

    private final Firestore firestore;

    @SuppressWarnings("unchecked")
    public List<String> findAll() {
        try {
            DocumentReference docRef = firestore.collection(COLLECTION).document(DOCUMENT);
            DocumentSnapshot snapshot = docRef.get().get();
            if (!snapshot.exists()) {
                return new ArrayList<>();
            }
            List<Object> raw = (List<Object>) snapshot.get(FIELD);
            if (raw == null) {
                return new ArrayList<>();
            }
            List<String> ids = new ArrayList<>(raw.size());
            for (Object o : raw) {
                if (o != null) ids.add(String.valueOf(o));
            }
            return ids;
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching unreal customers: {}", e.getMessage());
            Thread.currentThread().interrupt();
            return new ArrayList<>();
        }
    }

    public void saveAll(List<String> ids) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put(FIELD, ids);
            data.put("updatedAt", com.google.cloud.Timestamp.now());
            firestore.collection(COLLECTION).document(DOCUMENT).set(data).get();
            log.debug("Saved {} unreal customers", ids.size());
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error saving unreal customers: {}", e.getMessage());
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to save unreal customers", e);
        }
    }
}
