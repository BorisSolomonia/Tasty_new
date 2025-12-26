package ge.tastyerp.common.dto.aggregation;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Aggregated customer debt summary.
 *
 * This DTO represents the complete debt picture for a customer:
 * - Sales (aggregated from RS.ge waybills)
 * - Payments (aggregated from Firebase payments)
 * - Starting debt (from initial_debts)
 * - Current debt (calculated)
 *
 * Purpose: Minimize Firebase reads by storing aggregated data.
 * Updated: When Excel is uploaded, bank API syncs, or manual sync triggered.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerDebtSummaryDto {

    private String customerId;
    private String customerName;

    // Sales aggregation (from RS.ge)
    private BigDecimal totalSales;
    private Integer saleCount;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate lastSaleDate;

    // Payments aggregation (from Firebase payments)
    private BigDecimal totalPayments;
    private Integer paymentCount;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate lastPaymentDate;

    // Manual cash payments (from Firebase manual_cash_payments)
    private BigDecimal totalCashPayments;
    private Integer cashPaymentCount;

    // Starting debt (from initial_debts)
    private BigDecimal startingDebt;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startingDebtDate;

    // Current debt (calculated: startingDebt + totalSales - totalPayments - totalCashPayments)
    private BigDecimal currentDebt;

    // Metadata
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime lastUpdated;

    private String updateSource; // "excel_upload", "bank_api", "manual_sync"
}
