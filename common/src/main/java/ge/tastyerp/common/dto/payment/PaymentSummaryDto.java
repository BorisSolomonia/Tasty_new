package ge.tastyerp.common.dto.payment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Summary DTO for payments in customer analysis.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentSummaryDto {
    private String paymentId;
    private String customerId;
    private BigDecimal payment;
    private LocalDate date;
    private Boolean isAfterCutoff;
    private String source;
    private String uniqueCode;
    private String description;
    private BigDecimal balance;
}
