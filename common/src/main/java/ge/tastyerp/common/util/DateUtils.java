package ge.tastyerp.common.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for date parsing and validation.
 * Handles multiple date formats including Excel serial numbers.
 */
public final class DateUtils {

    private static final DateTimeFormatter ISO_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Pattern MDY_PATTERN = Pattern.compile("^(\\d{1,2})/(\\d{1,2})/(\\d{4})$");
    private static final Pattern YMD_PATTERN = Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$");
    // ISO datetime pattern: YYYY-MM-DDTHH:mm:ss (with optional milliseconds)
    private static final Pattern ISO_DATETIME_PATTERN = Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})T.*$");

    // Excel serial date offset (days from 1899-12-30 to 1970-01-01)
    // Using 25568 to match legacy behavior (includes 1900 leap year bug)
    private static final int EXCEL_EPOCH_OFFSET = 25568;
    private static final int SECONDS_PER_DAY = 86400;

    private DateUtils() {
        // Utility class - no instantiation
    }

    /**
     * Parse date from various formats to LocalDate.
     *
     * Supports:
     * - Excel serial numbers
     * - MM/DD/YYYY strings
     * - YYYY-MM-DD strings
     * - ISO date strings
     *
     * @param dateValue Date value in any supported format
     * @return LocalDate or null if parsing fails
     */
    public static LocalDate parseDate(Object dateValue) {
        if (dateValue == null) {
            return null;
        }

        // Handle numeric (Excel serial date)
        if (dateValue instanceof Number) {
            return parseExcelSerialDate(((Number) dateValue).doubleValue());
        }

        String dateStr = dateValue.toString().trim();
        if (dateStr.isEmpty()) {
            return null;
        }

        // Try Excel serial as string
        try {
            double serial = Double.parseDouble(dateStr);
            return parseExcelSerialDate(serial);
        } catch (NumberFormatException ignored) {
            // Not a number, try string formats
        }

        // Try MM/DD/YYYY
        Matcher mdyMatcher = MDY_PATTERN.matcher(dateStr);
        if (mdyMatcher.matches()) {
            int month = Integer.parseInt(mdyMatcher.group(1));
            int day = Integer.parseInt(mdyMatcher.group(2));
            int year = Integer.parseInt(mdyMatcher.group(3));
            return LocalDate.of(year, month, day);
        }

        // Try YYYY-MM-DD
        Matcher ymdMatcher = YMD_PATTERN.matcher(dateStr);
        if (ymdMatcher.matches()) {
            int year = Integer.parseInt(ymdMatcher.group(1));
            int month = Integer.parseInt(ymdMatcher.group(2));
            int day = Integer.parseInt(ymdMatcher.group(3));
            return LocalDate.of(year, month, day);
        }

        // Try ISO datetime format YYYY-MM-DDTHH:mm:ss (RS.ge format)
        Matcher isoDatetimeMatcher = ISO_DATETIME_PATTERN.matcher(dateStr);
        if (isoDatetimeMatcher.matches()) {
            int year = Integer.parseInt(isoDatetimeMatcher.group(1));
            int month = Integer.parseInt(isoDatetimeMatcher.group(2));
            int day = Integer.parseInt(isoDatetimeMatcher.group(3));
            return LocalDate.of(year, month, day);
        }

        // Try ISO format
        try {
            return LocalDate.parse(dateStr, ISO_FORMAT);
        } catch (DateTimeParseException ignored) {
            // Continue to next format
        }

        // Try generic parsing
        try {
            return LocalDate.parse(dateStr);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /**
     * Parse Excel serial date number to LocalDate.
     * Valid Excel serial dates are between 1 (January 1, 1900) and ~65000 (year 2078).
     */
    public static LocalDate parseExcelSerialDate(double serialDate) {
        // Validate reasonable range for Excel serial dates
        // 1 = January 1, 1900; 50000 = ~year 2037; values outside this are likely errors
        if (serialDate < 1 || serialDate > 60000) {
            return null;
        }
        // Convert Excel serial to epoch milliseconds
        long epochMs = (long) ((serialDate - EXCEL_EPOCH_OFFSET) * SECONDS_PER_DAY * 1000);
        return LocalDate.ofEpochDay(epochMs / (SECONDS_PER_DAY * 1000L));
    }

    /**
     * Format LocalDate to YYYY-MM-DD string.
     */
    public static String formatDate(LocalDate date) {
        return date == null ? null : date.format(ISO_FORMAT);
    }

    /**
     * Check if date is after cutoff date (exclusive).
     *
     * @param date       Date to check
     * @param cutoffDate Cutoff date string (YYYY-MM-DD)
     * @return true if date > cutoffDate
     */
    public static boolean isAfterCutoff(LocalDate date, String cutoffDate) {
        if (date == null || cutoffDate == null) {
            return false;
        }
        LocalDate cutoff = LocalDate.parse(cutoffDate, ISO_FORMAT);
        return date.isAfter(cutoff);
    }

    /**
     * Check if date string is after cutoff date.
     */
    public static boolean isAfterCutoff(String dateStr, String cutoffDate) {
        if (dateStr == null || cutoffDate == null) {
            return false;
        }
        // Simple string comparison works for YYYY-MM-DD format
        return dateStr.compareTo(cutoffDate) > 0;
    }
}
