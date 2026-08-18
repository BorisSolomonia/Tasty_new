package ge.tastyerp.common.util;

import ge.tastyerp.common.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The period a half-typed date input produces (0002-, 0020-, 0202-…) must be
 * refused before it costs anything; a real audit period must pass.
 */
class DateRangeGuardTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 18);
    private static final LocalDate FLOOR = DateRangeGuard.EARLIEST_SUPPORTED;

    @Test
    void halfTypedYearsAreRefusedWithTheBoundNamed() {
        for (String start : new String[]{"0002-01-01", "0020-01-01", "0202-01-01", "1999-12-31"}) {
            ValidationException e = assertThrows(ValidationException.class,
                    () -> DateRangeGuard.require(LocalDate.parse(start), TODAY, FLOOR, TODAY), start);
            assertTrue(e.getMessage().contains("2015-01-01"), e.getMessage());
        }
    }

    @Test
    void realPeriodsPass() {
        assertDoesNotThrow(() -> DateRangeGuard.require(LocalDate.of(2023, 1, 1), TODAY, FLOOR, TODAY));
        assertDoesNotThrow(() -> DateRangeGuard.require(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15), FLOOR, TODAY));
        assertDoesNotThrow(() -> DateRangeGuard.require(TODAY, TODAY.plusDays(1), FLOOR, TODAY), "tomorrow as an end date is tolerated");
        assertDoesNotThrow(() -> DateRangeGuard.require(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), FLOOR, TODAY), "month-end presets are ordinary");
        assertDoesNotThrow(() -> DateRangeGuard.require(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), FLOOR, TODAY), "a whole year is ordinary");
    }

    @Test
    void reversedFutureAndOverlongPeriodsAreRefused() {
        assertThrows(ValidationException.class, () -> DateRangeGuard.require(TODAY, TODAY.minusDays(1), FLOOR, TODAY));
        assertThrows(ValidationException.class, () -> DateRangeGuard.require(TODAY, TODAY.plusDays(500), FLOOR, TODAY));
        assertThrows(ValidationException.class, () -> DateRangeGuard.require(LocalDate.of(2015, 1, 1), LocalDate.of(2027, 1, 2), LocalDate.of(2000, 1, 1), LocalDate.of(2027, 1, 2)));
        assertThrows(ValidationException.class, () -> DateRangeGuard.require(null, TODAY, FLOOR, TODAY));
    }
}
