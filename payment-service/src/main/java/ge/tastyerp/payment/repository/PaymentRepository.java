package ge.tastyerp.payment.repository;

import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.*;
import ge.tastyerp.common.dto.payment.PaymentDto;
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
 * Repository for payments stored in Firebase.
 *
 * Data access only - NO business logic here.
 *
 * Collections:
 * - payments: Bank statement payments (TBC, BOG, Excel)
 * - manualCashPayments: Manual cash payments
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class PaymentRepository {

    private static final String COLLECTION_PAYMENTS = "payments";
    private static final String COLLECTION_MANUAL = "manualCashPayments";

    private final Firestore firestore;

    // ==================== BANK PAYMENTS ====================

    /**
     * Find bank payments by optional filters.
     * Any of the parameters can be null/blank to skip that filter.
     */
    public List<PaymentDto> findBankPayments(String customerId, LocalDate startDate, LocalDate endDate, String source) {
        return findPayments(COLLECTION_PAYMENTS, customerId, startDate, endDate, source);
    }

    /**
     * Get all bank payments.
     */
    public List<PaymentDto> findAll() {
        try {
            QuerySnapshot snapshot = firestore.collection(COLLECTION_PAYMENTS).get().get();
            return snapshot.getDocuments().stream()
                    .map(this::documentToPaymentDto)
                    .toList();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching all payments: {}", e.getMessage());
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        }
    }

    /**
     * Find payment by ID.
     */
    public Optional<PaymentDto> findById(String id) {
        try {
            DocumentSnapshot doc = firestore.collection(COLLECTION_PAYMENTS).document(id).get().get();
            if (!doc.exists()) {
                return Optional.empty();
            }
            return Optional.of(documentToPaymentDto(doc));
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching payment {}: {}", id, e.getMessage());
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Find payments by customer ID.
     */
    public List<PaymentDto> findByCustomerId(String customerId) {
        try {
            QuerySnapshot snapshot = firestore.collection(COLLECTION_PAYMENTS)
                    .whereEqualTo("supplierName", customerId)
                    .get().get();
            return snapshot.getDocuments().stream()
                    .map(this::documentToPaymentDto)
                    .toList();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching payments for customer {}: {}", customerId, e.getMessage());
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        }
    }

    /**
     * Find all payments after a specific date (for customer analysis).
     */
    public List<PaymentDto> findByDateAfter(LocalDate date) {
        try {
            com.google.cloud.Timestamp timestamp = com.google.cloud.Timestamp.of(
                    java.util.Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant()));

            QuerySnapshot snapshot = firestore.collection(COLLECTION_PAYMENTS)
                    .whereGreaterThanOrEqualTo("paymentDate", timestamp)
                    .get().get();
            return snapshot.getDocuments().stream()
                    .map(this::documentToPaymentDto)
                    .toList();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching payments after {}: {}", date, e.getMessage());
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        }
    }

    /**
     * Find payments for a specific customer after a date (for customer analysis).
     */
    public List<PaymentDto> findByCustomerIdAndDateAfter(String customerId, LocalDate date) {
        try {
            com.google.cloud.Timestamp timestamp = com.google.cloud.Timestamp.of(
                    java.util.Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant()));

            QuerySnapshot snapshot = firestore.collection(COLLECTION_PAYMENTS)
                    .whereEqualTo("supplierName", customerId)
                    .whereGreaterThanOrEqualTo("paymentDate", timestamp)
                    .get().get();
            return snapshot.getDocuments().stream()
                    .map(this::documentToPaymentDto)
                    .toList();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching payments for customer {} after {}: {}", customerId, date, e.getMessage());
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        }
    }

    /**
     * Find manual cash payments by customer ID.
     */
    public List<PaymentDto> findManualPaymentsByCustomerId(String customerId) {
        try {
            QuerySnapshot snapshot = firestore.collection(COLLECTION_MANUAL)
                    .whereEqualTo("customerId", customerId)
                    .get().get();
            return snapshot.getDocuments().stream()
                    .map(this::documentToPaymentDto)
                    .toList();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching manual payments for customer {}: {}", customerId, e.getMessage());
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        }
    }

    /**
     * Get all unique codes from existing payments.
     * Used for O(1) duplicate detection.
     */
    public Set<String> getAllUniqueCodes() {
        Set<String> codes = new HashSet<>();
        try {
            QuerySnapshot snapshot = firestore.collection(COLLECTION_PAYMENTS).get().get();
            for (DocumentSnapshot doc : snapshot.getDocuments()) {
                String code = doc.getString("uniqueCode");
                if (code != null) {
                    codes.add(code);
                } else {
                    // Reconstruct code from fields (legacy data)
                    String reconstructed = reconstructUniqueCode(doc);
                    if (reconstructed != null) {
                        codes.add(reconstructed);
                    }
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching unique codes: {}", e.getMessage());
            Thread.currentThread().interrupt();
        }
        return codes;
    }

    /**
     * Get unique codes for payments within the active payment window (after cutoff).
     * This keeps duplicate detection fast as data grows.
     */
    public Set<String> getAllUniqueCodesAfterCutoff() {
        Set<String> codes = new HashSet<>();
        try {
            QuerySnapshot snapshot = firestore.collection(COLLECTION_PAYMENTS)
                    .whereEqualTo("isAfterCutoff", true)
                    .select("uniqueCode", "paymentDate", "amount", "supplierName", "balance")
                    .get().get();

            for (DocumentSnapshot doc : snapshot.getDocuments()) {
                String code = doc.getString("uniqueCode");
                if (code != null) {
                    codes.add(code);
                } else {
                    String reconstructed = reconstructUniqueCode(doc);
                    if (reconstructed != null) {
                        codes.add(reconstructed);
                    }
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching unique codes (after cutoff): {}", e.getMessage());
            Thread.currentThread().interrupt();
        }
        return codes;
    }

    /**
     * Sum payments by source.
     */
    public BigDecimal sumPaymentsBySource(String source) {
        try {
            QuerySnapshot snapshot = firestore.collection(COLLECTION_PAYMENTS)
                    .whereEqualTo("source", source)
                    .get().get();

            return snapshot.getDocuments().stream()
                    .map(doc -> {
                        Double amount = doc.getDouble("amount");
                        return amount != null ? BigDecimal.valueOf(amount) : BigDecimal.ZERO;
                    })
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error summing payments for source {}: {}", source, e.getMessage());
            Thread.currentThread().interrupt();
            return BigDecimal.ZERO;
        }
    }

    /**
     * Save a bank payment.
     */
    public PaymentDto save(PaymentDto payment) {
        try {
            Map<String, Object> data = paymentDtoToMap(payment);
            data.put("uploadedAt", com.google.cloud.Timestamp.now());

            DocumentReference docRef = resolvePaymentDoc(payment);
            docRef.set(data).get();

            payment.setId(docRef.getId());
            log.debug("Saved payment: {}", docRef.getId());

            return payment;
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error saving payment: {}", e.getMessage());
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to save payment", e);
        }
    }

    /**
     * Save bank payments in a single Firestore batch.
     */
    public void saveAll(List<PaymentDto> payments) {
        if (payments == null || payments.isEmpty()) {
            return;
        }

        try {
            WriteBatch batch = buildBatch(payments);
            batch.commit().get();
            log.debug("Batch saved {} payments", payments.size());
        } catch (InterruptedException e) {
            String rootMessage = resolveRootCauseMessage(e);
            log.warn("Batch save interrupted (count={}): {}. Retrying once.", payments.size(), rootMessage);
            Thread.interrupted();
            try {
                WriteBatch retryBatch = buildBatch(payments);
                retryBatch.commit().get();
                log.info("Batch save retry succeeded (count={})", payments.size());
            } catch (InterruptedException retryInterrupted) {
                String retryMessage = resolveRootCauseMessage(retryInterrupted);
                log.error("Batch save retry interrupted (count={}): {}", payments.size(), retryMessage, retryInterrupted);
                Thread.currentThread().interrupt();
                throw new RuntimeException("Failed to batch save payments: " + retryMessage, retryInterrupted);
            } catch (ExecutionException retryExecution) {
                String retryMessage = resolveRootCauseMessage(retryExecution);
                log.error("Batch save retry failed (count={}): {}", payments.size(), retryMessage, retryExecution);
                throw new RuntimeException("Failed to batch save payments: " + retryMessage, retryExecution);
            }
        } catch (ExecutionException e) {
            String rootMessage = resolveRootCauseMessage(e);
            log.error("Error batch saving payments (count={}): {}", payments.size(), rootMessage, e);
            throw new RuntimeException("Failed to batch save payments: " + rootMessage, e);
        }
    }

    /**
     * Delete a bank payment.
     */
    public void delete(String id) {
        try {
            firestore.collection(COLLECTION_PAYMENTS).document(id).delete().get();
            log.debug("Deleted payment: {}", id);
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error deleting payment {}: {}", id, e.getMessage());
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to delete payment", e);
        }
    }

    public int deleteBankPaymentsBySources(List<String> sources) {
        if (sources == null || sources.isEmpty()) {
            return 0;
        }

        int deleted = 0;
        for (String source : sources) {
            if (source == null || source.isBlank()) {
                continue;
            }
            deleted += deletePaymentsBySource(source.trim().toLowerCase(Locale.ROOT));
        }
        return deleted;
    }

    // ==================== MANUAL PAYMENTS ====================

    /**
     * Find manual cash payments by optional filters.
     * Any of the parameters can be null/blank to skip that filter.
     */
    public List<PaymentDto> findManualPayments(String customerId, LocalDate startDate, LocalDate endDate, String source) {
        return findPayments(COLLECTION_MANUAL, customerId, startDate, endDate, source);
    }

    /**
     * Get all manual cash payments.
     */
    public List<PaymentDto> findAllManualPayments() {
        try {
            QuerySnapshot snapshot = firestore.collection(COLLECTION_MANUAL).get().get();
            return snapshot.getDocuments().stream()
                    .map(this::documentToPaymentDto)
                    .toList();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching manual payments: {}", e.getMessage());
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        }
    }

    /**
     * Find manual payment by ID.
     */
    public Optional<PaymentDto> findManualPaymentById(String id) {
        try {
            DocumentSnapshot doc = firestore.collection(COLLECTION_MANUAL).document(id).get().get();
            if (!doc.exists()) {
                return Optional.empty();
            }
            return Optional.of(documentToPaymentDto(doc));
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching manual payment {}: {}", id, e.getMessage());
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Save a manual cash payment.
     */
    public PaymentDto saveManualPayment(PaymentDto payment) {
        try {
            Map<String, Object> data = paymentDtoToMap(payment);
            data.put("uploadedAt", com.google.cloud.Timestamp.now());

            DocumentReference docRef = firestore.collection(COLLECTION_MANUAL).document();
            docRef.set(data).get();

            payment.setId(docRef.getId());
            log.debug("Saved manual payment: {}", docRef.getId());

            return payment;
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error saving manual payment: {}", e.getMessage());
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to save manual payment", e);
        }
    }

    /**
     * Update a manual cash payment.
     */
    public PaymentDto updateManualPayment(PaymentDto payment) {
        try {
            Map<String, Object> data = paymentDtoToMap(payment);
            data.put("updatedAt", com.google.cloud.Timestamp.now());

            firestore.collection(COLLECTION_MANUAL).document(payment.getId()).set(data).get();
            log.debug("Updated manual payment: {}", payment.getId());

            return payment;
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error updating manual payment: {}", e.getMessage());
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to update manual payment", e);
        }
    }

    /**
     * Delete a manual cash payment.
     */
    public void deleteManualPayment(String id) {
        try {
            firestore.collection(COLLECTION_MANUAL).document(id).delete().get();
            log.debug("Deleted manual payment: {}", id);
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error deleting manual payment {}: {}", id, e.getMessage());
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to delete manual payment", e);
        }
    }

    // ==================== HELPER METHODS ====================

    private List<PaymentDto> findPayments(
            String collection,
            String customerId,
            LocalDate startDate,
            LocalDate endDate,
            String source) {
        try {
            Query query = firestore.collection(collection);

            if (customerId != null && !customerId.isBlank()) {
                String customerField = COLLECTION_MANUAL.equals(collection) ? "customerId" : "supplierName";
                query = query.whereEqualTo(customerField, customerId);
            }

            if (source != null && !source.isBlank()) {
                query = query.whereEqualTo("source", source);
            }

            if (startDate != null) {
                query = query.whereGreaterThanOrEqualTo("paymentDate",
                        com.google.cloud.Timestamp.of(java.util.Date.from(
                                startDate.atStartOfDay(ZoneId.systemDefault()).toInstant())));
            }

            if (endDate != null) {
                query = query.whereLessThan("paymentDate",
                        com.google.cloud.Timestamp.of(java.util.Date.from(
                                endDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant())));
            }

            QuerySnapshot snapshot = query.get().get();
            return snapshot.getDocuments().stream()
                    .map(this::documentToPaymentDto)
                    .toList();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching payments from {}: {}", collection, e.getMessage());
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        }
    }

    private PaymentDto documentToPaymentDto(DocumentSnapshot doc) {
        LocalDate paymentDate = null;
        Object dateObj = doc.get("paymentDate");
        if (dateObj instanceof com.google.cloud.Timestamp) {
            paymentDate = ((com.google.cloud.Timestamp) dateObj).toDate()
                    .toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        }

        LocalDateTime uploadedAt = null;
        Object uploadedObj = doc.get("uploadedAt");
        if (uploadedObj instanceof com.google.cloud.Timestamp) {
            uploadedAt = ((com.google.cloud.Timestamp) uploadedObj).toDate()
                    .toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
        }

        Double amount = doc.getDouble("amount");
        Double balance = doc.getDouble("balance");

        String supplierName = doc.getString("supplierName");
        String manualCustomerId = doc.getString("customerId");
        String resolvedCustomerId = supplierName != null ? supplierName : manualCustomerId;
        String source = doc.getString("source");
        if (source == null && supplierName == null && manualCustomerId != null) {
            source = "manual-cash";
        }

        return PaymentDto.builder()
                .id(doc.getId())
                .uniqueCode(doc.getString("uniqueCode"))
                .customerId(resolvedCustomerId)
                .customerName(doc.getString("customerName"))
                .paymentDate(paymentDate)
                .amount(amount != null ? BigDecimal.valueOf(amount) : BigDecimal.ZERO)
                .balance(balance != null ? BigDecimal.valueOf(balance) : BigDecimal.ZERO)
                .description(doc.getString("description"))
                .source(source)
                .isAfterCutoff(Boolean.TRUE.equals(doc.getBoolean("isAfterCutoff")))
                .uploadedAt(uploadedAt)
                .excelRowIndex(doc.getLong("excelRowIndex") != null ? doc.getLong("excelRowIndex").intValue() : null)
                .build();
    }

    private Map<String, Object> paymentDtoToMap(PaymentDto payment) {
        Map<String, Object> data = new HashMap<>();
        data.put("uniqueCode", payment.getUniqueCode());
        data.put("supplierName", payment.getCustomerId());
        data.put("customerName", payment.getCustomerName());
        data.put("amount", payment.getAmount() != null ? payment.getAmount().doubleValue() : 0.0);
        data.put("balance", payment.getBalance() != null ? payment.getBalance().doubleValue() : 0.0);
        data.put("description", payment.getDescription());
        data.put("source", payment.getSource());
        data.put("isAfterCutoff", payment.isAfterCutoff());

        if (payment.getPaymentDate() != null) {
            data.put("paymentDate", com.google.cloud.Timestamp.of(
                    java.util.Date.from(payment.getPaymentDate()
                            .atStartOfDay(ZoneId.systemDefault()).toInstant())));
        }

        if (payment.getUploadedAt() != null) {
            data.put("uploadedAt", com.google.cloud.Timestamp.of(
                    java.util.Date.from(payment.getUploadedAt()
                            .atZone(ZoneId.systemDefault()).toInstant())));
        }

        if (payment.getExcelRowIndex() != null) {
            data.put("excelRowIndex", payment.getExcelRowIndex());
        }

        return data;
    }

    private String reconstructUniqueCode(DocumentSnapshot doc) {
        try {
            Object dateObj = doc.get("paymentDate");
            String dateStr = null;
            if (dateObj instanceof com.google.cloud.Timestamp) {
                LocalDate date = ((com.google.cloud.Timestamp) dateObj).toDate()
                        .toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                dateStr = date.toString();
            }

            Double amount = doc.getDouble("amount");
            String customerId = doc.getString("supplierName");
            Double balance = doc.getDouble("balance");

            if (dateStr == null || amount == null || customerId == null) {
                return null;
            }

            int amountCents = (int) Math.round(amount * 100);
            int balanceCents = balance != null ? (int) Math.round(balance * 100) : 0;

            return String.format("%s|%d|%s|%d", dateStr, amountCents, customerId.trim(), balanceCents);
        } catch (Exception e) {
            log.debug("Could not reconstruct uniqueCode for doc {}: {}", doc.getId(), e.getMessage());
            return null;
        }
    }

    public LocalDate findLatestPaymentDateForSources(List<String> sources, LocalDate startDate) {
        if (sources == null || sources.isEmpty()) {
            return null;
        }

        LocalDate latest = null;
        for (String source : sources) {
            if (source == null || source.isBlank()) {
                continue;
            }
            LocalDate candidate = findLatestPaymentDateForSource(source.trim().toLowerCase(Locale.ROOT), startDate);
            if (candidate != null && (latest == null || candidate.isAfter(latest))) {
                latest = candidate;
            }
        }
        return latest;
    }

    public Map<String, BigDecimal> getBankPaymentAggregatesForDate(LocalDate date, List<String> sources) {
        if (date == null || sources == null || sources.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, BigDecimal> totals = new HashMap<>();
        for (String source : sources) {
            if (source == null || source.isBlank()) {
                continue;
            }
            accumulateTotalsForDate(source.trim().toLowerCase(Locale.ROOT), date, totals);
        }
        return totals;
    }

    private LocalDate findLatestPaymentDateForSource(String source, LocalDate startDate) {
        try {
            Query query = firestore.collection(COLLECTION_PAYMENTS)
                    .whereEqualTo("source", source)
                    .orderBy("paymentDate", Query.Direction.DESCENDING)
                    .limit(1);

            if (startDate != null) {
                query = query.whereGreaterThanOrEqualTo("paymentDate", toTimestamp(startDate));
            }

            QuerySnapshot snapshot = query.get().get();
            if (snapshot.isEmpty()) {
                return null;
            }

            Object dateObj = snapshot.getDocuments().get(0).get("paymentDate");
            if (dateObj instanceof com.google.cloud.Timestamp) {
                return ((com.google.cloud.Timestamp) dateObj).toDate()
                        .toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            }
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching latest payment date for source {}: {}", source, e.getMessage());
            Thread.currentThread().interrupt();
        }
        return null;
    }

    private void accumulateTotalsForDate(String source, LocalDate date, Map<String, BigDecimal> totals) {
        try {
            QuerySnapshot snapshot = firestore.collection(COLLECTION_PAYMENTS)
                    .whereEqualTo("source", source)
                    .whereGreaterThanOrEqualTo("paymentDate", toTimestamp(date))
                    .whereLessThan("paymentDate", toTimestamp(date.plusDays(1)))
                    .get().get();

            for (DocumentSnapshot doc : snapshot.getDocuments()) {
                String customerId = doc.getString("supplierName");
                Double amount = doc.getDouble("amount");
                if (customerId == null || amount == null) {
                    continue;
                }
                totals.merge(customerId, BigDecimal.valueOf(amount), BigDecimal::add);
            }
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error aggregating payments for {} on {}: {}", source, date, e.getMessage());
            Thread.currentThread().interrupt();
        }
    }

    private int deletePaymentsBySource(String source) {
        int deleted = 0;
        try {
            QuerySnapshot snapshot = firestore.collection(COLLECTION_PAYMENTS)
                    .whereEqualTo("source", source)
                    .get().get();

            List<QueryDocumentSnapshot> docs = snapshot.getDocuments();
            for (int i = 0; i < docs.size(); i += 500) {
                WriteBatch batch = firestore.batch();
                List<QueryDocumentSnapshot> chunk = docs.subList(i, Math.min(i + 500, docs.size()));
                for (QueryDocumentSnapshot doc : chunk) {
                    batch.delete(doc.getReference());
                }
                batch.commit().get();
                deleted += chunk.size();
            }
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error deleting payments for source {}: {}", source, e.getMessage());
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to delete payments for source " + source, e);
        }
        return deleted;
    }

    private com.google.cloud.Timestamp toTimestamp(LocalDate date) {
        return com.google.cloud.Timestamp.of(java.util.Date.from(
                date.atStartOfDay(ZoneId.systemDefault()).toInstant()));
    }

    private WriteBatch buildBatch(List<PaymentDto> payments) {
        WriteBatch batch = firestore.batch();
        for (PaymentDto payment : payments) {
            Map<String, Object> data = paymentDtoToMap(payment);
            if (!data.containsKey("uploadedAt")) {
                data.put("uploadedAt", com.google.cloud.Timestamp.now());
            }

            DocumentReference docRef = resolvePaymentDoc(payment);
            batch.set(docRef, data);
            payment.setId(docRef.getId());
        }
        return batch;
    }

    private DocumentReference resolvePaymentDoc(PaymentDto payment) {
        String uniqueCode = payment != null ? payment.getUniqueCode() : null;
        if (uniqueCode != null && !uniqueCode.isBlank()) {
            return firestore.collection(COLLECTION_PAYMENTS).document(uniqueCode);
        }
        return firestore.collection(COLLECTION_PAYMENTS).document();
    }

    private String resolveRootCauseMessage(Exception e) {
        if (e instanceof ExecutionException executionException) {
            Throwable cause = executionException.getCause();
            if (cause != null) {
                String message = cause.getMessage();
                return message != null ? message : cause.getClass().getSimpleName();
            }
        }
        String message = e.getMessage();
        return message != null ? message : e.getClass().getSimpleName();
    }
}
