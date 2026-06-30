package ge.tastyerp.common.dto.audit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * "Real Totals" summary cards (BOR-74 Phase 3).
 *
 * Sales/purchases are summed strictly over {@code is_real_entity} counterparties
 * for the selected period; non-real (exception-only) entities are excluded.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RealTotalsDto {

    private BigDecimal realTotalSales;
    private BigDecimal realTotalPurchases;

    private BigDecimal excludedSales;       // sales attributed to non-real entities
    private BigDecimal excludedPurchases;   // purchases attributed to non-real entities

    private int realEntityCount;
    private int excludedEntityCount;
}
