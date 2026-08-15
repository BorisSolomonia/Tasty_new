package ge.tastyerp.payment.repository;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import ge.tastyerp.common.dto.payment.PaymentDto;
import ge.tastyerp.common.util.FutureResults;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 * Repository for manual cash payments.
 *
 * Collection: manualCashPayments
 * Document structure: {
 *   customerId: string,
 *   customerName: string,
 *   amount: number,
 *   paymentDate: Timestamp,
 *   description: string,
 *   createdAt: Timestamp
 * }
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ManualCashPaymentRepository {

    private static final String COLLECTION = "manualCashPayments";

    /** Firestore hard limit on operations per WriteBatch. */
    private static final int FIRESTORE_BATCH_LIMIT = 500;

    private final Firestore firestore;

    /**
     * Find all manual cash payments after a specific date.
     */
    public List<PaymentDto> findByDateAfter(LocalDate date) {
        try {
            Timestamp timestamp = Timestamp.of(java.util.Date.from(
                    date.atStartOfDay(ZoneId.systemDefault()).toInstant()));

            List<PaymentDto> payments = new ArrayList<>();

            for (QueryDocumentSnapshot document : firestore.collection(COLLECTION)
                    .whereGreaterThanOrEqualTo("paymentDate", timestamp)
                    .get()
                    .get()
                    .getDocuments()) {
                payments.add(documentToDto(document));
            }

            return payments;

        } catch (InterruptedException | ExecutionException e) {
            throw readFailure("fetch manual cash payments after " + date, e);
        }
    }

    /**
     * Find manual cash payments for a specific customer after a date.
     */
    public List<PaymentDto> findByCustomerIdAndDateAfter(String customerId, LocalDate date) {
        try {
            Timestamp timestamp = Timestamp.of(java.util.Date.from(
                    date.atStartOfDay(ZoneId.systemDefault()).toInstant()));

            List<PaymentDto> payments = new ArrayList<>();

            for (QueryDocumentSnapshot document : firestore.collection(COLLECTION)
                    .whereEqualTo("customerId", customerId)
                    .whereGreaterThanOrEqualTo("paymentDate", timestamp)
                    .get()
                    .get()
                    .getDocuments()) {
                payments.add(documentToDto(document));
            }

            return payments;

        } catch (InterruptedException | ExecutionException e) {
            throw readFailure("fetch manual cash payments for customer " + customerId + " after " + date, e);
        }
    }

    /**
     * Save a manual cash payment. A caller-supplied {@code id} is honoured, which
     * is what makes the Excel import idempotent (see {@link #saveAll}).
     */
    public PaymentDto save(PaymentDto payment) {
        try {
            String id = payment.getId() != null ? payment.getId() : UUID.randomUUID().toString();

            firestore.collection(COLLECTION).document(id).set(toDocument(payment)).get();

            payment.setId(id);
            return payment;

        } catch (InterruptedException e) {
            // Genuine interruption: preserve the flag for the thread owner.
            Thread.currentThread().interrupt();
            log.error("Error saving manual cash payment: {}", e.getMessage());
            throw new RuntimeException("Failed to save manual cash payment", e);
        } catch (ExecutionException e) {
            // NOT an interruption: never touch the interrupt flag here, or the
            // next blocking call on this thread fails instantly (BOR-81 B-3).
            log.error("Error saving manual cash payment: {}", e.getMessage());
            throw new RuntimeException("Failed to save manual cash payment", e);
        }
    }

    /**
     * Save many manual cash payments in Firestore batches (500-op limit), one
     * round trip per batch instead of one per row (BOR-81 finding B-5). Ids are
     * honoured as in {@link #save}; rows without one get a random id.
     *
     * @return the number of payments written
     */
    public int saveAll(List<PaymentDto> payments) {
        if (payments == null || payments.isEmpty()) {
            return 0;
        }
        int written = 0;
        for (int i = 0; i < payments.size(); i += FIRESTORE_BATCH_LIMIT) {
            List<PaymentDto> chunk = payments.subList(i, Math.min(i + FIRESTORE_BATCH_LIMIT, payments.size()));
            com.google.cloud.firestore.WriteBatch batch = firestore.batch();
            for (PaymentDto payment : chunk) {
                String id = payment.getId() != null ? payment.getId() : UUID.randomUUID().toString();
                payment.setId(id);
                batch.set(firestore.collection(COLLECTION).document(id), toDocument(payment));
            }
            FutureResults.await(batch.commit(), "batch-save manual cash payments");
            written += chunk.size();
        }
        return written;
    }

    private static Map<String, Object> toDocument(PaymentDto payment) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("customerId", payment.getCustomerId());
        doc.put("customerName", payment.getCustomerName() != null ? payment.getCustomerName() : "");
        doc.put("amount", payment.getAmount().doubleValue());
        doc.put("paymentDate", Timestamp.of(java.util.Date.from(
                payment.getPaymentDate().atStartOfDay(ZoneId.systemDefault()).toInstant())));
        doc.put("description", payment.getDescription() != null ? payment.getDescription() : "");
        doc.put("source", "manual-cash");
        if (payment.getUniqueCode() != null && !payment.getUniqueCode().isBlank()) {
            doc.put("uniqueCode", payment.getUniqueCode());
        }
        doc.put("createdAt", Timestamp.now());
        return doc;
    }

    /**
     * Delete a manual cash payment.
     */
    public void delete(String id) {
        try {
            firestore.collection(COLLECTION).document(id).delete().get();
            log.debug("Deleted manual cash payment: {}", id);
        } catch (InterruptedException e) {
            // Genuine interruption: preserve the flag for the thread owner.
            Thread.currentThread().interrupt();
            log.error("Error deleting manual cash payment {}: {}", id, e.getMessage());
            throw new RuntimeException("Failed to delete manual cash payment", e);
        } catch (ExecutionException e) {
            // NOT an interruption: never touch the interrupt flag here, or the
            // next blocking call on this thread fails instantly (BOR-81 B-3).
            log.error("Error deleting manual cash payment {}: {}", id, e.getMessage());
            throw new RuntimeException("Failed to delete manual cash payment", e);
        }
    }

    private PaymentDto documentToDto(QueryDocumentSnapshot document) {
        Timestamp paymentDate = document.getTimestamp("paymentDate");
        LocalDate date = paymentDate != null
                ? LocalDate.ofInstant(paymentDate.toDate().toInstant(), ZoneId.systemDefault())
                : null;

        Double amount = document.getDouble("amount");

        return PaymentDto.builder()
                .id(document.getId())
                .customerId(document.getString("customerId"))
                .customerName(document.getString("customerName"))
                .amount(amount != null ? BigDecimal.valueOf(amount) : BigDecimal.ZERO)
                .paymentDate(date)
                .description(document.getString("description"))
                .source("manual-cash")
                .build();
    }

    /**
     * Surface a Firestore read failure instead of masking it as "no data".
     * Cash payments feed customer debt totals; an outage must not read as zero.
     */
    private RuntimeException readFailure(String context, Exception e) {
        log.error("Error {}: {}", context, e.getMessage());
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        return new RuntimeException("Failed to " + context, e);
    }
}
