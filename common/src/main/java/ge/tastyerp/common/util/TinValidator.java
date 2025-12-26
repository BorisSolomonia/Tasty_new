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
     */
    public static String normalize(String tin) {
        if (tin == null) {
            return "";
        }
        return tin.replaceAll("[\\s\\-_.]+", "").trim();
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
