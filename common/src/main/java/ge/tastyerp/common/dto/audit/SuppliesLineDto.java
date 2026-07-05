package ge.tastyerp.common.dto.audit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * One purchased supplies product (car maintenance, spare parts …) aggregated over
 * the range (BOR-76 follow-up). Supplies are never sold, so only a purchase spend
 * and its deductible input VAT are tracked.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SuppliesLineDto {
    private String productName;
    private BigDecimal quantityKg;  // may be 0 for non-kg items
    private BigDecimal amount;      // total spend (VAT-inclusive gross)
    private BigDecimal inputVat;    // deductible input VAT (per the product's rate)
}
