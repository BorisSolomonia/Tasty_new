package ge.tastyerp.config.repository;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import ge.tastyerp.common.dto.config.ProductCategoryDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Persists product-name -> category overrides in a single Firestore document
 * (config/product_categories, field "categories" = list of {name, category}).
 *
 * A single doc mirrors the product_sales_customers pattern and sidesteps
 * document-id encoding issues for names containing spaces / punctuation.
 *
 * Data access only — NO business logic here.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ProductCategoryRepository {

    private static final String COLLECTION = "config";
    private static final String DOCUMENT = "product_categories";
    private static final String FIELD = "categories";

    private final Firestore firestore;

    @SuppressWarnings("unchecked")
    public List<ProductCategoryDto> findAll() {
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
            List<ProductCategoryDto> result = new ArrayList<>();
            for (Map<String, Object> m : raw) {
                Object name = m.get("name");
                Object category = m.get("category");
                if (name != null && category != null) {
                    result.add(ProductCategoryDto.builder()
                            .name(String.valueOf(name))
                            .category(String.valueOf(category))
                            .build());
                }
            }
            return result;
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching product categories: {}", e.getMessage());
            Thread.currentThread().interrupt();
            return new ArrayList<>();
        }
    }

    public void saveAll(List<ProductCategoryDto> categories) {
        try {
            List<Map<String, Object>> raw = new ArrayList<>();
            for (ProductCategoryDto c : categories) {
                Map<String, Object> m = new HashMap<>();
                m.put("name", c.getName());
                m.put("category", c.getCategory());
                raw.add(m);
            }
            Map<String, Object> data = new HashMap<>();
            data.put(FIELD, raw);
            data.put("updatedAt", com.google.cloud.Timestamp.now());
            firestore.collection(COLLECTION).document(DOCUMENT).set(data).get();
            log.debug("Saved {} product category overrides", raw.size());
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error saving product categories: {}", e.getMessage());
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to save product categories", e);
        }
    }
}
