package ge.tastyerp.config.repository;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import ge.tastyerp.common.dto.config.WriteOffRateDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Persists per-category "possible write-off" rates in a single Firestore document
 * (config/write_off_rates, field "rates" = list of {category, percent}).
 *
 * A single doc mirrors the product_categories / excluded_customers pattern.
 * Data access only — NO business logic here.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class WriteOffRateRepository {

    private static final String COLLECTION = "config";
    private static final String DOCUMENT = "write_off_rates";
    private static final String FIELD = "rates";

    private final Firestore firestore;

    @SuppressWarnings("unchecked")
    public List<WriteOffRateDto> findAll() {
        try {
            DocumentReference docRef = firestore.collection(COLLECTION).document(DOCUMENT);
            DocumentSnapshot snapshot = docRef.get().get();
            if (!snapshot.exists()) {
                return new ArrayList<>();
            }
            List<Map<String, Object>> raw = (List<Map<String, Object>>) snapshot.get(FIELD);
            if (raw == null) {
                return new ArrayList<>();
            }
            List<WriteOffRateDto> result = new ArrayList<>();
            for (Map<String, Object> m : raw) {
                Object category = m.get("category");
                Object percent = m.get("percent");
                if (category != null && percent != null) {
                    result.add(WriteOffRateDto.builder()
                            .category(String.valueOf(category))
                            .percent(new BigDecimal(String.valueOf(percent)))
                            .build());
                }
            }
            return result;
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching write-off rates: {}", e.getMessage());
            Thread.currentThread().interrupt();
            return new ArrayList<>();
        }
    }

    public void saveAll(List<WriteOffRateDto> rates) {
        try {
            List<Map<String, Object>> raw = new ArrayList<>();
            for (WriteOffRateDto r : rates) {
                Map<String, Object> m = new HashMap<>();
                m.put("category", r.getCategory());
                // Store as a plain number string; BigDecimal keeps exact precision.
                m.put("percent", r.getPercent() != null ? r.getPercent().toPlainString() : "0");
                raw.add(m);
            }
            Map<String, Object> data = new HashMap<>();
            data.put(FIELD, raw);
            data.put("updatedAt", com.google.cloud.Timestamp.now());
            firestore.collection(COLLECTION).document(DOCUMENT).set(data).get();
            log.debug("Saved {} write-off rates", raw.size());
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error saving write-off rates: {}", e.getMessage());
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to save write-off rates", e);
        }
    }
}
