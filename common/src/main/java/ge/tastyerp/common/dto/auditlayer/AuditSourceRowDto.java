package ge.tastyerp.common.dto.auditlayer;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One immutable source row plus its audit-layer reading (BOR-89 §11).
 *
 * <p>This is the terminal node of every drill-down: {@code total → subgroup →
 * source rows → this record → mapping history}. The raw fields are shown exactly
 * as imported so a reader can always see what the bank or RS.ge actually said,
 * next to what the audit decided about it.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditSourceRowDto {

    private AuditSourceType sourceType;

    /** Id within its own source collection. */
    private String sourceRowId;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate date;

    /** CREDIT / DEBIT for bank rows; PURCHASE / SALE for documents. */
    private String direction;

    private BigDecimal amount;

    private BigDecimal quantityKg;

    private String productName;

    private String counterpartyName;

    private String counterpartyTin;

    /** Raw description exactly as imported. Never normalised. */
    private String description;

    /** Secondary raw description field, where the source has one. */
    private String additionalInformation;

    /** Document number / transaction id from the source. */
    private String reference;

    /**
     * The bank's own transaction-type wording (statement column G), verbatim —
     * e.g. {@code გადარიცხვა თანხის გატანა}, {@code სხვა ხარჯები}. This is the
     * strongest classification signal in the file because it is the bank's
     * assertion rather than an inference from free text.
     */
    private String transactionType;

    /**
     * The counterparty tax code after audit-layer resolution. Equals
     * {@link #counterpartyTin} when the statement printed one; otherwise it may
     * be inferred from the name. Null when identity could not be established.
     */
    private String resolvedCounterpartyTin;

    /** How {@link #resolvedCounterpartyTin} was established. */
    private CounterpartyIdentitySource counterpartyIdentitySource;

    /** Plain-language basis for the identity, so an inference can be checked. */
    private String counterpartyIdentityBasis;

    private AuditMappingStatus status;

    /** Current mapping, if any. */
    private AuditMappingDto mapping;

    /** Portion of {@link #amount} not allocated by any split. */
    private BigDecimal unresolvedAmount;

    /** Full append-only history for this row's mappings and overrides. */
    private List<AuditChangeLogDto> history;
}
