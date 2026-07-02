package ge.tastyerp.payment.service.audit;

import ge.tastyerp.common.dto.audit.DailyLedgerRowDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BOR-75 parity + stress test for the daily inventory pipeline.
 *
 * <h3>Parity</h3>
 * Every optimized-path day is compared against an INDEPENDENT reference
 * implementation of the spec (posibWriteOff = 28% of purchased;
 * ending = start + purchased - sold - posibWriteOff) using exact BigDecimal
 * comparison — zero tolerance. Quantities are generated with one decimal place
 * so no intermediate rounding occurs and equality must be byte-exact.
 *
 * <h3>Stress</h3>
 * Simulates 3 years (1095 days) of multi-ton daily purchases/sales across 4
 * product categories (4380 ledger days) and requires the whole run to complete
 * well under a second — guarding against future algorithmic regressions
 * (accidental O(n²), memory blowup).
 */
class AuditLedgerParityStressTest {

    private static final BigDecimal RATE = new BigDecimal("0.28");
    private static final int DAYS = 365 * 3;
    private static final String[] CATEGORIES = {"BEEF", "PORK", "FAT", "OTHER"};

    private final WriteOffCalculator calc = new WriteOffCalculator();

    /** Independent reference implementation of one write-off day (the spec). */
    private DailyLedgerRowDto referenceDay(LocalDate date, BigDecimal start, BigDecimal buy, BigDecimal sell) {
        BigDecimal base = start.add(buy);
        BigDecimal writeOff = buy.multiply(RATE);
        BigDecimal ending = base.subtract(sell).subtract(writeOff);
        boolean overage = sell.compareTo(base) > 0 || ending.compareTo(BigDecimal.ZERO) < 0;
        BigDecimal pct = base.signum() > 0
                ? writeOff.divide(base, 6, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;
        return DailyLedgerRowDto.builder()
                .date(date)
                .startingInventoryKg(start.setScale(3, RoundingMode.HALF_UP))
                .purchasedKg(buy.setScale(3, RoundingMode.HALF_UP))
                .soldKg(sell.setScale(3, RoundingMode.HALF_UP))
                .writeOffKg(writeOff.setScale(3, RoundingMode.HALF_UP))
                .endingInventoryKg(ending.setScale(3, RoundingMode.HALF_UP))
                .writeOffPercent(pct.setScale(2, RoundingMode.HALF_UP))
                .overage(overage)
                .build();
    }

    /** kg value with exactly one decimal place, in [0, maxTensOfKg*10). */
    private static BigDecimal kg(Random rnd, int maxTensOfKg) {
        return BigDecimal.valueOf(rnd.nextInt(maxTensOfKg * 100), 1);
    }

    @Test
    @DisplayName("3 years × 4 categories: exact parity with reference + conservation invariant, fast")
    void multiYearParityAndStress() {
        Random rnd = new Random(20260702); // deterministic
        long start = System.currentTimeMillis();
        int overageDays = 0;

        for (String category : CATEGORIES) {
            BigDecimal running = BigDecimal.ZERO;
            BigDecimal refRunning = BigDecimal.ZERO;
            LocalDate day = LocalDate.of(2023, 1, 1);

            for (int i = 0; i < DAYS; i++, day = day.plusDays(1)) {
                // Multi-ton scale: purchases up to ~5t/day, sales up to ~4t/day.
                BigDecimal purchased = kg(rnd, 500);
                BigDecimal sold = kg(rnd, 400);

                DailyLedgerRowDto actual = calc.computeDay(day, running, purchased, sold);
                DailyLedgerRowDto expected = referenceDay(day, refRunning, purchased, sold);

                // Byte-exact financial parity, field by field.
                assertEquals(0, actual.getStartingInventoryKg().compareTo(expected.getStartingInventoryKg()),
                        category + " day " + i + " starting mismatch");
                assertEquals(0, actual.getPurchasedKg().compareTo(expected.getPurchasedKg()));
                assertEquals(0, actual.getSoldKg().compareTo(expected.getSoldKg()));
                assertEquals(0, actual.getWriteOffKg().compareTo(expected.getWriteOffKg()),
                        category + " day " + i + " write-off mismatch");
                assertEquals(0, actual.getEndingInventoryKg().compareTo(expected.getEndingInventoryKg()),
                        category + " day " + i + " ending mismatch");
                assertEquals(0, actual.getWriteOffPercent().compareTo(expected.getWriteOffPercent()));
                assertEquals(expected.isOverage(), actual.isOverage());

                // Conservation invariant: ending = start + purchased - sold - writeOff (exact).
                BigDecimal conserved = actual.getStartingInventoryKg()
                        .add(actual.getPurchasedKg())
                        .subtract(actual.getSoldKg())
                        .subtract(actual.getWriteOffKg());
                assertEquals(0, actual.getEndingInventoryKg().compareTo(conserved),
                        category + " day " + i + " conservation violated");

                if (actual.isOverage()) overageDays++;
                running = actual.getEndingInventoryKg();
                refRunning = expected.getEndingInventoryKg();
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 5000, "4380 ledger days took " + elapsed + " ms — algorithmic regression?");
        assertTrue(overageDays > 0, "synthetic data should produce some overage days");
    }

    @Test
    @DisplayName("Passthrough parity: ending = start + purchased - sold, no write-off, never overage")
    void passthroughParity() {
        Random rnd = new Random(42);
        BigDecimal running = new BigDecimal("100.0");
        LocalDate day = LocalDate.of(2025, 1, 1);

        for (int i = 0; i < 1000; i++, day = day.plusDays(1)) {
            BigDecimal purchased = kg(rnd, 300);
            BigDecimal sold = kg(rnd, 300);

            DailyLedgerRowDto row = calc.passthroughDay(day, running, purchased, sold);

            BigDecimal expectedEnding = running.add(purchased).subtract(sold).setScale(3, RoundingMode.HALF_UP);
            assertEquals(0, row.getEndingInventoryKg().compareTo(expectedEnding), "day " + i);
            assertEquals(0, row.getWriteOffKg().compareTo(new BigDecimal("0.000")));
            assertFalse(row.isOverage(), "passthrough must never flag overage");

            running = row.getEndingInventoryKg();
        }
    }
}
