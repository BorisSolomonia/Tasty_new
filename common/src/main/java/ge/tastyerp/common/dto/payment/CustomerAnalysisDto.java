package ge.tastyerp.common.dto.payment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Customer analysis DTO containing debt calculation and payment/sales details.
 *
 * Business Logic:
 * currentDebt = startingDebt + totalSales - totalPayments
 *
 * All sales and payments are filtered to be AFTER cutoff date (2025-04-29).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerAnalysisDto {

    /**
     * Customer ID (TIN).
     */
    private String customerId;

    /**
     * Customer name (resolved from multiple sources).
     */
    private String customerName;

    /**
     * Total sales amount from waybills after cutoff date.
     */
    private BigDecimal totalSales;

    /**
     * Total payments from all sources after cutoff date.
     */
    private BigDecimal totalPayments;

    /**
     * Total manual cash payments after cutoff date.
     */
    private BigDecimal totalCashPayments;

    /**
     * Current debt balance.
     * Formula: startingDebt + totalSales - totalPayments
     */
    private BigDecimal currentDebt;

    /**
     * Starting debt as of cutoff date (from initial_debts).
     */
    private BigDecimal startingDebt;

    /**
     * Starting debt date (usually cutoff date).
     */
    private LocalDate startingDebtDate;

    /**
     * Number of waybills for this customer after cutoff.
     */
    private Integer waybillCount;

    /**
     * Number of payments for this customer after cutoff.
     */
    private Integer paymentCount;

    /**
     * List of waybills after cutoff date.
     */
    private List<WaybillSummaryDto> waybills;

    /**
     * List of payments after cutoff date.
     */
    private List<PaymentSummaryDto> payments;

    /**
     * List of manual cash payments after cutoff date.
     */
    private List<PaymentSummaryDto> cashPayments;
}
