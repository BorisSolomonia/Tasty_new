package ge.tastyerp.common.dto.payment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Summary of all payments for a specific customer.
 * Implements SUMIFS-style aggregation logic.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerPaymentSummary {

    private String customerId;
    private String customerName;

    // Starting debt (from config/initial_debts)
    private BigDecimal startingDebt;
    private String startingDebtDate;

    // Total waybills (sales)
    private BigDecimal totalSales;
    private int waybillCount;

    // Total payments
    private BigDecimal totalBankPayments;
    private BigDecimal totalCashPayments;
    private BigDecimal totalPayments;       // bank + cash
    private int paymentCount;

    // Calculated balance
    private BigDecimal currentDebt;         // startingDebt + totalSales - totalPayments

    // Detailed breakdown
    private List<PaymentDto> payments;
}
