package ge.tastyerp.common.dto.auditlayer;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One permanent record of a manual audit-layer change (BOR-89 §12).
 *
 * <p>Append-only. Nothing in the audit layer deletes or edits a log entry, which
 * is what makes "deleting a mapping while retaining history" possible.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditChangeLogDto {

    private String id;

    /** What kind of thing changed — MAPPING, REAL_INVENTORY, SUPPLIER_DEBT, ... */
    private String entityType;

    /** Id of the changed entity, or of the source row it classifies. */
    private String entityId;

    /** Field or aspect that changed. */
    private String field;

    /** Serialised previous value. Null when the entity was created. */
    private String oldValue;

    /** Serialised new value. Null when the entity was voided. */
    private String newValue;

    /** Self-declared operator name (decision D-4), not an authenticated user. */
    private String changedBy;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime changedAt;

    private String reason;
}
