package ge.tastyerp.common.dto.auditlayer;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * An audit-layer classification of one immutable source row (BOR-89 §6).
 *
 * <p>Nothing here is written back to the source. The mapping is a separate
 * record that says how the audit reads a row; the row itself is untouched.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditMappingDto {

    /** Firestore document id. Null when creating. */
    private String id;

    private AuditSourceType sourceType;

    /** Id of the source row in its own collection. Immutable. */
    private String sourceRowId;

    /** Source amount at mapping time, copied for validation and display only. */
    private BigDecimal sourceAmount;

    private AuditMappingStatus status;

    /** Allocations of {@link #sourceAmount}. May be empty (fully unresolved). */
    private List<AuditMappingSplitDto> splits;

    /**
     * Derived, never stored: {@code sourceAmount − Σ split amounts}. A non-zero
     * value is the visible unresolved remainder the ticket requires (§6).
     */
    private BigDecimal unresolvedAmount;

    /** Other source rows this mapping links to, as {@code TYPE:id} strings. */
    private List<String> linkedSourceRows;

    /** Why a rule proposed this mapping. Null for hand-made mappings. */
    private String suggestionReason;

    /** 0-100 for suggestions. Null when a person made the decision. */
    private Integer confidence;

    /** Free-text justification for the mapping as a whole. */
    private String note;

    /**
     * Self-declared operator name (decision D-4). The API has no authentication,
     * so this is a recorded claim, not an authenticated identity.
     */
    private String createdBy;

    private String updatedBy;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
}
