package ge.tastyerp.common.dto.audit;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The full dual-ledger / shadow-cash-flow payload for a date range (BOR-76).
 * Assembled read-only from RS.ge documented movements + editable config; the
 * existing /payments and /waybills data are never modified.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DualLedgerDto {

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;
    private String productFilter;

    /**
     * BOR-79 — one unified card per category: purchase window, sales window and
     * on-hand footer, fully computed server-side for the accordion dashboard.
     */
    private List<UnifiedCategoryCardDto> categoryCards;

    /** Part 1 — per-category purchase cash shortage (gap = docTotal − realTotal). */
    private List<CategoryCashGapDto> purchaseShortages;
    /** Part 2 — per-category sales cash surplus (gap = realTotal − docTotal). */
    private List<CategoryCashGapDto> saleSurpluses;
    /** Part 3 — per formal-sales customer commission AR. */
    private List<FormalCommissionDto> formalCommissions;
    /** Part 4 — per-category VAT position. */
    private List<CategoryVatDto> vat;
    /** Purchase-only supplies (car maintenance, spare parts) — own section. */
    private List<SuppliesLineDto> supplies;

    // Grand totals
    private BigDecimal totalPurchaseShortage; // Σ gap (negative = net shortage)
    private BigDecimal totalSaleSurplus;      // Σ gap (positive = net surplus)
    private BigDecimal totalFormalCommission; // Σ commissionAr
    private BigDecimal totalVatPayable;       // Σ vatPayable incl. supplies input VAT
    private BigDecimal totalSuppliesSpend;    // Σ supplies amount
    private BigDecimal totalSuppliesInputVat; // Σ supplies input VAT (deductible)
}
