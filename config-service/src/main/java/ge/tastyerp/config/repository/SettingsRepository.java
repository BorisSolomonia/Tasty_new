package ge.tastyerp.config.repository;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import ge.tastyerp.common.dto.config.SystemSettingsDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/**
 * Repository for system settings stored in Firebase.
 *
 * Data access only - NO business logic here.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class SettingsRepository {

    private static final String COLLECTION = "config";
    private static final String DOCUMENT = "system_settings";
    private static final String PRODUCT_SALES_CUSTOMERS_DOC = "product_sales_customers";

    private final Firestore firestore;

    /**
     * Get system settings from Firebase.
     */
    public Optional<SystemSettingsDto> getSettings() {
        try {
            DocumentReference docRef = firestore.collection(COLLECTION).document(DOCUMENT);
            DocumentSnapshot snapshot = docRef.get().get();

            if (!snapshot.exists()) {
                log.debug("System settings document not found");
                return Optional.empty();
            }

            SystemSettingsDto settings = SystemSettingsDto.builder()
                    .cutoffDate(snapshot.getString("cutoffDate"))
                    .paymentCutoffDate(snapshot.getString("paymentCutoffDate"))
                    .batchSize(getIntegerSafe(snapshot, "batchSize"))
                    .maxDateRangeMonths(getIntegerSafe(snapshot, "maxDateRangeMonths"))
                    .tbcBankApiEnabled(getBooleanSafe(snapshot, "tbcBankApiEnabled"))
                    .bogBankApiEnabled(getBooleanSafe(snapshot, "bogBankApiEnabled"))
                    .bankSyncIntervalMinutes(getIntegerSafe(snapshot, "bankSyncIntervalMinutes"))
                    .build();

            return Optional.of(settings);

        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching system settings: {}", e.getMessage());
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Save system settings to Firebase.
     */
    public void saveSettings(SystemSettingsDto settings) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("cutoffDate", settings.getCutoffDate());
            data.put("paymentCutoffDate", settings.getPaymentCutoffDate());
            data.put("batchSize", settings.getBatchSize());
            data.put("maxDateRangeMonths", settings.getMaxDateRangeMonths());
            data.put("tbcBankApiEnabled", settings.getTbcBankApiEnabled());
            data.put("bogBankApiEnabled", settings.getBogBankApiEnabled());
            data.put("bankSyncIntervalMinutes", settings.getBankSyncIntervalMinutes());
            data.put("updatedAt", com.google.cloud.Timestamp.now());

            firestore.collection(COLLECTION).document(DOCUMENT).set(data).get();
            log.debug("System settings saved successfully");

        } catch (InterruptedException | ExecutionException e) {
            log.error("Error saving system settings: {}", e.getMessage());
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to save system settings", e);
        }
    }

    /**
     * Get product sales customers list from Firebase.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getProductSalesCustomers() {
        try {
            DocumentReference docRef = firestore.collection(COLLECTION).document(PRODUCT_SALES_CUSTOMERS_DOC);
            DocumentSnapshot snapshot = docRef.get().get();

            if (!snapshot.exists()) {
                log.debug("Product sales customers document not found");
                return Collections.emptyList();
            }

            List<Map<String, Object>> customers = (List<Map<String, Object>>) snapshot.get("customers");
            return customers != null ? customers : Collections.emptyList();

        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching product sales customers: {}", e.getMessage());
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        }
    }

    /**
     * Save product sales customers list to Firebase.
     */
    public void saveProductSalesCustomers(List<Map<String, Object>> customers) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("customers", customers);
            data.put("updatedAt", com.google.cloud.Timestamp.now());

            firestore.collection(COLLECTION).document(PRODUCT_SALES_CUSTOMERS_DOC).set(data).get();
            log.debug("Product sales customers saved successfully ({} entries)", customers.size());

        } catch (InterruptedException | ExecutionException e) {
            log.error("Error saving product sales customers: {}", e.getMessage());
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to save product sales customers", e);
        }
    }

    // Helper methods for safe type conversion

    private Integer getIntegerSafe(DocumentSnapshot snapshot, String field) {
        Long value = snapshot.getLong(field);
        return value != null ? value.intValue() : null;
    }

    private Boolean getBooleanSafe(DocumentSnapshot snapshot, String field) {
        return snapshot.getBoolean(field);
    }
}
