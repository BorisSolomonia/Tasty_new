package ge.tastyerp.payment.service.audit;

import ge.tastyerp.common.dto.audit.DailyLedgerRowDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the daily write-off / yield algorithm (BOR-74 Phase 2).
 */
class WriteOffCalculatorTest {

    private final WriteOffCalculator calc = new WriteOffCalculator();
    private final LocalDate day = LocalDate.of(2026, 6, 1);

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void maximizesWriteOffToThirtyPercentWhenRoomExists() {
        // 100kg purchased, nothing sold -> full 30% legal write-off, no overage.
        DailyLedgerRowDto row = calc.computeDay(day, BigDecimal.ZERO, bd("100"), BigDecimal.ZERO);

        assertEquals(0, row.getWriteOffKg().compareTo(bd("30.000")), "write-off should be 30kg");
        assertEquals(0, row.getWriteOffPercent().compareTo(bd("30.00")), "should hit the 30% ceiling");
        assertEquals(0, row.getEndingInventoryKg().compareTo(bd("70.000")), "ending = 100 - 0 - 30");
        assertFalse(row.isOverage(), "within ceiling -> no overage");
    }

    @Test
    void targetBandIsAtLeast29Percent() {
        DailyLedgerRowDto row = calc.computeDay(day, BigDecimal.ZERO, bd("100"), BigDecimal.ZERO);
        assertTrue(row.getWriteOffPercent().compareTo(bd("29")) >= 0, "must target at least 29%");
        assertTrue(row.getWriteOffPercent().compareTo(bd("30")) <= 0, "must not exceed 30%");
    }

    @Test
    void flagsOverageWhenSalesConsumeTooMuchStock() {
        // 100kg available, 80kg sold -> only 20kg room < 30kg ceiling -> overage.
        DailyLedgerRowDto row = calc.computeDay(day, BigDecimal.ZERO, bd("100"), bd("80"));

        assertTrue(row.isOverage(), "30% ceiling cannot be absorbed -> overage flagged");
        assertEquals(0, row.getWriteOffKg().compareTo(bd("20.000")), "write-off limited to remaining 20kg");
        assertEquals(0, row.getEndingInventoryKg().compareTo(bd("0.000")), "ending clamped to 0");
    }

    @Test
    void flagsOverageWhenSoldMoreThanAvailable() {
        // Sold more than physically available -> documentation inconsistency.
        DailyLedgerRowDto row = calc.computeDay(day, BigDecimal.ZERO, bd("50"), bd("60"));
        assertTrue(row.isOverage(), "selling more than available must be flagged");
        assertEquals(0, row.getWriteOffKg().compareTo(bd("0.000")), "no room to write off");
    }

    @Test
    void carriesOpeningStockIntoTheBase() {
        // 40kg opening + 60kg purchased = 100kg base -> 30% = 30kg.
        DailyLedgerRowDto row = calc.computeDay(day, bd("40"), bd("60"), BigDecimal.ZERO);
        assertEquals(0, row.getWriteOffKg().compareTo(bd("30.000")), "base includes opening stock");
        assertEquals(0, row.getEndingInventoryKg().compareTo(bd("70.000")));
    }
}
