package ge.tastyerp.payment.service.audit;

import ge.tastyerp.common.dto.audit.DailyLedgerRowDto;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Daily yield / write-off algorithm for the Audit Control inventory ledger
 * (BOR-74 Phase 2).
 *
 * <h3>Model — "possible write-off"</h3>
 * For a meat processor, bone/trim loss during processing is expressed as a
 * percentage <b>of the stock received that day</b> ("posib Write-off" = possible
 * write-off). The rate defaults to {@value #DEFAULT_WRITE_OFF_PERCENT_LITERAL}%
 * but is user-editable per category (persisted in config-service); the caller
 * passes the resolved rate in. This is the write-off that drives the running
 * inventory.
 *
 * <pre>
 *   base            = startingInventory + purchased          (available before sales)
 *   posibWriteOff   = purchased * rate                        (rate default 0.28)
 *   ending          = base - sold - posibWriteOff
 *   overage         = sold > base  ||  ending < 0
 * </pre>
 *
 * <ul>
 *   <li>{@code sold > base} means more was sold than was physically available —
 *       a documentation inconsistency, flagged.</li>
 *   <li>{@code ending < 0} means sales plus the 28% possible write-off exceeded
 *       the available stock — the day cannot absorb the loss, flagged.</li>
 * </ul>
 */
@Component
public class WriteOffCalculator {

    /** Default possible write-off: 28% of the stock received (purchased) that day. */
    public static final BigDecimal POSSIBLE_WRITE_OFF_RATE = new BigDecimal("0.28");

    /** Default rate as a whole percentage (used to seed the editable input). */
    public static final BigDecimal DEFAULT_WRITE_OFF_PERCENT = new BigDecimal("28");
    private static final String DEFAULT_WRITE_OFF_PERCENT_LITERAL = "28";

    private static final int SCALE = 3;

    /**
     * Compute one ledger day at the default 28% rate. Retained for callers/tests
     * that don't override the rate; delegates to the rate-aware overload.
     *
     * @param date      the day
     * @param starting  inventory carried in from the previous day (kg)
     * @param purchased stock received that day (kg)
     * @param sold      stock sold that day (kg)
     */
    public DailyLedgerRowDto computeDay(LocalDate date,
                                        BigDecimal starting,
                                        BigDecimal purchased,
                                        BigDecimal sold) {
        return computeDay(date, starting, purchased, sold, POSSIBLE_WRITE_OFF_RATE);
    }

    /**
     * Compute one ledger day at an explicit possible-write-off rate.
     *
     * @param rate write-off rate as a fraction of purchased kg (e.g. 0.28 for
     *             28%); {@code null} falls back to {@link #POSSIBLE_WRITE_OFF_RATE}
     */
    public DailyLedgerRowDto computeDay(LocalDate date,
                                        BigDecimal starting,
                                        BigDecimal purchased,
                                        BigDecimal sold,
                                        BigDecimal rate) {
        BigDecimal start = nz(starting);
        BigDecimal buy = nz(purchased);
        BigDecimal sell = nz(sold);
        BigDecimal r = rate != null ? rate : POSSIBLE_WRITE_OFF_RATE;

        BigDecimal base = start.add(buy);
        BigDecimal posibWriteOff = buy.multiply(r);

        BigDecimal ending = base.subtract(sell).subtract(posibWriteOff);

        boolean overage = sell.compareTo(base) > 0
                || ending.compareTo(BigDecimal.ZERO) < 0;

        BigDecimal writeOffPercent = base.compareTo(BigDecimal.ZERO) > 0
                ? posibWriteOff.divide(base, 6, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        return DailyLedgerRowDto.builder()
                .date(date)
                .startingInventoryKg(scale(start))
                .purchasedKg(scale(buy))
                .soldKg(scale(sell))
                .writeOffKg(scale(posibWriteOff))
                .endingInventoryKg(scale(ending))
                .writeOffPercent(writeOffPercent.setScale(2, RoundingMode.HALF_UP))
                .overage(overage)
                .build();
    }

    /**
     * Compute one ledger day for a passthrough (non-write-off) category such as
     * OTHER / Unclassified. Inventory simply carries forward:
     * {@code ending = starting + purchased - sold}, with no write-off ceiling and
     * no overage flag — these products must never affect the Beef/Pork math.
     */
    public DailyLedgerRowDto passthroughDay(LocalDate date,
                                            BigDecimal starting,
                                            BigDecimal purchased,
                                            BigDecimal sold) {
        BigDecimal start = nz(starting);
        BigDecimal buy = nz(purchased);
        BigDecimal sell = nz(sold);
        BigDecimal ending = start.add(buy).subtract(sell);

        return DailyLedgerRowDto.builder()
                .date(date)
                .startingInventoryKg(scale(start))
                .purchasedKg(scale(buy))
                .soldKg(scale(sell))
                .writeOffKg(scale(BigDecimal.ZERO))
                .endingInventoryKg(scale(ending))
                .writeOffPercent(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .overage(false)
                .build();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
