package ge.tastyerp.common.dto.payment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Summary DTO for waybills in customer analysis.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WaybillSummaryDto {
    private String waybillId;
    private String customerId;
    private String customerName;
    private LocalDate date;
    private BigDecimal amount;
    private Boolean isAfterCutoff;
}
