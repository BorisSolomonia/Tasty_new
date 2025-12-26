package ge.tastyerp.payment.controller;

import ge.tastyerp.common.dto.ApiResponse;
import ge.tastyerp.common.dto.payment.ExcelUploadResponse;
import ge.tastyerp.common.dto.payment.ManualCashPaymentDto;
import ge.tastyerp.payment.service.ManualCashExcelImportService;
import ge.tastyerp.payment.service.ManualCashPaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * REST Controller for manual cash payment management.
 *
 * IMPORTANT: Controllers contain NO business logic.
 * All logic is delegated to ManualCashPaymentService.
 */
@RestController
@RequestMapping("/api/payments/manual")
@RequiredArgsConstructor
@Tag(name = "Manual Cash Payments", description = "Manual cash payment management")
public class ManualCashPaymentController {

    private final ManualCashPaymentService manualCashPaymentService;
    private final ManualCashExcelImportService manualCashExcelImportService;

    @GetMapping
    @Operation(summary = "Get all manual cash payments")
    public ResponseEntity<ApiResponse<List<ManualCashPaymentDto>>> getAllManualPayments() {
        List<ManualCashPaymentDto> payments = manualCashPaymentService.getAllManualPayments();
        return ResponseEntity.ok(ApiResponse.success(payments));
    }

    @GetMapping("/customer/{customerId}")
    @Operation(summary = "Get manual cash payments for a customer")
    public ResponseEntity<ApiResponse<List<ManualCashPaymentDto>>> getManualPaymentsByCustomer(
            @PathVariable String customerId) {
        List<ManualCashPaymentDto> payments = manualCashPaymentService.getManualPaymentsByCustomer(customerId);
        return ResponseEntity.ok(ApiResponse.success(payments));
    }

    @PostMapping
    @Operation(summary = "Add a manual cash payment")
    public ResponseEntity<ApiResponse<ManualCashPaymentDto>> addManualPayment(
            @Valid @RequestBody ManualCashPaymentDto paymentDto) {
        ManualCashPaymentDto created = manualCashPaymentService.addManualPayment(paymentDto);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(created, "Manual cash payment added successfully"));
    }

    @PostMapping(value = "/excel/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload manual cash payments from Excel file")
    public ResponseEntity<ApiResponse<ExcelUploadResponse>> uploadManualExcel(
            @RequestParam("file") MultipartFile file) {
        ExcelUploadResponse response = manualCashExcelImportService.processManualExcelUpload(file);
        return ResponseEntity.ok(ApiResponse.success(response, response.getMessage()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a manual cash payment")
    public ResponseEntity<ApiResponse<Void>> deleteManualPayment(@PathVariable String id) {
        manualCashPaymentService.deleteManualPayment(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Manual cash payment deleted successfully"));
    }
}
