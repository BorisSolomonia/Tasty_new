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
 * <h3>Model</h3>
 * For a meat processor, bone/trim loss during processing is a legally allowed
 * write-off capped at 30% of the inventory processed that day. The algorithm
 * <b>maximizes</b> the compliant write-off (targets the 29%–30% band) and flags
 * days where the legal allowance cannot be fully absorbed — an "overage".
 *
 * <pre>
 *   base               = startingInventory + purchased        (available before sales)
 *   ceiling            = base * 0.30                          (legal maximum)
 *   remainingAfterSale = base - sold
 *   writeOff           = clamp(ceiling, 0, max(0, remainingAfterSale))
 *   ending             = base - sold - writeOff
 *   overage            = sold > base  ||  ceiling > remainingAfterSale
 * </pre>
 *
 * <ul>
 *   <li>When there is room, the full 30% is taken (≥ 29% target satisfied).</li>
 *   <li>{@code sold > base} means more was sold than was physically available —
 *       a documentation inconsistency, flagged.</li>
 *   <li>{@code ceiling > remainingAfterSale} means the day's sales consumed so
 *       much stock that the full legal write-off could not be applied — the loss
 *       exceeds the compliant range, flagged.</li>
 * </ul>
 */
@Component
public class WriteOffCalculator {

    /** Legal ceiling: write-off may not exceed 30% of the day's processing base. */
    public static final BigDecimal CEILING_RATE = new BigDecimal("0.30");
    /** Lower bound of the target band the algorithm tries to reach. */
    public static final BigDecimal TARGET_RATE = new BigDecimal("0.29");

    private static final int SCALE = 3;

    /**
     * Compute one ledger day.
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
        BigDecimal start = nz(starting);
        BigDecimal buy = nz(purchased);
        BigDecimal sell = nz(sold);

        BigDecimal base = start.add(buy);
        BigDecimal ceiling = base.multiply(CEILING_RATE);
        BigDecimal remainingAfterSale = base.subtract(sell);

        BigDecimal writeOff = ceiling;
        BigDecimal room = remainingAfterSale.max(BigDecimal.ZERO);
        if (writeOff.compareTo(room) > 0) {
            writeOff = room;
        }

        boolean overage = sell.compareTo(base) > 0
                || ceiling.compareTo(remainingAfterSale) > 0;

        BigDecimal ending = base.subtract(sell).subtract(writeOff);

        BigDecimal writeOffPercent = base.compareTo(BigDecimal.ZERO) > 0
                ? writeOff.divide(base, 6, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        return DailyLedgerRowDto.builder()
                .date(date)
                .startingInventoryKg(scale(start))
                .purchasedKg(scale(buy))
                .soldKg(scale(sell))
                .writeOffKg(scale(writeOff))
                .endingInventoryKg(scale(ending))
                .writeOffPercent(writeOffPercent.setScale(2, RoundingMode.HALF_UP))
                .overage(overage)
                .build();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
