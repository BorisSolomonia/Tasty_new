package ge.tastyerp.common.dto.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * System configuration settings.
 * Stored in Firebase config/system_settings document.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemSettingsDto {

    private String cutoffDate;              // YYYY-MM-DD format
    private String paymentCutoffDate;       // YYYY-MM-DD format
    private Integer batchSize;
    private Integer maxDateRangeMonths;

    // Future: Banking API settings
    private Boolean tbcBankApiEnabled;
    private Boolean bogBankApiEnabled;
    private Integer bankSyncIntervalMinutes;
}
