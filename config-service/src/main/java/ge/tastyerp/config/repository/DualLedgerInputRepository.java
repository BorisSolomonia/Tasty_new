package ge.tastyerp.config.repository;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import ge.tastyerp.common.dto.config.CategoryLedgerInputDto;
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
 * Persists per-category dual-ledger input overrides in a single Firestore doc
 * (config/dual_ledger_inputs, field "inputs" = list of maps). Nullable numeric
 * fields are stored as plain-number strings and omitted when null.
 *
 * Data access only — NO business logic here.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class DualLedgerInputRepository {

    private static final String COLLECTION = "config";
    private static final String DOCUMENT = "dual_ledger_inputs";
    private static final String FIELD = "inputs";

    private final Firestore firestore;

    @SuppressWarnings("unchecked")
    public List<CategoryLedgerInputDto> findAll() {
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
            List<CategoryLedgerInputDto> result = new ArrayList<>();
            for (Map<String, Object> m : raw) {
                Object category = m.get("category");
                if (category == null) continue;
                result.add(CategoryLedgerInputDto.builder()
                        .category(String.valueOf(category))
                        .docPurchasePrice(num(m.get("docPurchasePrice")))
                        .realPurchasePrice(num(m.get("realPurchasePrice")))
                        .realPurchaseKg(num(m.get("realPurchaseKg")))
                        .docSalePrice(num(m.get("docSalePrice")))
                        .realSalePrice(num(m.get("realSalePrice")))
                        .realSaleKg(num(m.get("realSaleKg")))
                        .build());
            }
            return result;
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching dual-ledger inputs: {}", e.getMessage());
            Thread.currentThread().interrupt();
            return new ArrayList<>();
        }
    }

    public void saveAll(List<CategoryLedgerInputDto> inputs) {
        try {
            List<Map<String, Object>> raw = new ArrayList<>();
            for (CategoryLedgerInputDto i : inputs) {
                Map<String, Object> m = new HashMap<>();
                m.put("category", i.getCategory());
                putIfPresent(m, "docPurchasePrice", i.getDocPurchasePrice());
                putIfPresent(m, "realPurchasePrice", i.getRealPurchasePrice());
                putIfPresent(m, "realPurchaseKg", i.getRealPurchaseKg());
                putIfPresent(m, "docSalePrice", i.getDocSalePrice());
                putIfPresent(m, "realSalePrice", i.getRealSalePrice());
                putIfPresent(m, "realSaleKg", i.getRealSaleKg());
                raw.add(m);
            }
            Map<String, Object> data = new HashMap<>();
            data.put(FIELD, raw);
            data.put("updatedAt", com.google.cloud.Timestamp.now());
            firestore.collection(COLLECTION).document(DOCUMENT).set(data).get();
            log.debug("Saved {} dual-ledger inputs", raw.size());
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error saving dual-ledger inputs: {}", e.getMessage());
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to save dual-ledger inputs", e);
        }
    }

    private static BigDecimal num(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void putIfPresent(Map<String, Object> m, String key, BigDecimal v) {
        if (v != null) {
            m.put(key, v.toPlainString());
        }
    }
}
