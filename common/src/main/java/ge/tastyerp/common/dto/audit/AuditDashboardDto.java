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
 * Aggregate payload backing the {@code /audit-control} page (BOR-74).
 *
 * Bundles everything the dashboard renders for the selected date range so the
 * frontend can fetch it in a single request.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditDashboardDto {

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;

    /** Optional product parent-category filter (null = all). */
    private String productFilter;

    private RealTotalsDto realTotals;

    private List<InventoryLedgerDto> inventoryLedgers;

    private List<ReconciliationRowDto> reconciliation;
    private BigDecimal realDebtTotal;
    private BigDecimal exceptionDebtTotal;

    private TargetedExpenseDto targetedExpense;

    private List<AuditExceptionDto> exceptions;
}
