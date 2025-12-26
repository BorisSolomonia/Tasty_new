package ge.tastyerp.common.dto.aggregation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO for tracking asynchronous aggregation job status.
 *
 * Used to provide real-time feedback to frontend about
 * background aggregation progress after Excel uploads.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AggregationJobDto {

    /**
     * Unique job identifier (UUID).
     */
    private String jobId;

    /**
     * Job status: PENDING, RUNNING, COMPLETED, FAILED.
     */
    private JobStatus status;

    /**
     * Source that triggered aggregation (e.g., "excel_upload", "manual_refresh").
     */
    private String source;

    /**
     * Current processing step for progress tracking.
     */
    private String currentStep;

    /**
     * Overall progress percentage (0-100).
     */
    private Integer progressPercent;

    /**
     * When the job was created.
     */
    private LocalDateTime createdAt;

    /**
     * When the job started processing.
     */
    private LocalDateTime startedAt;

    /**
     * When the job completed (success or failure).
     */
    private LocalDateTime completedAt;

    /**
     * Aggregation result (only present if status = COMPLETED).
     */
    private AggregationResultDto result;

    /**
     * Error message (only present if status = FAILED).
     */
    private String errorMessage;

    /**
     * Stack trace for debugging (only present if status = FAILED).
     */
    private String errorDetails;

    public enum JobStatus {
        PENDING,    // Queued, not yet started
        RUNNING,    // Currently processing
        COMPLETED,  // Successfully finished
        FAILED      // Error occurred
    }
}
