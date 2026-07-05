package ge.tastyerp.common.dto.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * User-editable dual-ledger inputs for one product category (BOR-76).
 *
 * All fields are nullable OVERRIDES layered on top of factual documented data:
 * <ul>
 *   <li>Documented kg is always factual (summed from RS.ge purchase/sale
 *       movements) and is never stored here.</li>
 *   <li>{@code docPurchasePrice} / {@code docSalePrice} default to the
 *       movement-derived average GEL/kg but may be overridden.</li>
 *   <li>{@code realPurchasePrice} / {@code realSalePrice} are user-entered (do
 *       not appear on waybills); default to the documented price.</li>
 *   <li>{@code realPurchaseKg} / {@code realSaleKg} are user-entered (real
 *       received/sold amounts, already cut); default to the documented kg.</li>
 * </ul>
 * Stored in Firestore {@code config/dual_ledger_inputs}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryLedgerInputDto {
    private String category;              // BEEF / PORK / FAT / OTHER

    private BigDecimal docPurchasePrice;  // GEL/kg, override of the waybill average
    private BigDecimal realPurchasePrice; // GEL/kg, user-entered
    private BigDecimal realPurchaseKg;    // kg, user-entered real received

    private BigDecimal docSalePrice;      // GEL/kg, override of the waybill average
    private BigDecimal realSalePrice;     // GEL/kg, user-entered
    private BigDecimal realSaleKg;        // kg, user-entered real sold
}
