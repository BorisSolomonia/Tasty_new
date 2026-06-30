package ge.tastyerp.common.dto.audit;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Total expenses isolated for a single target identification number
 * (e.g. ID 01008026584) over the selected date range (BOR-74 Phase 2).
 *
 * Data-source note: the current payment model only captures the payer TIN +
 * amount + date + free-text description (no dedicated recipient/counterparty
 * account field, and no live BOG/TBC JSON API). The total is therefore matched
 * heuristically on the payer identification and the description text; the
 * {@code matchedOnDescription} flag records when only the description matched.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TargetedExpenseDto {

    private String targetId;
    private BigDecimal totalExpense;
    private int matchCount;

    private List<Match> matches;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Match {
        private String paymentId;
        private String source;
        private BigDecimal amount;

        @JsonFormat(pattern = "yyyy-MM-dd")
        private LocalDate date;

        private String description;
        private boolean matchedOnDescription;
    }
}
