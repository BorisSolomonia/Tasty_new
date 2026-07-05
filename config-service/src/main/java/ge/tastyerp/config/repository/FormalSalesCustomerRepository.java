package ge.tastyerp.config.repository;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import ge.tastyerp.common.dto.config.FormalSalesCustomerDto;
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
 * Persists the formal-sales customer set in a single Firestore doc
 * (config/formal_sales_customers, field "customers" = list of
 * {customerId, customerName, commissionPerKg}).
 *
 * Data access only — NO business logic here.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class FormalSalesCustomerRepository {

    private static final String COLLECTION = "config";
    private static final String DOCUMENT = "formal_sales_customers";
    private static final String FIELD = "customers";

    private final Firestore firestore;

    @SuppressWarnings("unchecked")
    public List<FormalSalesCustomerDto> findAll() {
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
            List<FormalSalesCustomerDto> result = new ArrayList<>();
            for (Map<String, Object> m : raw) {
                Object id = m.get("customerId");
                if (id == null) continue;
                Object rate = m.get("commissionPerKg");
                result.add(FormalSalesCustomerDto.builder()
                        .customerId(String.valueOf(id))
                        .customerName(m.get("customerName") != null ? String.valueOf(m.get("customerName")) : null)
                        .commissionPerKg(rate != null ? new BigDecimal(String.valueOf(rate)) : null)
                        .build());
            }
            return result;
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching formal-sales customers: {}", e.getMessage());
            Thread.currentThread().interrupt();
            return new ArrayList<>();
        }
    }

    public void saveAll(List<FormalSalesCustomerDto> customers) {
        try {
            List<Map<String, Object>> raw = new ArrayList<>();
            for (FormalSalesCustomerDto c : customers) {
                Map<String, Object> m = new HashMap<>();
                m.put("customerId", c.getCustomerId());
                if (c.getCustomerName() != null) m.put("customerName", c.getCustomerName());
                m.put("commissionPerKg", c.getCommissionPerKg() != null
                        ? c.getCommissionPerKg().toPlainString() : "0");
                raw.add(m);
            }
            Map<String, Object> data = new HashMap<>();
            data.put(FIELD, raw);
            data.put("updatedAt", com.google.cloud.Timestamp.now());
            firestore.collection(COLLECTION).document(DOCUMENT).set(data).get();
            log.debug("Saved {} formal-sales customers", raw.size());
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error saving formal-sales customers: {}", e.getMessage());
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to save formal-sales customers", e);
        }
    }
}
