package ge.tastyerp.config.repository;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import ge.tastyerp.common.dto.config.InitialDebtDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 * Repository for initial debts stored in Firebase.
 *
 * Data access only - NO business logic here.
 *
 * Structure in Firebase:
 * config/initial_debts: {
 *   "202200778": { "name": "შპს წისქვილი ჯგუფი", "debt": 6740, "date": "2025-04-29" },
 *   "53001051654": { "name": "ელგუჯა ციბაძე", "debt": 141, "date": "2025-04-29" },
 *   ...
 * }
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class InitialDebtRepository {

    private static final String COLLECTION = "config";
    private static final String DOCUMENT = "initial_debts";

    private final Firestore firestore;

    /**
     * Get all initial debts.
     */
    public List<InitialDebtDto> findAll() {
        try {
            DocumentSnapshot snapshot = getDocument();

            if (!snapshot.exists()) {
                return Collections.emptyList();
            }

            List<InitialDebtDto> debts = new ArrayList<>();
            Map<String, Object> data = snapshot.getData();

            if (data != null) {
                for (Map.Entry<String, Object> entry : data.entrySet()) {
                    String customerId = entry.getKey();

                    // Skip metadata fields
                    if (customerId.equals("updatedAt") || customerId.equals("createdAt")) {
                        continue;
                    }

                    if (entry.getValue() instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> debtData = (Map<String, Object>) entry.getValue();
                        debts.add(mapToDto(customerId, debtData));
                    }
                }
            }

            return debts;

        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching all initial debts: {}", e.getMessage());
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        }
    }

    /**
     * Find initial debt by customer ID.
     */
    public Optional<InitialDebtDto> findByCustomerId(String customerId) {
        try {
            DocumentSnapshot snapshot = getDocument();

            if (!snapshot.exists()) {
                return Optional.empty();
            }

            Object debtData = snapshot.get(customerId);
            if (debtData == null) {
                return Optional.empty();
            }

            if (debtData instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) debtData;
                return Optional.of(mapToDto(customerId, data));
            }

            return Optional.empty();

        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching initial debt for {}: {}", customerId, e.getMessage());
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Save initial debt (create or update).
     */
    public InitialDebtDto save(InitialDebtDto debt) {
        try {
            DocumentReference docRef = firestore.collection(COLLECTION).document(DOCUMENT);

            Map<String, Object> debtData = new HashMap<>();
            debtData.put("name", debt.getName());
            debtData.put("debt", debt.getDebt().doubleValue());
            debtData.put("date", debt.getDate());

            // Update the specific customer field
            Map<String, Object> updates = new HashMap<>();
            updates.put(debt.getCustomerId(), debtData);
            updates.put("updatedAt", com.google.cloud.Timestamp.now());

            docRef.update(updates).get();

            log.debug("Saved initial debt for customer: {}", debt.getCustomerId());
            return debt;

        } catch (ExecutionException e) {
            // Document might not exist, create it
            if (isNotFoundError(e)) {
                return createDocument(debt);
            }
            log.error("Error saving initial debt: {}", e.getMessage());
            throw new RuntimeException("Failed to save initial debt", e);
        } catch (InterruptedException e) {
            log.error("Error saving initial debt: {}", e.getMessage());
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to save initial debt", e);
        }
    }

    /**
     * Delete initial debt by customer ID.
     */
    public void delete(String customerId) {
        try {
            DocumentReference docRef = firestore.collection(COLLECTION).document(DOCUMENT);

            Map<String, Object> updates = new HashMap<>();
            updates.put(customerId, com.google.cloud.firestore.FieldValue.delete());
            updates.put("updatedAt", com.google.cloud.Timestamp.now());

            docRef.update(updates).get();
            log.debug("Deleted initial debt for customer: {}", customerId);

        } catch (InterruptedException | ExecutionException e) {
            log.error("Error deleting initial debt: {}", e.getMessage());
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to delete initial debt", e);
        }
    }

    // Private helper methods

    private DocumentSnapshot getDocument() throws InterruptedException, ExecutionException {
        return firestore.collection(COLLECTION).document(DOCUMENT).get().get();
    }

    /**
     * Check if the exception indicates a NOT_FOUND error from Firestore.
     * Uses proper gRPC status checking instead of fragile string matching.
     */
    private boolean isNotFoundError(ExecutionException e) {
        Throwable cause = e.getCause();

        // Check for NotFoundException directly
        if (cause != null && cause.getClass().getName().contains("NotFoundException")) {
            return true;
        }

        // Also check for StatusRuntimeException
        if (cause instanceof StatusRuntimeException statusException) {
            return statusException.getStatus().getCode() == Status.Code.NOT_FOUND;
        }

        // Check deeper in the exception chain
        Throwable rootCause = cause;
        while (rootCause != null && rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
            if (rootCause instanceof StatusRuntimeException statusException) {
                return statusException.getStatus().getCode() == Status.Code.NOT_FOUND;
            }
        }

        return false;
    }

    private InitialDebtDto createDocument(InitialDebtDto firstDebt) {
        try {
            Map<String, Object> debtData = new HashMap<>();
            debtData.put("name", firstDebt.getName());
            debtData.put("debt", firstDebt.getDebt().doubleValue());
            debtData.put("date", firstDebt.getDate());

            Map<String, Object> document = new HashMap<>();
            document.put(firstDebt.getCustomerId(), debtData);
            document.put("createdAt", com.google.cloud.Timestamp.now());
            document.put("updatedAt", com.google.cloud.Timestamp.now());

            firestore.collection(COLLECTION).document(DOCUMENT).set(document).get();
            log.debug("Created initial_debts document with first entry: {}", firstDebt.getCustomerId());

            return firstDebt;

        } catch (InterruptedException | ExecutionException e) {
            log.error("Error creating initial_debts document: {}", e.getMessage());
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to create initial_debts document", e);
        }
    }

    private InitialDebtDto mapToDto(String customerId, Map<String, Object> data) {
        BigDecimal debt = BigDecimal.ZERO;
        Object debtValue = data.get("debt");
        if (debtValue instanceof Number) {
            debt = BigDecimal.valueOf(((Number) debtValue).doubleValue());
        }

        return InitialDebtDto.builder()
                .customerId(customerId)
                .name((String) data.get("name"))
                .debt(debt)
                .date((String) data.get("date"))
                .build();
    }
}
