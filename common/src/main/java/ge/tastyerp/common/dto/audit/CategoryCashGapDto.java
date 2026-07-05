package ge.tastyerp.common.dto.audit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Documented-vs-real cash gap for one product category (BOR-76 Parts 1 &amp; 2).
 *
 * Reused for both directions:
 * <ul>
 *   <li><b>Purchase shortage</b>: {@code gap = docTotal − realTotal} (negative
 *       when real paid exceeds paper — a cash shortage).</li>
 *   <li><b>Sales surplus</b>: {@code gap = realTotal − docTotal} (positive when
 *       real received exceeds paper — a cash surplus).</li>
 * </ul>
 * where {@code docTotal = docKg·docPrice} and {@code realTotal = realKg·realPrice}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryCashGapDto {
    private String category;

    private BigDecimal docKg;
    private BigDecimal docPrice;   // GEL/kg
    private BigDecimal docTotal;   // docKg · docPrice

    private BigDecimal realKg;
    private BigDecimal realPrice;  // GEL/kg
    private BigDecimal realTotal;  // realKg · realPrice

    /** Signed cash gap (see class javadoc for the per-direction sign convention). */
    private BigDecimal gap;
}
