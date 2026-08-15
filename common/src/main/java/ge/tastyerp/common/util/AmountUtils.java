package ge.tastyerp.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for amount parsing and calculations.
 * Handles Georgian/international number formats.
 */
public final class AmountUtils {

    private static final Pattern NUMERIC_PATTERN = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private AmountUtils() {
        // Utility class - no instantiation
    }

    /**
     * Parse amount from various formats.
     *
     * Handles:
     * - Georgian number formats
     * - Various separators (spaces, commas)
     * - Negative numbers
     *
     * @param value Value to parse
     * @return BigDecimal amount or ZERO if parsing fails
     */
    public static BigDecimal parseAmount(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }

        if (value instanceof Number) {
            return BigDecimal.valueOf(((Number) value).doubleValue())
                    .setScale(2, RoundingMode.HALF_UP);
        }

        String stringValue = value.toString()
                // Remove various whitespace characters
                .replaceAll("[\\s\\u00A0\\u202F\\u2009]+", "")
                .trim();

        // Handle comma-decimal formats:
        // - Comma but no dot: the comma is the decimal separator ("10,50").
        // - Both present: whichever separator appears LAST is the decimal one and
        //   the other is a thousands grouper — "1,234.56" and "1.234,56" are both
        //   1234.56. Deciding by mere presence (the previous rule) turned the
        //   European form "1.234,56" into 1.23, a silent 1000× understatement
        //   (BOR-81 finding B-14).
        // - Dot but no comma: plain decimal; Arabic thousands separators dropped.
        int lastComma = stringValue.lastIndexOf(',');
        int lastDot = stringValue.lastIndexOf('.');
        if (lastComma >= 0 && lastDot < 0) {
            stringValue = stringValue.replace(",", ".");
        } else if (lastComma >= 0 && lastDot >= 0) {
            if (lastComma > lastDot) {
                // European: dots group thousands, comma is the decimal point.
                stringValue = stringValue.replace(".", "").replace(",", ".");
            } else {
                stringValue = stringValue.replaceAll("[,\\u066C]", "");
            }
        } else {
            stringValue = stringValue.replaceAll("[,\\u066C]", "");
        }

        Matcher matcher = NUMERIC_PATTERN.matcher(stringValue);
        if (!matcher.find()) {
            return BigDecimal.ZERO;
        }

        try {
            return new BigDecimal(matcher.group())
                    .setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * Convert amount to cents (integer value).
     */
    public static int toCents(BigDecimal amount) {
        if (amount == null) {
            return 0;
        }
        return amount.multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();
    }

    /**
     * Convert cents to amount.
     */
    public static BigDecimal fromCents(int cents) {
        return BigDecimal.valueOf(cents)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    /**
     * Round amount to 2 decimal places.
     */
    public static BigDecimal round(BigDecimal amount) {
        if (amount == null) {
            return BigDecimal.ZERO;
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Check if amount is positive (greater than zero).
     */
    public static boolean isPositive(BigDecimal amount) {
        return amount != null && amount.compareTo(BigDecimal.ZERO) > 0;
    }
}
