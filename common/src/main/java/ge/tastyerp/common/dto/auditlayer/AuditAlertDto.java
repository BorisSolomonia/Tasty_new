package ge.tastyerp.common.dto.auditlayer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * One fired audit rule (BOR-89 §9).
 *
 * <p>Every alert carries the inputs it was computed from, so the UI can show the
 * arithmetic rather than an unexplained red number. That is the ticket's
 * "implement transparent rules first" requirement.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditAlertDto {

    /** Stable rule id, e.g. {@code CASH_WITHDRAWAL_UNRESOLVED}. */
    private String ruleId;

    /** CRITICAL | HIGH | MEDIUM | LOW. */
    private String severity;

    /** INVENTORY | CASH | DOCUMENTATION — which flow the rule belongs to. */
    private String flow;

    /** One-line human statement of what fired. */
    private String title;

    /** The formula in words, e.g. "settlement + debt > documented purchases". */
    private String formula;

    /** Named inputs the formula was evaluated on. */
    private Map<String, BigDecimal> inputs;

    /** Headline amount, where the rule has one. */
    private BigDecimal amount;

    /** Headline quantity, where the rule has one. */
    private BigDecimal quantityKg;

    /** How many source rows contributed. */
    private int affectedRowCount;

    /** Drill-down key the UI passes back to fetch the contributing rows. */
    private String drilldownKey;

    /** Products or counterparties responsible, when the rule identifies them. */
    private List<String> subjects;
}
