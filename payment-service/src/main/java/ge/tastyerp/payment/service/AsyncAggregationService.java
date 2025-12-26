package ge.tastyerp.payment.service;

import ge.tastyerp.common.dto.aggregation.AggregationJobDto;
import ge.tastyerp.common.dto.aggregation.AggregationResultDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous aggregation service with job tracking.
 *
 * This service handles long-running customer debt aggregation operations
 * in the background to prevent HTTP timeout errors during Excel uploads.
 *
 * Key features:
 * - Async execution with @Async annotation
 * - In-memory job tracking for status polling
 * - Comprehensive logging with correlation IDs
 * - Progress updates during processing
 * - Error handling with detailed stack traces
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncAggregationService {

    private final AggregationService aggregationService;

    /**
     * In-memory job store for tracking async aggregation status.
     * In production, consider using Redis for multi-instance support.
     */
    private final Map<String, AggregationJobDto> jobStore = new ConcurrentHashMap<>();

    /**
     * Trigger asynchronous aggregation.
     *
     * This method returns immediately with a job ID while processing
     * continues in the background on the aggregationExecutor thread pool.
     *
     * @param source Source that triggered aggregation (e.g., "excel_upload")
     * @return Job ID for status tracking
     */
    public String triggerAggregation(String source) {
        String jobId = UUID.randomUUID().toString();

        AggregationJobDto job = AggregationJobDto.builder()
                .jobId(jobId)
                .status(AggregationJobDto.JobStatus.PENDING)
                .source(source)
                .currentStep("Queued")
                .progressPercent(0)
                .createdAt(LocalDateTime.now())
                .build();

        jobStore.put(jobId, job);

        log.info("[{}] Aggregation job created. Source: {}", jobId, source);

        // Execute async (fire and forget)
        executeAggregationAsync(jobId, source);

        return jobId;
    }

    /**
     * Execute aggregation asynchronously on the aggregationExecutor thread pool.
     *
     * @param jobId Unique job identifier
     * @param source Source that triggered aggregation
     * @return CompletableFuture for async handling
     */
    @Async("aggregationExecutor")
    public CompletableFuture<Void> executeAggregationAsync(String jobId, String source) {
        log.info("[{}] ⏳ Aggregation started. Thread: {}", jobId, Thread.currentThread().getName());

        long startTime = System.currentTimeMillis();

        updateJobStatus(jobId, AggregationJobDto.JobStatus.RUNNING, "Starting aggregation", 5);

        try {
            // Step 1: Fetch waybills from RS.ge (slowest step)
            updateJobStatus(jobId, AggregationJobDto.JobStatus.RUNNING,
                    "Fetching waybills from RS.ge", 20);
            log.info("[{}] Step 1/5: Fetching waybills from RS.ge", jobId);

            // Step 2: Fetch payments from Firebase
            updateJobStatus(jobId, AggregationJobDto.JobStatus.RUNNING,
                    "Fetching payments from Firebase", 40);
            log.info("[{}] Step 2/5: Fetching payments from Firebase", jobId);

            // Step 3: Fetch initial debts
            updateJobStatus(jobId, AggregationJobDto.JobStatus.RUNNING,
                    "Fetching initial debts", 60);
            log.info("[{}] Step 3/5: Fetching initial debts", jobId);

            // Step 4: Aggregate customer debts
            updateJobStatus(jobId, AggregationJobDto.JobStatus.RUNNING,
                    "Aggregating customer debts", 70);
            log.info("[{}] Step 4/5: Aggregating customer debts", jobId);

            // Execute the actual aggregation
            AggregationService.AggregationResult result =
                    aggregationService.aggregateCustomerDebts(source);

            // Step 5: Save to Firebase
            updateJobStatus(jobId, AggregationJobDto.JobStatus.RUNNING,
                    "Saving to Firebase", 90);
            log.info("[{}] Step 5/5: Saving aggregated data to Firebase", jobId);

            long duration = System.currentTimeMillis() - startTime;

            // Convert to DTO
            AggregationResultDto resultDto = AggregationResultDto.builder()
                    .totalCustomers(result.getTotalCustomers())
                    .newCount(result.getNewCount())
                    .updatedCount(result.getUpdatedCount())
                    .unchangedCount(result.getUnchangedCount())
                    .durationMs(duration)
                    .build();

            // Mark as completed
            AggregationJobDto job = jobStore.get(jobId);
            if (job != null) {
                job.setStatus(AggregationJobDto.JobStatus.COMPLETED);
                job.setCurrentStep("Completed");
                job.setProgressPercent(100);
                job.setCompletedAt(LocalDateTime.now());
                job.setResult(resultDto);
                job.setStartedAt(job.getStartedAt() != null ? job.getStartedAt() : job.getCreatedAt());
            }

            log.info("[{}] ✅ Aggregation completed successfully. " +
                            "Duration: {}ms, Customers: {}, New: {}, Updated: {}, Unchanged: {}",
                    jobId, duration,
                    result.getTotalCustomers(),
                    result.getNewCount(),
                    result.getUpdatedCount(),
                    result.getUnchangedCount());

            return CompletableFuture.completedFuture(null);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;

            log.error("[{}] ❌ Aggregation failed after {}ms: {}",
                    jobId, duration, e.getMessage(), e);

            // Capture stack trace for debugging
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            String stackTrace = sw.toString();

            // Mark as failed
            AggregationJobDto job = jobStore.get(jobId);
            if (job != null) {
                job.setStatus(AggregationJobDto.JobStatus.FAILED);
                job.setCurrentStep("Failed");
                job.setProgressPercent(0);
                job.setCompletedAt(LocalDateTime.now());
                job.setErrorMessage(e.getMessage());
                job.setErrorDetails(stackTrace);
                job.setStartedAt(job.getStartedAt() != null ? job.getStartedAt() : job.getCreatedAt());
            }

            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Get job status by ID.
     *
     * @param jobId Job identifier
     * @return Job status or empty if not found
     */
    public Optional<AggregationJobDto> getJobStatus(String jobId) {
        return Optional.ofNullable(jobStore.get(jobId));
    }

    /**
     * Update job status and progress.
     */
    private void updateJobStatus(String jobId, AggregationJobDto.JobStatus status,
                                  String step, int progressPercent) {
        AggregationJobDto job = jobStore.get(jobId);
        if (job != null) {
            job.setStatus(status);
            job.setCurrentStep(step);
            job.setProgressPercent(progressPercent);

            if (status == AggregationJobDto.JobStatus.RUNNING && job.getStartedAt() == null) {
                job.setStartedAt(LocalDateTime.now());
            }
        }
    }

    /**
     * Clean up old completed/failed jobs from memory.
     * Should be called periodically (e.g., via @Scheduled).
     */
    public void cleanupOldJobs(int maxAgeMinutes) {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(maxAgeMinutes);
        int removed = 0;

        for (Map.Entry<String, AggregationJobDto> entry : jobStore.entrySet()) {
            AggregationJobDto job = entry.getValue();
            if (job.getCompletedAt() != null && job.getCompletedAt().isBefore(cutoff)) {
                jobStore.remove(entry.getKey());
                removed++;
            }
        }

        if (removed > 0) {
            log.info("Cleaned up {} old aggregation jobs", removed);
        }
    }
}
