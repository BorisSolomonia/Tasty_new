package ge.tastyerp.config.repository;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import ge.tastyerp.common.dto.config.ProductVatRateDto;
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
 * Persists per-product VAT-rate overrides in a single Firestore document
 * (config/product_vat_rates, field "rates" = list of {name, percent}).
 *
 * Mirrors {@code ProductCategoryRepository}. Data access only — no business logic.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ProductVatRateRepository {

    private static final String COLLECTION = "config";
    private static final String DOCUMENT = "product_vat_rates";
    private static final String FIELD = "rates";

    private final Firestore firestore;

    @SuppressWarnings("unchecked")
    public List<ProductVatRateDto> findAll() {
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
            List<ProductVatRateDto> result = new ArrayList<>();
            for (Map<String, Object> m : raw) {
                Object name = m.get("name");
                Object percent = m.get("percent");
                if (name != null && percent != null) {
                    result.add(ProductVatRateDto.builder()
                            .name(String.valueOf(name))
                            .percent(new BigDecimal(String.valueOf(percent)))
                            .build());
                }
            }
            return result;
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching product VAT rates: {}", e.getMessage());
            Thread.currentThread().interrupt();
            return new ArrayList<>();
        }
    }

    public void saveAll(List<ProductVatRateDto> rates) {
        try {
            List<Map<String, Object>> raw = new ArrayList<>();
            for (ProductVatRateDto r : rates) {
                Map<String, Object> m = new HashMap<>();
                m.put("name", r.getName());
                m.put("percent", r.getPercent() != null ? r.getPercent().toPlainString() : "18");
                raw.add(m);
            }
            Map<String, Object> data = new HashMap<>();
            data.put(FIELD, raw);
            data.put("updatedAt", com.google.cloud.Timestamp.now());
            firestore.collection(COLLECTION).document(DOCUMENT).set(data).get();
            log.debug("Saved {} product VAT rates", raw.size());
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error saving product VAT rates: {}", e.getMessage());
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to save product VAT rates", e);
        }
    }
}
