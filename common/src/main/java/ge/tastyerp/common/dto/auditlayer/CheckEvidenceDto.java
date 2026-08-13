package ge.tastyerp.common.dto.auditlayer;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Payment evidence — a check or receipt held for a counterparty (BOR-89 §4B).
 *
 * <p>Evidence is kept strictly separate from money. A supplier can hand over a
 * genuine receipt for goods that were never paid for, so a check is only
 * "supported" once it is linked to real bank or cash movement. The unsupported
 * remainder is a headline number, not a rounding difference.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckEvidenceDto {

    private String id;

    /** Document/check number as printed on the evidence. */
    private String documentNumber;

    private String counterpartyName;

    private String counterpartyTin;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate date;

    /** Face value of the evidence. Manually entered. */
    private BigDecimal amount;

    /**
     * Portion backed by matched real money movement. Derived from the mappings
     * that link bank rows to this evidence — never typed in directly.
     */
    private BigDecimal supportedAmount;

    /** {@code amount − supportedAmount}. Derived. */
    private BigDecimal unsupportedAmount;

    /** Operator's classification of why an unsupported balance is acceptable. */
    private String explanation;

    /** True once someone has judged the unsupported balance (alert rule 24). */
    private boolean classified;

    private String note;

    /** Self-declared operator name (decision D-4), not an authenticated user. */
    private String updatedBy;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
}
