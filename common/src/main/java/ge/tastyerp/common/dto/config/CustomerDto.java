package ge.tastyerp.common.dto.config;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for customer master data.
 *
 * Represents customers stored in Firebase customers collection.
 * Used for name resolution in payment reconciliation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerDto {

    /**
     * Customer identification number (TIN).
     * Format: 9 or 11 digits.
     */
    @NotBlank(message = "Identification is required")
    private String identification;

    /**
     * Customer name in Georgian.
     */
    @NotBlank(message = "Customer name is required")
    private String customerName;

    /**
     * Contact information (optional).
     */
    private String contactInfo;
}
