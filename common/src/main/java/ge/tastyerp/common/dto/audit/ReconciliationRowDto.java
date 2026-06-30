package ge.tastyerp.common.dto.audit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * One customer's reconciliation row in the Audit Control debt table.
 *
 * Separates a "real" receivable (owed by an actual business partner) from a
 * "documentation exception" debt (owed by an exception-only entity), per
 * BOR-74's requirement to split real debt from documentation exceptions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReconciliationRowDto {

    private String customerId;
    private String customerName;
    private boolean realEntity;

    private BigDecimal totalSales;
    private BigDecimal totalPayments;
    private BigDecimal currentDebt;

    /** currentDebt when realEntity == true, otherwise zero. */
    private BigDecimal realDebt;
    /** currentDebt when realEntity == false, otherwise zero. */
    private BigDecimal exceptionDebt;

    /** True when an auditor has manually marked this customer's balance as settled. */
    private boolean manuallyMarkedPaid;
}
