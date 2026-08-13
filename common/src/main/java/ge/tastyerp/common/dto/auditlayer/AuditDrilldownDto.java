package ge.tastyerp.common.dto.auditlayer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * The expansion of one aggregate into the exact rows that compose it (§11).
 *
 * <p>{@link #total} is recomputed from {@link #rows} rather than copied from the
 * headline figure, so a drill-down that does not add up is visible instead of
 * hidden.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditDrilldownDto {

    /** The key that was expanded, e.g. {@code cash.unresolvedWithdrawals}. */
    private String drilldownKey;

    /** Human label for the aggregate. */
    private String label;

    /** Plain-language statement of what these rows have in common. */
    private String definition;

    /** Σ of the rows below. */
    private BigDecimal total;

    private BigDecimal totalQuantityKg;

    private int rowCount;

    private List<AuditSourceRowDto> rows;

    /** True when {@link #rows} was capped; the UI must say so rather than imply completeness. */
    private boolean truncated;
}
