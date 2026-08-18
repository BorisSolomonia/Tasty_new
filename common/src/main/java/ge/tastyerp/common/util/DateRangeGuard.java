package ge.tastyerp.common.util;

import ge.tastyerp.common.exception.ValidationException;

import java.time.LocalDate;

/**
 * The one rule for "is this period sane enough to fetch for" (BOR-92 v6.1).
 *
 * <p>Why it exists: a native date input emits a value on every keystroke, so
 * typing the year 2023 sends 0002-…, 0020-…, 0202-… first. Each of those
 * reached RS.ge and started a 3-day-chunk sweep from year 202 to today —
 * a quarter of a million SOAP calls per request — evicting the chunk cache
 * everyone else depends on. The client that typed the date had long moved on;
 * the sweeps kept running. So the guard sits at the cost: every RS.ge fetch,
 * every audit read, refuses a period nobody could mean, with a 400 that names
 * the bound, before a single upstream call is made.</p>
 */
public final class DateRangeGuard {

    /** No business data exists before this; anything earlier is a typing artefact. */
    public static final LocalDate EARLIEST_SUPPORTED = LocalDate.of(2015, 1, 1);
    /** Longest period a single request may sweep. */
    public static final int MAX_SPAN_YEARS = 12;
    /**
     * How far past today a period may end. Month-end presets ("this month" →
     * 2026-08-31) and "next year" filters are ordinary; a future end costs
     * nothing at RS.ge (there is nothing there). Only an absurd future is refused.
     */
    public static final int MAX_DAYS_AHEAD = 400;

    private DateRangeGuard() {}

    /**
     * @throws ValidationException when the period starts before
     *         {@link #EARLIEST_SUPPORTED}, ends more than {@link #MAX_DAYS_AHEAD} days
     *         after today, ends before it starts, or spans more than {@link #MAX_SPAN_YEARS}
     */
    public static void require(LocalDate start, LocalDate end) {
        require(start, end, EARLIEST_SUPPORTED, LocalDate.now());
    }

    /** Test seam: explicit floor and "today". */
    public static void require(LocalDate start, LocalDate end, LocalDate earliest, LocalDate today) {
        if (start == null || end == null) {
            throw new ValidationException("startDate", "Both startDate and endDate are required");
        }
        if (start.isBefore(earliest)) {
            throw new ValidationException("startDate",
                    "startDate " + start + " is before the earliest supported date " + earliest
                            + " — nothing exists there; if you are typing a year, finish typing it");
        }
        if (end.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            throw new ValidationException("endDate", "endDate " + end + " is more than a year in the future");
        }
        if (end.isBefore(start)) {
            throw new ValidationException("endDate", "endDate " + end + " is before startDate " + start);
        }
        if (start.plusYears(MAX_SPAN_YEARS).isBefore(end)) {
            throw new ValidationException("endDate",
                    "The period " + start + " → " + end + " spans more than " + MAX_SPAN_YEARS + " years");
        }
    }
}
