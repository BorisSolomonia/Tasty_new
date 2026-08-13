package ge.tastyerp.common.dto.auditlayer;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A name-to-tax-code link a person taught the audit layer (BOR-89 §14).
 *
 * <p>Bank statements frequently name a counterparty without printing its tax
 * code. Where the same name appears elsewhere <em>with</em> a code the link can
 * be learned automatically; where it never does, somebody has to say so. This is
 * that record.</p>
 *
 * <p>It is an audit-layer overlay, never a rewrite: the statement row keeps the
 * blank it was imported with, and every figure built on a resolved identity
 * reports that its identity was inferred.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CounterpartyAliasDto {

    /** Firestore document id — the normalised name. */
    private String id;

    /** The counterparty name exactly as the statement writes it. */
    private String rawName;

    /** Whitespace-collapsed, comma-trimmed, lower-cased form used for matching. */
    private String normalizedName;

    /** The tax code this name belongs to. */
    private String counterpartyTin;

    private String note;

    /** Self-declared operator name (decision D-4), not an authenticated identity. */
    private String createdBy;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;
}
