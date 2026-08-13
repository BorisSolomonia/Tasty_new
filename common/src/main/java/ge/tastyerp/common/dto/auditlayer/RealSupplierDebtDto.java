package ge.tastyerp.common.dto.auditlayer;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Manually maintained real outstanding debt to one supplier (BOR-89 §4B).
 *
 * <p>Deliberately an input, not a derived figure. The ticket forbids
 * {@code goods = bank payments + withdrawals + debt} because a withdrawal does
 * not prove a supplier was settled.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RealSupplierDebtDto {

    /** Firestore document id — the supplier TIN when known, else the name. */
    private String id;

    private String supplierName;

    private String supplierTin;

    /** Outstanding amount the operator asserts is still owed. */
    private BigDecimal outstandingAmount;

    private String note;

    /** Self-declared operator name (decision D-4), not an authenticated user. */
    private String updatedBy;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
}
