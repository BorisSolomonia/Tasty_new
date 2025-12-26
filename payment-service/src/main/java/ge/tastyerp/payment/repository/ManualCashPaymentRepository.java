package ge.tastyerp.payment.repository;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import ge.tastyerp.common.dto.payment.PaymentDto;
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
            log.error("Error fetching manual cash payments after {}: {}", date, e.getMessage());
            Thread.currentThread().interrupt();
            return Collections.emptyList();
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
            log.error("Error fetching manual cash payments for customer {} after {}: {}", customerId, date, e.getMessage());
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        }
    }

    /**
     * Save a manual cash payment.
     */
    public PaymentDto save(PaymentDto payment) {
        try {
            String id = payment.getId() != null ? payment.getId() : UUID.randomUUID().toString();

            firestore.collection(COLLECTION).document(id).set(Map.of(
                    "customerId", payment.getCustomerId(),
                    "customerName", payment.getCustomerName() != null ? payment.getCustomerName() : "",
                    "amount", payment.getAmount().doubleValue(),
                    "paymentDate", Timestamp.of(java.util.Date.from(
                            payment.getPaymentDate().atStartOfDay(ZoneId.systemDefault()).toInstant())),
                    "description", payment.getDescription() != null ? payment.getDescription() : "",
                    "source", "manual-cash",
                    "createdAt", Timestamp.now()
            )).get();

            payment.setId(id);
            return payment;

        } catch (InterruptedException | ExecutionException e) {
            log.error("Error saving manual cash payment: {}", e.getMessage());
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to save manual cash payment", e);
        }
    }

    /**
     * Delete a manual cash payment.
     */
    public void delete(String id) {
        try {
            firestore.collection(COLLECTION).document(id).delete().get();
            log.debug("Deleted manual cash payment: {}", id);
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error deleting manual cash payment {}: {}", id, e.getMessage());
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to delete manual cash payment", e);
        }
    }

    private PaymentDto documentToDto(QueryDocumentSnapshot document) {
        Timestamp paymentDate = document.getTimestamp("paymentDate");
        LocalDate date = paymentDate != null
                ? LocalDate.ofInstant(paymentDate.toDate().toInstant(), ZoneId.systemDefault())
                : null;

        return PaymentDto.builder()
                .id(document.getId())
                .customerId(document.getString("customerId"))
                .customerName(document.getString("customerName"))
                .amount(BigDecimal.valueOf(document.getDouble("amount")))
                .paymentDate(date)
                .description(document.getString("description"))
                .source("manual-cash")
                .build();
    }
}
