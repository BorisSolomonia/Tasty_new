package ge.tastyerp.config.service;

import ge.tastyerp.common.dto.config.SystemSettingsDto;
import ge.tastyerp.common.exception.ResourceNotFoundException;
import ge.tastyerp.common.exception.ValidationException;
import ge.tastyerp.config.repository.SettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

/**
 * Service for managing system settings.
 *
 * ALL business logic for settings management is here.
 * Controllers only delegate to this service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsService {

    private final SettingsRepository settingsRepository;

    // Default values from environment variables
    @Value("${business.cutoff-date:2025-04-29}")
    private String defaultCutoffDate;

    @Value("${business.payment-cutoff-date:2025-04-29}")
    private String defaultPaymentCutoffDate;

    @Value("${business.batch-size:100}")
    private Integer defaultBatchSize;

    @Value("${business.max-date-range-months:12}")
    private Integer defaultMaxDateRangeMonths;

    // Allowed setting keys
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "cutoffDate",
            "paymentCutoffDate",
            "batchSize",
            "maxDateRangeMonths",
            "tbcBankApiEnabled",
            "bogBankApiEnabled",
            "bankSyncIntervalMinutes"
    );

    /**
     * Get all system settings.
     * If not found in Firebase, returns defaults from environment variables.
     */
    public SystemSettingsDto getAllSettings() {
        log.debug("Fetching all system settings");

        return settingsRepository.getSettings()
                .orElseGet(this::getDefaultSettings);
    }

    /**
     * Get a specific setting by key.
     */
    public Object getSetting(String key) {
        validateKey(key);

        SystemSettingsDto settings = getAllSettings();
        return getSettingValue(settings, key);
    }

    /**
     * Update a specific setting.
     */
    public SystemSettingsDto updateSetting(String key, Object value) {
        validateKey(key);
        validateValue(key, value);

        log.info("Updating setting: {} = {}", key, value);

        SystemSettingsDto current = getAllSettings();
        SystemSettingsDto updated = applySettingUpdate(current, key, value);

        settingsRepository.saveSettings(updated);

        return updated;
    }

    /**
     * Reset all settings to default values.
     */
    public SystemSettingsDto resetToDefaults() {
        log.info("Resetting all settings to defaults");

        SystemSettingsDto defaults = getDefaultSettings();
        settingsRepository.saveSettings(defaults);

        return defaults;
    }

    /**
     * Get the cutoff date for waybill filtering.
     */
    public String getCutoffDate() {
        return getAllSettings().getCutoffDate();
    }

    /**
     * Get the payment cutoff date.
     */
    public String getPaymentCutoffDate() {
        return getAllSettings().getPaymentCutoffDate();
    }

    /**
     * Get batch size for processing.
     */
    public int getBatchSize() {
        Integer batchSize = getAllSettings().getBatchSize();
        return batchSize != null ? batchSize : defaultBatchSize;
    }

    // Private helper methods

    private SystemSettingsDto getDefaultSettings() {
        return SystemSettingsDto.builder()
                .cutoffDate(defaultCutoffDate)
                .paymentCutoffDate(defaultPaymentCutoffDate)
                .batchSize(defaultBatchSize)
                .maxDateRangeMonths(defaultMaxDateRangeMonths)
                .tbcBankApiEnabled(false)
                .bogBankApiEnabled(false)
                .bankSyncIntervalMinutes(60)
                .build();
    }

    private void validateKey(String key) {
        if (!ALLOWED_KEYS.contains(key)) {
            throw new ValidationException("key", "Unknown setting key: " + key);
        }
    }

    private void validateValue(String key, Object value) {
        switch (key) {
            case "cutoffDate":
            case "paymentCutoffDate":
                if (value == null || !value.toString().matches("\\d{4}-\\d{2}-\\d{2}")) {
                    throw new ValidationException(key, "Date must be in YYYY-MM-DD format");
                }
                break;
            case "batchSize":
            case "maxDateRangeMonths":
            case "bankSyncIntervalMinutes":
                if (!(value instanceof Number) || ((Number) value).intValue() <= 0) {
                    throw new ValidationException(key, "Must be a positive integer");
                }
                break;
            case "tbcBankApiEnabled":
            case "bogBankApiEnabled":
                if (!(value instanceof Boolean)) {
                    throw new ValidationException(key, "Must be a boolean");
                }
                break;
        }
    }

    private Object getSettingValue(SystemSettingsDto settings, String key) {
        return switch (key) {
            case "cutoffDate" -> settings.getCutoffDate();
            case "paymentCutoffDate" -> settings.getPaymentCutoffDate();
            case "batchSize" -> settings.getBatchSize();
            case "maxDateRangeMonths" -> settings.getMaxDateRangeMonths();
            case "tbcBankApiEnabled" -> settings.getTbcBankApiEnabled();
            case "bogBankApiEnabled" -> settings.getBogBankApiEnabled();
            case "bankSyncIntervalMinutes" -> settings.getBankSyncIntervalMinutes();
            default -> throw new ResourceNotFoundException("Setting", key);
        };
    }

    private SystemSettingsDto applySettingUpdate(SystemSettingsDto current, String key, Object value) {
        return SystemSettingsDto.builder()
                .cutoffDate(key.equals("cutoffDate") ? value.toString() : current.getCutoffDate())
                .paymentCutoffDate(key.equals("paymentCutoffDate") ? value.toString() : current.getPaymentCutoffDate())
                .batchSize(key.equals("batchSize") ? ((Number) value).intValue() : current.getBatchSize())
                .maxDateRangeMonths(key.equals("maxDateRangeMonths") ? ((Number) value).intValue() : current.getMaxDateRangeMonths())
                .tbcBankApiEnabled(key.equals("tbcBankApiEnabled") ? (Boolean) value : current.getTbcBankApiEnabled())
                .bogBankApiEnabled(key.equals("bogBankApiEnabled") ? (Boolean) value : current.getBogBankApiEnabled())
                .bankSyncIntervalMinutes(key.equals("bankSyncIntervalMinutes") ? ((Number) value).intValue() : current.getBankSyncIntervalMinutes())
                .build();
    }
}
