package ge.tastyerp.payment.service;

import com.google.cloud.firestore.*;
import ge.tastyerp.common.dto.payment.PaymentDto;
import ge.tastyerp.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 * Service for deduplicating payments in Firebase.
 *
 * Updated 2025-01-05: UniqueCode now includes balance again to distinguish
 * same-day, same-amount payments from the same customer.
 *
 * Current uniqueCode format: date|amountCents|customerId|balanceCents
 *
 * This service groups by the FULL uniqueCode (including balance) to find
 * exact duplicates only. Payments with different balances are considered unique.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeduplicationService {

    private final Firestore firestore;
    private static final String COLLECTION_PAYMENTS = "payments";

    /**
     * Result of deduplication operation.
     */
    public record DeduplicationResult(
            int totalPayments,
            int duplicateGroups,
            int paymentsDeleted,
            int paymentsUpdated,
            BigDecimal amountRecovered,
            List<String> deletedPaymentIds,
            List<DuplicateGroup> duplicateDetails
    ) {}

    public record DuplicateGroup(
            String newUniqueCode,
            String customerId,
            LocalDate date,
            BigDecimal amount,
            int count,
            String keptPaymentId,
            List<String> deletedPaymentIds
    ) {}

    /**
     * Analyze payments for duplicates (dry run - no changes made).
     */
    public DeduplicationResult analyzeDuplicates() {
        log.info("🔍 Analyzing payments for duplicates (dry run)...");
        return processDeduplication(true);
    }

    /**
     * Remove duplicate payments from Firebase.
     * Keeps the oldest payment in each duplicate group.
     */
    public DeduplicationResult removeDuplicates() {
        log.info("🧹 Removing duplicate payments...");
        return processDeduplication(false);
    }

    private DeduplicationResult processDeduplication(boolean dryRun) {
        try {
            // Fetch all payments
            QuerySnapshot snapshot = firestore.collection(COLLECTION_PAYMENTS).get().get();
            List<QueryDocumentSnapshot> docs = snapshot.getDocuments();

            log.info("📊 Found {} total payments in Firebase", docs.size());

            // Group payments by NEW uniqueCode (without balance)
            Map<String, List<PaymentRecord>> groups = new HashMap<>();

            for (QueryDocumentSnapshot doc : docs) {
                PaymentRecord record = documentToRecord(doc);
                if (record == null) continue;

                String code = buildUniqueCode(record);
                groups.computeIfAbsent(code, k -> new ArrayList<>()).add(record);
            }

            // Find duplicate groups (more than 1 payment per code)
            List<DuplicateGroup> duplicateDetails = new ArrayList<>();
            List<String> allDeletedIds = new ArrayList<>();
            BigDecimal totalAmountRecovered = BigDecimal.ZERO;
            int paymentsUpdated = 0;

            for (Map.Entry<String, List<PaymentRecord>> entry : groups.entrySet()) {
                List<PaymentRecord> payments = entry.getValue();

                if (payments.size() > 1) {
                    // Sort by uploadedAt (oldest first)
                    payments.sort(Comparator.comparing(p -> p.uploadedAt != null ? p.uploadedAt : "9999"));

                    PaymentRecord keep = payments.get(0);
                    List<String> deleteIds = new ArrayList<>();
                    BigDecimal duplicateAmount = BigDecimal.ZERO;

                    for (int i = 1; i < payments.size(); i++) {
                        PaymentRecord dup = payments.get(i);
                        deleteIds.add(dup.id);
                        duplicateAmount = duplicateAmount.add(dup.amount);
                        allDeletedIds.add(dup.id);
                    }

                    totalAmountRecovered = totalAmountRecovered.add(duplicateAmount);

                    duplicateDetails.add(new DuplicateGroup(
                            entry.getKey(),
                            keep.customerId,
                            keep.date,
                            keep.amount,
                            payments.size(),
                            keep.id,
                            deleteIds
                    ));

                    if (!dryRun) {
                        // Delete duplicates
                        for (String id : deleteIds) {
                            firestore.collection(COLLECTION_PAYMENTS).document(id).delete().get();
                            log.info("🗑️ Deleted duplicate payment: {}", id);
                        }

                        // Update kept payment's uniqueCode to new format
                        if (!entry.getKey().equals(keep.uniqueCode)) {
                            firestore.collection(COLLECTION_PAYMENTS).document(keep.id)
                                    .update("uniqueCode", entry.getKey()).get();
                            paymentsUpdated++;
                            log.info("✏️ Updated uniqueCode for payment {}: {} -> {}",
                                    keep.id, keep.uniqueCode, entry.getKey());
                        }
                    }
                }
            }

            // Log summary
            log.info("📋 Deduplication {} complete:", dryRun ? "analysis" : "operation");
            log.info("   Total payments: {}", docs.size());
            log.info("   Duplicate groups found: {}", duplicateDetails.size());
            log.info("   Payments to delete: {}", allDeletedIds.size());
            log.info("   Amount to recover: ₾{}", totalAmountRecovered);

            return new DeduplicationResult(
                    docs.size(),
                    duplicateDetails.size(),
                    allDeletedIds.size(),
                    paymentsUpdated,
                    totalAmountRecovered,
                    allDeletedIds,
                    duplicateDetails
            );

        } catch (InterruptedException | ExecutionException e) {
            log.error("❌ Error during deduplication: {}", e.getMessage(), e);
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to deduplicate payments", e);
        }
    }

    /**
     * Build uniqueCode format (with balance).
     */
    private String buildUniqueCode(PaymentRecord record) {
        int amountCents = record.amount.multiply(BigDecimal.valueOf(100))
                .setScale(0, java.math.RoundingMode.HALF_UP)
                .intValue();
        int balanceCents = record.balance.multiply(BigDecimal.valueOf(100))
                .setScale(0, java.math.RoundingMode.HALF_UP)
                .intValue();
        return String.format("%s|%d|%s|%d", record.date.toString(), amountCents, record.customerId.trim(), balanceCents);
    }

    private record PaymentRecord(
            String id,
            String uniqueCode,
            String customerId,
            LocalDate date,
            BigDecimal amount,
            BigDecimal balance,
            String uploadedAt
    ) {}

    private PaymentRecord documentToRecord(QueryDocumentSnapshot doc) {
        try {
            Object dateObj = doc.get("paymentDate");
            LocalDate date = null;
            if (dateObj instanceof com.google.cloud.Timestamp ts) {
                date = ts.toDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            }

            Double amount = doc.getDouble("amount");
            String customerId = doc.getString("supplierName");
            Double balance = doc.getDouble("balance");

            if (date == null || amount == null || customerId == null) {
                return null;
            }

            Object uploadedObj = doc.get("uploadedAt");
            String uploadedAt = null;
            if (uploadedObj instanceof com.google.cloud.Timestamp ts) {
                uploadedAt = ts.toString();
            }

            return new PaymentRecord(
                    doc.getId(),
                    doc.getString("uniqueCode"),
                    customerId,
                    date,
                    BigDecimal.valueOf(amount),
                    balance != null ? BigDecimal.valueOf(balance) : BigDecimal.ZERO,
                    uploadedAt
            );
        } catch (Exception e) {
            log.warn("Could not parse payment document {}: {}", doc.getId(), e.getMessage());
            return null;
        }
    }
}
