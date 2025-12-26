package ge.tastyerp.common.dto.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request metadata for Excel upload.
 * The actual file is sent as multipart.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExcelUploadRequest {

    @NotBlank(message = "Bank is required")
    @Pattern(regexp = "^(tbc|bog)$", message = "Bank must be 'tbc' or 'bog'")
    private String bank;

    // Optional: validate only without saving
    private boolean validateOnly;
}
