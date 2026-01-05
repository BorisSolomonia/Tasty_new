package ge.tastyerp.common.util;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Utility class for generating unique payment codes.
 *
 * The unique code format is: date|amountCents|customerId|balanceCents
 *
 * Balance is INCLUDED because:
 * - Same customer can make multiple payments of the same amount on the same day
 * - The balance after each transaction is unique and distinguishes these payments
 * - Without balance, legitimate separate payments would be marked as duplicates
 *
 * Example: Customer pays 1410 twice on 2025-05-13:
 * - Payment 1: balance after = 2322.46 → uniqueCode includes |232246
 * - Payment 2: balance after = 6773.46 → uniqueCode includes |677346
 * - Both payments are correctly saved as unique
 *
 * For overlapping bank statements with the same payment:
 * - The account balance after a transaction should be identical regardless of statement
 * - If duplicates occur, use DeduplicationService to clean them up
 */
public final class UniqueCodeGenerator {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String DELIMITER = "|";

    private UniqueCodeGenerator() {
        // Utility class - no instantiation
    }

    /**
     * Build a unique code for payment deduplication.
     * Includes balance to distinguish same-day, same-amount payments.
     *
     * @param date       Transaction date
     * @param amount     Payment amount
     * @param customerId Customer identifier (TIN)
     * @param balance    Account balance after transaction
     * @return Unique code string
     */
    public static String buildUniqueCode(LocalDate date, BigDecimal amount, String customerId, BigDecimal balance) {
        return String.join(DELIMITER,
                date.format(DATE_FORMAT),
                toCents(amount),
                normalizeId(customerId),
                toCents(balance)
        );
    }

    /**
     * Build a unique code from string date (YYYY-MM-DD format).
     * Includes balance to distinguish same-day, same-amount payments.
     */
    public static String buildUniqueCode(String dateStr, BigDecimal amount, String customerId, BigDecimal balance) {
        return String.join(DELIMITER,
                dateStr,
                toCents(amount),
                normalizeId(customerId),
                toCents(balance)
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
