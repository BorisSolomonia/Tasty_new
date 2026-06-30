package ge.tastyerp.common.dto.audit;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A reconciliation exception tracked in the Firestore {@code audit_exceptions}
 * collection (BOR-74 Phase 2).
 *
 * Exceptions are either system-generated (e.g. a write-off overage, an unmatched
 * waybill, a missing payment proof) or created manually by an auditor.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditExceptionDto {

    private String id;

    /** Machine code, e.g. WRITE_OFF_OVERAGE, UNMATCHED_WAYBILL, MISSING_PAYMENT_PROOF, MANUAL. */
    private String type;

    /** Free-text explanation. */
    private String description;

    /** Optional references to the offending records. */
    private String referenceId;     // waybillId / paymentId
    private String customerId;
    private BigDecimal amount;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate date;

    /** OPEN or RESOLVED. */
    @Builder.Default
    private String status = "OPEN";

    /** True when created by a user rather than the reconciliation engine. */
    private boolean manual;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    private String createdBy;
}
