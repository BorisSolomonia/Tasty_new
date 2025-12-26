package ge.tastyerp.common.dto.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * DTO for manual cash payments.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManualCashPaymentDto {

    /**
     * Payment ID (auto-generated).
     */
    private String id;

    /**
     * Customer ID (TIN).
     */
    @NotBlank(message = "Customer ID is required")
    private String customerId;

    /**
     * Customer name (optional, for display).
     */
    private String customerName;

    /**
     * Payment amount.
     */
    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be positive")
    private BigDecimal amount;

    /**
     * Payment date.
     */
    @NotNull(message = "Payment date is required")
    private LocalDate paymentDate;

    /**
     * Optional description.
     */
    private String description;

    /**
     * When this payment was created.
     */
    private String createdAt;
}
