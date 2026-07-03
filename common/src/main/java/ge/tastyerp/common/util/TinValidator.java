package ge.tastyerp.common.util;

import java.util.regex.Pattern;

/**
 * Utility class for Georgian TIN (Tax Identification Number) validation.
 *
 * Georgian TIN formats:
 * - 9 digits for companies
 * - 11 digits for individuals
 */
public final class TinValidator {

    private static final Pattern TIN_9_PATTERN = Pattern.compile("^\\d{9}$");
    private static final Pattern TIN_11_PATTERN = Pattern.compile("^\\d{11}$");

    private TinValidator() {
        // Utility class - no instantiation
    }

    /**
     * Validate Georgian TIN format.
     *
     * @param tin TIN to validate
     * @return true if valid 9 or 11 digit TIN
     */
    public static boolean isValid(String tin) {
        if (tin == null || tin.isBlank()) {
            return false;
        }

        String cleaned = normalize(tin);
        return TIN_9_PATTERN.matcher(cleaned).matches() ||
               TIN_11_PATTERN.matcher(cleaned).matches();
    }

    /**
     * Normalize TIN by removing whitespace and common separators.
     *
     * NOTE: this is used as a STORAGE key in several services (initial debts,
     * payments) — do not change its behaviour (e.g. to strip leading zeros) or
     * existing documents become unreachable. For identity matching that must
     * survive RS.ge's leading-zero stripping, use {@link #canonicalId}.
     */
    public static String normalize(String tin) {
        if (tin == null) {
            return "";
        }
        return tin.replaceAll("[\\s\\-_.]+", "").trim();
    }

    /**
     * Canonical identity key for a customer, robust to RS.ge stripping leading
     * zeros from individual IDs ("01008057492" from Excel vs "1008057492" from
     * RS.ge must map to the SAME customer).
     *
     * Digits only, then leading zeros removed (keeping at least one digit).
     * Falls back to {@link #normalize} for non-numeric identifiers (e.g. a name
     * used as an id) so those remain distinct.
     *
     * This is an in-memory matching key only — it never rewrites stored data.
     */
    public static String canonicalId(String id) {
        if (id == null) {
            return "";
        }
        String digits = id.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            return normalize(id);
        }
        return digits.replaceFirst("^0+(\\d)", "$1");
    }

    /**
     * Check if a string looks like a TIN (might be used instead of name).
     * Used to detect when BUYER_NAME contains TIN instead of actual name.
     */
    public static boolean looksLikeTin(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String cleaned = normalize(value);
        return TIN_9_PATTERN.matcher(cleaned).matches() ||
               TIN_11_PATTERN.matcher(cleaned).matches();
    }

    /**
     * Get TIN type description.
     */
    public static String getType(String tin) {
        if (!isValid(tin)) {
            return "Invalid";
        }
        String cleaned = normalize(tin);
        return cleaned.length() == 9 ? "Company" : "Individual";
    }
}
