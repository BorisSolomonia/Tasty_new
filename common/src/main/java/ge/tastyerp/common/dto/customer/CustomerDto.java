package ge.tastyerp.common.dto.customer;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Data Transfer Object for Customer entities.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerDto {

    private String id;

    @NotBlank(message = "Customer name is required")
    private String customerName;

    @NotBlank(message = "Identification (TIN) is required")
    private String identification;      // TIN - 9 or 11 digits

    private String contactInfo;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
