package ge.tastyerp.payment.controller;

import ge.tastyerp.common.dto.ApiResponse;
import ge.tastyerp.common.dto.payment.CustomerPaymentSummary;
import ge.tastyerp.common.dto.payment.ExcelUploadResponse;
import ge.tastyerp.common.dto.payment.PaymentDto;
import ge.tastyerp.common.dto.payment.PaymentStatusDto;
import ge.tastyerp.payment.service.PaymentService;
import ge.tastyerp.payment.service.ExcelProcessingService;
import ge.tastyerp.payment.service.AsyncAggregationService;
import ge.tastyerp.payment.service.DeduplicationService;
import ge.tastyerp.payment.service.PaymentStatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * REST Controller for payment operations.
 *
 * IMPORTANT: Controllers contain NO business logic.
 * All logic is delegated to PaymentService and ExcelProcessingService.
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Tag(name = "Payments", description = "Payment management and reconciliation")
public class PaymentController {

    private final PaymentService paymentService;
    private final ExcelProcessingService excelProcessingService;
    private final AsyncAggregationService asyncAggregationService;
    private final DeduplicationService deduplicationService;
    private final PaymentStatusService paymentStatusService;

    // ==================== EXCEL UPLOAD ====================

    @PostMapping(value = "/excel/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload and process bank statement Excel file")
    public ResponseEntity<ApiResponse<ExcelUploadResponse>> uploadExcel(
            @RequestParam("file") MultipartFile file,
            @RequestParam("bank") String bank) {

        ExcelUploadResponse response = excelProcessingService.processExcelUpload(file, bank);
        return ResponseEntity.ok(ApiResponse.success(response, response.getMessage()));
    }

    @PostMapping(value = "/excel/validate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Validate Excel file without saving (dry run)")
    public ResponseEntity<ApiResponse<ExcelUploadResponse>> validateExcel(
            @RequestParam("file") MultipartFile file,
            @RequestParam("bank") String bank) {

        ExcelUploadResponse response = excelProcessingService.validateExcel(file, bank);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ==================== PAYMENT QUERIES ====================

    @GetMapping
    @Operation(summary = "Get all payments with optional filters")
    public ResponseEntity<ApiResponse<List<PaymentDto>>> getAllPayments(
            @RequestParam(required = false) String customerId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String source) {

        List<PaymentDto> payments = paymentService.getPayments(customerId, startDate, endDate, source);
        return ResponseEntity.ok(ApiResponse.success(payments));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get payment by ID")
    public ResponseEntity<ApiResponse<PaymentDto>> getPaymentById(@PathVariable String id) {
        PaymentDto payment = paymentService.getPaymentById(id);
        return ResponseEntity.ok(ApiResponse.success(payment));
    }

    @GetMapping("/customer/{customerId}")
    @Operation(summary = "Get payment summary for a customer (SUMIFS logic)")
    public ResponseEntity<ApiResponse<CustomerPaymentSummary>> getCustomerSummary(
            @PathVariable String customerId) {

        CustomerPaymentSummary summary = paymentService.getCustomerPaymentSummary(customerId);
        return ResponseEntity.ok(ApiResponse.success(summary));
    }

    @GetMapping("/customer/{customerId}/payments")
    @Operation(summary = "Get all payments for a customer")
    public ResponseEntity<ApiResponse<List<PaymentDto>>> getCustomerPayments(
            @PathVariable String customerId) {

        List<PaymentDto> payments = paymentService.getPaymentsByCustomer(customerId);
        return ResponseEntity.ok(ApiResponse.success(payments));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a payment")
    public ResponseEntity<ApiResponse<Void>> deletePayment(@PathVariable String id) {
        paymentService.deletePayment(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Payment deleted"));
    }

    @DeleteMapping("/bank")
    @Operation(summary = "Delete all bank payments (tbc/bog)")
    public ResponseEntity<ApiResponse<Object>> deleteBankPayments() {
        int deleted = paymentService.purgeBankPayments(List.of("tbc", "bog"));
        String jobId = asyncAggregationService.triggerAggregation("bank_purge");
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("deleted", deleted, "aggregationJobId", jobId),
                "Bank payments deleted"
        ));
    }

    // ==================== STATISTICS ====================

    @GetMapping("/stats")
    @Operation(summary = "Get payment statistics")
    public ResponseEntity<ApiResponse<Object>> getPaymentStats() {
        Object stats = paymentService.getPaymentStatistics();
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @GetMapping("/status")
    @Operation(summary = "Get payment status indicators for all customers",
            description = "Returns color indicators based on days since last payment: " +
                         "< 14 days = none, 14-30 days = yellow, 30+ days = red. " +
                         "Used for visual warnings on payments page.")
    public ResponseEntity<ApiResponse<Map<String, PaymentStatusDto>>> getPaymentStatus() {
        Map<String, PaymentStatusDto> statusMap = paymentStatusService.calculatePaymentStatus();
        return ResponseEntity.ok(ApiResponse.success(statusMap));
    }

    // ==================== AGGREGATION JOB STATUS ====================

    @GetMapping("/aggregation/job/{jobId}")
    @Operation(summary = "Get aggregation job status",
            description = "Poll this endpoint to track async aggregation progress after Excel upload")
    public ResponseEntity<ApiResponse<ge.tastyerp.common.dto.aggregation.AggregationJobDto>> getAggregationJobStatus(
            @PathVariable String jobId) {

        return asyncAggregationService.getJobStatus(jobId)
                .map(job -> ResponseEntity.ok(ApiResponse.success(job)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ==================== DEDUPLICATION ====================

    @GetMapping("/deduplicate/analyze")
    @Operation(summary = "Analyze payments for duplicates (dry run)",
            description = "Returns list of duplicate payment groups without making any changes. " +
                         "Use this to preview what will be removed before calling the remove endpoint.")
    public ResponseEntity<ApiResponse<DeduplicationService.DeduplicationResult>> analyzeDuplicates() {
        DeduplicationService.DeduplicationResult result = deduplicationService.analyzeDuplicates();
        String message = String.format("Found %d duplicate groups (%d payments to delete, ₾%.2f to recover)",
                result.duplicateGroups(), result.paymentsDeleted(), result.amountRecovered());
        return ResponseEntity.ok(ApiResponse.success(result, message));
    }

    @PostMapping("/deduplicate/remove")
    @Operation(summary = "Remove duplicate payments",
            description = "Removes duplicate payments keeping the oldest in each group. " +
                         "This action cannot be undone. Run /analyze first to preview changes.")
    public ResponseEntity<ApiResponse<DeduplicationService.DeduplicationResult>> removeDuplicates() {
        DeduplicationService.DeduplicationResult result = deduplicationService.removeDuplicates();

        // Trigger aggregation after deduplication to update customer debt summaries
        String jobId = null;
        if (result.paymentsDeleted() > 0) {
            try {
                jobId = asyncAggregationService.triggerAggregation("deduplication");
            } catch (Exception e) {
                // Don't fail if aggregation trigger fails
            }
        }

        String message = String.format("Removed %d duplicate payments (₾%.2f recovered). Aggregation job: %s",
                result.paymentsDeleted(), result.amountRecovered(), jobId != null ? jobId : "none");
        return ResponseEntity.ok(ApiResponse.success(result, message));
    }
}
