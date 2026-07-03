package ge.tastyerp.common.dto.payment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Authoritative debt overview: every customer's debt plus the headline totals,
 * computed server-side. Totals EXCLUDE customers in the shared exclude set, so
 * every device sees the same headline number.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebtOverviewDto {
    private List<CustomerDebtDto> customers;

    // Headline totals over NON-excluded customers only.
    private BigDecimal totalSales;
    private BigDecimal totalPayments;
    private BigDecimal totalCashPayments;
    private BigDecimal totalOutstanding;   // Σ(startingDebt + totalSales - totalPayments)
}
