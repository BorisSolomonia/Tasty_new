package ge.tastyerp.common.dto.audit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Inventory-on-hand ledger for one product parent category over a date range.
 *
 * Child products are aggregated into the parent node (BOR-74 acceptance
 * criterion: "aggregates child product inventory into parent nodes").
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryLedgerDto {

    private String parentCategory;          // BEEF / PORK / OTHER
    private List<String> childProducts;     // child names that fed this category

    private BigDecimal openingStockKg;      // stock at the start of the range
    private BigDecimal totalPurchasedKg;
    private BigDecimal totalSoldKg;
    private BigDecimal totalWriteOffKg;
    private BigDecimal endingInventoryKg;   // inventory on hand at range end

    /**
     * The possible-write-off rate applied to this category, as a percentage of
     * purchased kg (e.g. 28). Editable per category for BEEF/PORK; null/zero for
     * passthrough categories (FAT/OTHER) which never apply a write-off.
     */
    private BigDecimal writeOffRatePercent;

    /** Number of days where the required write-off breached the 30% ceiling. */
    private int overageDays;

    private List<DailyLedgerRowDto> dailyRows;
}
