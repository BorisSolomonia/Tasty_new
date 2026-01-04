package ge.tastyerp.common.util;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Utility class for generating unique payment codes.
 *
 * The unique code format is: date|amountCents|customerId
 *
 * IMPORTANT: Balance was REMOVED from uniqueCode (2025-01-04) because:
 * - Bank statements from overlapping date ranges show different balances for the same transaction
 * - This caused duplicate payments when uploading overlapping Excel files
 * - Example: Same payment in Jan-Mar statement vs Feb-Apr statement had different balances
 *
 * This ensures exact matching for:
 * - Same date
 * - Same amount (in cents to avoid floating point issues)
 * - Same customer
 */
public final class UniqueCodeGenerator {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String DELIMITER = "|";

    private UniqueCodeGenerator() {
        // Utility class - no instantiation
    }

    /**
     * Build a unique code for payment deduplication.
     * NOTE: Balance is intentionally excluded to prevent duplicates from overlapping bank statements.
     *
     * @param date       Transaction date
     * @param amount     Payment amount
     * @param customerId Customer identifier (TIN)
     * @param balance    Account balance (IGNORED - kept for backward compatibility)
     * @return Unique code string
     */
    public static String buildUniqueCode(LocalDate date, BigDecimal amount, String customerId, BigDecimal balance) {
        // Balance intentionally excluded - causes duplicates with overlapping bank statements
        return String.join(DELIMITER,
                date.format(DATE_FORMAT),
                toCents(amount),
                normalizeId(customerId)
        );
    }

    /**
     * Build a unique code from string date (YYYY-MM-DD format).
     * NOTE: Balance is intentionally excluded to prevent duplicates from overlapping bank statements.
     */
    public static String buildUniqueCode(String dateStr, BigDecimal amount, String customerId, BigDecimal balance) {
        // Balance intentionally excluded - causes duplicates with overlapping bank statements
        return String.join(DELIMITER,
                dateStr,
                toCents(amount),
                normalizeId(customerId)
        );
    }

    /**
     * Convert amount to cents string (multiplied by 100, rounded).
     */
    private static String toCents(BigDecimal amount) {
        if (amount == null) {
            return "0";
        }
        return String.valueOf(amount.multiply(BigDecimal.valueOf(100))
                .setScale(0, java.math.RoundingMode.HALF_UP)
                .intValue());
    }

    /**
     * Normalize customer ID by trimming whitespace.
     */
    private static String normalizeId(String id) {
        return id == null ? "" : id.trim();
    }

    /**
     * Extract date from unique code.
     */
    public static String extractDate(String uniqueCode) {
        if (uniqueCode == null || !uniqueCode.contains(DELIMITER)) {
            return null;
        }
        return uniqueCode.split("\\|")[0];
    }

    /**
     * Extract customer ID from unique code.
     */
    public static String extractCustomerId(String uniqueCode) {
        if (uniqueCode == null || !uniqueCode.contains(DELIMITER)) {
            return null;
        }
        String[] parts = uniqueCode.split("\\|");
        return parts.length > 2 ? parts[2] : null;
    }
}
