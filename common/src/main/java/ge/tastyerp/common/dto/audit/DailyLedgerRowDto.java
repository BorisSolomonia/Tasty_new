package ge.tastyerp.common.dto.audit;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One day of the inventory ledger for a single product category.
 *
 * Formula (BOR-74 Phase 2):
 *   endingInventory = startingInventory + purchasedKg - soldKg - writeOffKg
 *
 * The write-off amount is produced by the daily yield algorithm which targets
 * 29%–30% of the day's processed inventory and flags overages.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyLedgerRowDto {

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate date;

    private BigDecimal startingInventoryKg;
    private BigDecimal purchasedKg;
    private BigDecimal soldKg;
    private BigDecimal writeOffKg;
    private BigDecimal endingInventoryKg;

    /** Write-off as a percentage of the day's available (start + purchases) inventory. */
    private BigDecimal writeOffPercent;

    /** True when the computed/required write-off exceeded the 30% legal ceiling. */
    private boolean overage;
}
