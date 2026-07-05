package ge.tastyerp.payment.service.audit;

import ge.tastyerp.common.dto.audit.DailyLedgerRowDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the daily "possible write-off" algorithm (BOR-74 Phase 2).
 *
 * Model: posibWriteOff = 28% of the day's purchased kg;
 *        ending = starting + purchased - sold - posibWriteOff;
 *        overage = sold > base || ending < 0.
 */
class WriteOffCalculatorTest {

    private final WriteOffCalculator calc = new WriteOffCalculator();
    private final LocalDate day = LocalDate.of(2026, 6, 1);

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void writeOffIs28PercentOfPurchased() {
        // 100kg purchased, nothing sold -> 28kg possible write-off.
        DailyLedgerRowDto row = calc.computeDay(day, BigDecimal.ZERO, bd("100"), BigDecimal.ZERO);

        assertEquals(0, row.getWriteOffKg().compareTo(bd("28.000")), "write-off should be 28kg (28% of 100)");
        assertEquals(0, row.getWriteOffPercent().compareTo(bd("28.00")), "28kg of a 100kg base");
        assertEquals(0, row.getEndingInventoryKg().compareTo(bd("72.000")), "ending = 100 - 0 - 28");
        assertFalse(row.isOverage(), "sales fit -> no overage");
    }

    @Test
    void writeOffIsBasedOnPurchasedNotOpeningStock() {
        // 40kg opening + 60kg purchased: write-off is 28% of the 60kg purchased = 16.8kg,
        // NOT 28% of the 100kg base.
        DailyLedgerRowDto row = calc.computeDay(day, bd("40"), bd("60"), BigDecimal.ZERO);

        assertEquals(0, row.getWriteOffKg().compareTo(bd("16.800")), "28% of purchased only");
        assertEquals(0, row.getEndingInventoryKg().compareTo(bd("83.200")), "ending = 100 - 0 - 16.8");
        assertFalse(row.isOverage());
    }

    @Test
    void noPurchaseMeansNoWriteOff() {
        // Selling from opening stock with no purchases -> zero possible write-off.
        DailyLedgerRowDto row = calc.computeDay(day, bd("100"), BigDecimal.ZERO, bd("50"));

        assertEquals(0, row.getWriteOffKg().compareTo(bd("0.000")), "no purchases -> no write-off");
        assertEquals(0, row.getEndingInventoryKg().compareTo(bd("50.000")), "ending = 100 - 50 - 0");
        assertFalse(row.isOverage());
    }

    @Test
    void flagsOverageWhenSalesPlusWriteOffExceedStock() {
        // base=100, sold 80, write-off 28 -> ending = -8 -> overage.
        DailyLedgerRowDto row = calc.computeDay(day, BigDecimal.ZERO, bd("100"), bd("80"));

        assertTrue(row.isOverage(), "sales + 28% write-off exceed available stock -> overage");
        assertEquals(0, row.getWriteOffKg().compareTo(bd("28.000")), "write-off stays 28% of purchased");
        assertEquals(0, row.getEndingInventoryKg().compareTo(bd("-8.000")), "ending goes negative");
    }

    @Test
    void flagsOverageWhenSoldMoreThanAvailable() {
        // Sold more than physically available -> documentation inconsistency.
        DailyLedgerRowDto row = calc.computeDay(day, BigDecimal.ZERO, bd("50"), bd("60"));

        assertTrue(row.isOverage(), "selling more than available must be flagged");
        assertEquals(0, row.getWriteOffKg().compareTo(bd("14.000")), "write-off = 28% of 50kg purchased");
    }

    @Test
    void appliesAnExplicitEditableRate() {
        // User sets 30% -> write-off = 30kg of 100kg purchased; ending = 100 - 0 - 30.
        DailyLedgerRowDto row = calc.computeDay(day, BigDecimal.ZERO, bd("100"), BigDecimal.ZERO, bd("0.30"));

        assertEquals(0, row.getWriteOffKg().compareTo(bd("30.000")), "30% of 100kg purchased");
        assertEquals(0, row.getEndingInventoryKg().compareTo(bd("70.000")), "ending = 100 - 0 - 30");
        assertEquals(0, row.getWriteOffPercent().compareTo(bd("30.00")), "30kg of the 100kg base");
    }

    @Test
    void zeroRateMeansNoWriteOff() {
        // User sets 0% -> no write-off at all, inventory carries straight through.
        DailyLedgerRowDto row = calc.computeDay(day, BigDecimal.ZERO, bd("100"), bd("40"), BigDecimal.ZERO);

        assertEquals(0, row.getWriteOffKg().compareTo(bd("0.000")), "0% -> no write-off");
        assertEquals(0, row.getEndingInventoryKg().compareTo(bd("60.000")), "ending = 100 - 40 - 0");
        assertFalse(row.isOverage());
    }

    @Test
    void nullRateFallsBackToDefault28Percent() {
        DailyLedgerRowDto row = calc.computeDay(day, BigDecimal.ZERO, bd("100"), BigDecimal.ZERO, null);

        assertEquals(0, row.getWriteOffKg().compareTo(bd("28.000")), "null rate defaults to 28%");
    }
}
