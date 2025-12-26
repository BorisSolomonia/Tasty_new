package ge.tastyerp.common.dto.aggregation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for aggregation operation results.
 *
 * Returned when aggregation completes successfully.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AggregationResultDto {

    /**
     * Total number of customers processed.
     */
    private Integer totalCustomers;

    /**
     * Number of new customer debt summaries created.
     */
    private Integer newCount;

    /**
     * Number of existing customer debt summaries updated.
     */
    private Integer updatedCount;

    /**
     * Number of customer debt summaries unchanged (no data change detected).
     */
    private Integer unchangedCount;

    /**
     * Processing duration in milliseconds.
     */
    private Long durationMs;
}
