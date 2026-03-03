package ge.tastyerp.common.dto.waybill;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Pre-aggregated sales totals per customer.
 * Returned by waybill-service /api/waybills/sales/customer-totals.
 * Avoids transferring thousands of raw WaybillDto objects to payment-service.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerSalesTotalsDto {
    private String customerId;       // buyerTin
    private String customerName;     // buyerName
    private BigDecimal totalSales;   // sum of waybill amounts
    private Integer saleCount;       // number of waybills
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate lastSaleDate;
}
