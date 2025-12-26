package ge.tastyerp.common.dto.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Initial debt entry for a customer.
 * Stored in Firebase config/initial_debts document.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InitialDebtDto {

    @NotBlank(message = "Customer ID is required")
    private String customerId;

    @NotBlank(message = "Customer name is required")
    private String name;

    @NotNull(message = "Debt amount is required")
    @PositiveOrZero(message = "Debt must be zero or positive")
    private BigDecimal debt;

    @NotBlank(message = "Date is required")
    private String date;    // YYYY-MM-DD format
}
