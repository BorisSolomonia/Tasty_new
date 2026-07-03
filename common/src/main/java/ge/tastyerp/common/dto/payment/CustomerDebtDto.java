package ge.tastyerp.common.dto.payment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * One customer's authoritative debt, computed server-side (single source of truth).
 *
 * All amounts are BigDecimal, pre-rounded to 2dp. Customers are keyed by
 * {@code ge.tastyerp.common.util.TinValidator#canonicalId} so RS.ge's
 * leading-zero-stripped IDs and Excel/initial-debt IDs collapse into one row.
 *
 * The frontend DISPLAYS these values and never recomputes them.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerDebtDto {
    private String customerId;          // canonical id
    private String customerName;
    private BigDecimal startingDebt;
    private BigDecimal totalSales;
    private BigDecimal totalPayments;   // all sources (bank + cash)
    private BigDecimal totalCashPayments;
    private BigDecimal currentDebt;     // startingDebt + totalSales - totalPayments
    private Integer waybillCount;
    private Integer paymentCount;
    private boolean excluded;           // excluded from the headline totals
}
