package ge.tastyerp.common.dto.audit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * One category's complete documented-vs-physical lifecycle for the unified
 * Audit Control dashboard (BOR-79): a Purchases window, a Sales window and an
 * On-Hand Inventory footer, fully computed server-side.
 *
 * <h3>Purchase window</h3>
 * <pre>
 *   netDocPurchaseKg = purchaseDocKg × (1 − writeOffPercent/100)
 *   netDocKgPrice    = purchaseDocKg × purchaseDocPrice / netDocPurchaseKg
 *   debtDoc          = purchaseDocKg × purchaseDocPrice
 *   debtReal         = purchaseRealKg × purchaseRealPrice
 *   vatDifference    = (debtReal − debtDoc) / 1.18 × 0.18
 * </pre>
 *
 * <h3>Sales window</h3>
 * {@code salesRealKg} strictly excludes customers marked unreal or formal in
 * the reconciliation sheet. {@code salesRealTotal = realProductSales +
 * formalCommission} — the two components are reported separately so the UI can
 * segregate real product revenue from commission revenue.
 *
 * <h3>Footer</h3>
 * <pre>
 *   onHandDocKg = startingInventoryKg + netDocPurchaseKg − salesDocKg
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnifiedCategoryCardDto {

    private String category;

    // ==================== Purchase window ====================
    private BigDecimal purchaseDocKg;
    /** Average documented price for the period (or the user's override). */
    private BigDecimal purchaseDocPrice;
    /** Editable posib write-off % of purchased kg (default 28 BEEF/PORK, else 0). */
    private BigDecimal writeOffPercent;
    private BigDecimal netDocPurchaseKg;
    private BigDecimal netDocKgPrice;
    /** Physical purchase quantity/price; default to documented until overridden. */
    private BigDecimal purchaseRealKg;
    private BigDecimal purchaseRealPrice;
    /** Owed to suppliers on paper: purchaseDocKg × purchaseDocPrice. */
    private BigDecimal debtDoc;
    /** Really owed to suppliers: purchaseRealKg × purchaseRealPrice. */
    private BigDecimal debtReal;
    /** VAT hidden in the purchase gap: (debtReal − debtDoc) × 18/118. */
    private BigDecimal vatDifference;

    // ==================== Sales window ====================
    private BigDecimal salesDocKg;
    private BigDecimal salesDocPrice;
    private BigDecimal salesDocTotal;
    /** Documented kg sold to unreal (exception) customers — excluded from real. */
    private BigDecimal unrealSalesKg;
    /** Documented kg sold to formal (commission-only) customers — excluded from real. */
    private BigDecimal formalSalesKg;
    /** salesDocKg − unrealSalesKg − formalSalesKg. */
    private BigDecimal salesRealKg;
    private BigDecimal salesRealPrice;
    /** salesRealKg × salesRealPrice — real product revenue only. */
    private BigDecimal realProductSales;
    /** Σ formal customer kg (this category) × their commission per kg. */
    private BigDecimal formalCommission;
    /** realProductSales + formalCommission. */
    private BigDecimal salesRealTotal;

    // ==================== On-hand footer ====================
    /** No historical physical-stock source yet — documented limitation, 0. */
    private BigDecimal startingInventoryKg;
    /** startingInventoryKg + netDocPurchaseKg − salesDocKg. */
    private BigDecimal onHandDocKg;
}
