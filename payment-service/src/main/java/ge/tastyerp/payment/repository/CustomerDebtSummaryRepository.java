package ge.tastyerp.payment.repository;

import com.google.cloud.firestore.*;
import ge.tastyerp.common.dto.aggregation.CustomerDebtSummaryDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 * Repository for customer debt summaries stored in Firebase.
 *
 * Collection: customer_debt_summary
 * Document ID: customerId
 *
 * This stores AGGREGATED data only - no individual transactions!
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class CustomerDebtSummaryRepository {

    private static final String COLLECTION = "customer_debt_summary";

    private final Firestore firestore;

    /**
     * Get debt summary for a specific customer.
     */
    public Optional<CustomerDebtSummaryDto> findById(String customerId) {
        try {
            DocumentSnapshot doc = firestore.collection(COLLECTION)
                    .document(customerId)
                    .get()
                    .get();

            if (!doc.exists()) {
                return Optional.empty();
            }

            return Optional.of(documentToDto(doc));
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching debt summary for customer {}: {}", customerId, e.getMessage());
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Get all debt summaries.
     */
    public List<CustomerDebtSummaryDto> findAll() {
        try {
            QuerySnapshot snapshot = firestore.collection(COLLECTION).get().get();
            return snapshot.getDocuments().stream()
                    .map(this::documentToDto)
                    .toList();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching all debt summaries: {}", e.getMessage());
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        }
    }

    /**
     * Save or update a debt summary.
     * Document ID is the customerId.
     */
    public CustomerDebtSummaryDto save(CustomerDebtSummaryDto summary) {
        try {
            Map<String, Object> data = dtoToMap(summary);
            data.put("lastUpdated", com.google.cloud.Timestamp.now());

            firestore.collection(COLLECTION)
                    .document(summary.getCustomerId())
                    .set(data)
                    .get();

            log.debug("Saved debt summary for customer: {}", summary.getCustomerId());
            return summary;
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error saving debt summary for customer {}: {}", summary.getCustomerId(), e.getMessage());
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to save debt summary", e);
        }
    }

    /**
     * Batch save multiple debt summaries.
     */
    public void saveAll(List<CustomerDebtSummaryDto> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return;
        }

        try {
            WriteBatch batch = firestore.batch();
            com.google.cloud.Timestamp now = com.google.cloud.Timestamp.now();

            for (CustomerDebtSummaryDto summary : summaries) {
                Map<String, Object> data = dtoToMap(summary);
                data.put("lastUpdated", now);

                DocumentReference docRef = firestore.collection(COLLECTION)
                        .document(summary.getCustomerId());
                batch.set(docRef, data);
            }

            batch.commit().get();
            log.info("Batch saved {} debt summaries", summaries.size());
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error batch saving debt summaries: {}", e.getMessage());
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to batch save debt summaries", e);
        }
    }

    /**
     * Delete a debt summary.
     */
    public void delete(String customerId) {
        try {
            firestore.collection(COLLECTION)
                    .document(customerId)
                    .delete()
                    .get();
            log.debug("Deleted debt summary for customer: {}", customerId);
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error deleting debt summary for customer {}: {}", customerId, e.getMessage());
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to delete debt summary", e);
        }
    }

    /**
     * Check if a debt summary exists for a customer.
     */
    public boolean exists(String customerId) {
        try {
            DocumentSnapshot doc = firestore.collection(COLLECTION)
                    .document(customerId)
                    .get()
                    .get();
            return doc.exists();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error checking existence for customer {}: {}", customerId, e.getMessage());
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ==================== HELPER METHODS ====================

    private CustomerDebtSummaryDto documentToDto(DocumentSnapshot doc) {
        return CustomerDebtSummaryDto.builder()
                .customerId(doc.getId())
                .customerName(doc.getString("customerName"))
                .totalSales(toBigDecimal(doc.getDouble("totalSales")))
                .saleCount(toInteger(doc.getLong("saleCount")))
                .lastSaleDate(toLocalDate(doc.get("lastSaleDate")))
                .totalPayments(toBigDecimal(doc.getDouble("totalPayments")))
                .paymentCount(toInteger(doc.getLong("paymentCount")))
                .lastPaymentDate(toLocalDate(doc.get("lastPaymentDate")))
                .totalCashPayments(toBigDecimal(doc.getDouble("totalCashPayments")))
                .cashPaymentCount(toInteger(doc.getLong("cashPaymentCount")))
                .startingDebt(toBigDecimal(doc.getDouble("startingDebt")))
                .startingDebtDate(toLocalDate(doc.get("startingDebtDate")))
                .currentDebt(toBigDecimal(doc.getDouble("currentDebt")))
                .lastUpdated(toLocalDateTime(doc.get("lastUpdated")))
                .updateSource(doc.getString("updateSource"))
                .build();
    }

    private Map<String, Object> dtoToMap(CustomerDebtSummaryDto dto) {
        Map<String, Object> data = new HashMap<>();
        data.put("customerId", dto.getCustomerId());
        data.put("customerName", dto.getCustomerName());
        data.put("totalSales", toDouble(dto.getTotalSales()));
        data.put("saleCount", dto.getSaleCount() != null ? dto.getSaleCount() : 0);
        data.put("lastSaleDate", toTimestamp(dto.getLastSaleDate()));
        data.put("totalPayments", toDouble(dto.getTotalPayments()));
        data.put("paymentCount", dto.getPaymentCount() != null ? dto.getPaymentCount() : 0);
        data.put("lastPaymentDate", toTimestamp(dto.getLastPaymentDate()));
        data.put("totalCashPayments", toDouble(dto.getTotalCashPayments()));
        data.put("cashPaymentCount", dto.getCashPaymentCount() != null ? dto.getCashPaymentCount() : 0);
        data.put("startingDebt", toDouble(dto.getStartingDebt()));
        data.put("startingDebtDate", toTimestamp(dto.getStartingDebtDate()));
        data.put("currentDebt", toDouble(dto.getCurrentDebt()));
        data.put("updateSource", dto.getUpdateSource());
        return data;
    }

    private BigDecimal toBigDecimal(Double value) {
        return value != null ? BigDecimal.valueOf(value) : BigDecimal.ZERO;
    }

    private Integer toInteger(Long value) {
        return value != null ? value.intValue() : 0;
    }

    private Double toDouble(BigDecimal value) {
        return value != null ? value.doubleValue() : 0.0;
    }

    private LocalDate toLocalDate(Object value) {
        if (value instanceof com.google.cloud.Timestamp) {
            return ((com.google.cloud.Timestamp) value).toDate()
                    .toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        }
        return null;
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof com.google.cloud.Timestamp) {
            return ((com.google.cloud.Timestamp) value).toDate()
                    .toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
        }
        return null;
    }

    private com.google.cloud.Timestamp toTimestamp(LocalDate date) {
        if (date == null) return null;
        return com.google.cloud.Timestamp.of(
                java.util.Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant())
        );
    }
}
