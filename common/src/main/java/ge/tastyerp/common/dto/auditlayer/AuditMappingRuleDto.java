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
 * A reusable classification the user chose to apply beyond one transaction
 * (BOR-91).
 *
 * <p>A rule is created only by explicit choice: the mapping dialog offers the
 * criteria that actually match the row in hand, each with a live count of what
 * it would catch, and the user picks one. Nothing generalises on its own.</p>
 *
 * <p>Rules stay live — a statement row imported next month that matches is
 * mapped automatically and tagged with the rule that did it, so the reason is
 * always readable on the row. Revoking a rule un-maps everything it created,
 * which is what makes it safe to be generous with them.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditMappingRuleDto {

    private String id;

    /** What counts as similar. */
    private MappingRuleCriterion criterion;

    /** CREDIT or DEBIT. A rule never crosses direction. */
    private String direction;

    /** Counterparty tax code the rule keys on, when the criterion uses one. */
    private String counterpartyTin;

    /** Counterparty name, normalised, used when there is no tax code. */
    private String counterpartyName;

    /** Exact description the rule keys on, when the criterion uses one. */
    private String description;

    /** The bank's transaction type the rule keys on, when the criterion uses one. */
    private String transactionType;

    // --- what the rule asserts ---

    /** Category every matching transaction is mapped to. */
    private String categoryCode;

    /** Counterparty recorded on the resulting mapping, when relevant. */
    private String mappedCounterpartyName;
    private String mappedCounterpartyTin;

    /** Human explanation shown wherever a row says why it was mapped. */
    private String note;

    /** False once revoked; kept so history still resolves. */
    private boolean active;

    /** How many mappings this rule has created so far. */
    private int appliedCount;

    private BigDecimal appliedAmount;

    /** Self-declared operator name (decision D-4), not an authenticated identity. */
    private String createdBy;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    private String updatedBy;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;

    /**
     * What this rule would do, shown before the user commits (BOR-91: "make the
     * meaning of similar transactions understandable before confirming").
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Preview {
        private MappingRuleCriterion criterion;
        /** Plain-language statement of the match, e.g. "every payment to X". */
        private String explanation;
        /** How many rows match, including the one in hand. */
        private int matchCount;
        /** Their combined value. */
        private BigDecimal matchAmount;
        /** How many of those already carry a mapping a person made. */
        private int alreadyMappedByPersonCount;
        /** A sample so the user can see what they are about to catch. */
        private List<AuditSourceRowDto> sample;
    }
}
