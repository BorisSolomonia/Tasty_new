package ge.tastyerp.common.dto.auditlayer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * Map a chosen set of bank rows in one go (BOR-92 v4). The set is explicit —
 * ids the operator saw and selected — never a pattern; a pattern is a rule
 * and goes through the rule path with its own confirmation.
 *
 * <p>Each row receives one split of the given group / document status /
 * counterparty. By default the split covers only the row's <em>unmapped
 * remainder</em>, so a decision a person already made on a row is kept; with
 * {@code replace} the row's existing splits are dropped and the split covers
 * the whole amount.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditBulkMapRequestDto {
    private LocalDate startDate;
    private LocalDate endDate;
    private AuditSourceType sourceType;
    private List<String> sourceRowIds;
    private String categoryCode;
    private String subgroupCode;
    private String counterpartyTin;
    private String counterpartyName;
    private String note;
    private boolean replace;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Result {
        private int mapped;
        /** Rows already fully mapped (default mode) or not found in the period. */
        private int skipped;
        private java.math.BigDecimal amount;
    }
}
