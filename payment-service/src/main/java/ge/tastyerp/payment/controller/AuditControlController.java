package ge.tastyerp.payment.controller;

import ge.tastyerp.common.dto.ApiResponse;
import ge.tastyerp.common.dto.audit.AuditDashboardDto;
import ge.tastyerp.common.dto.audit.AuditExceptionDto;
import ge.tastyerp.common.dto.audit.TargetedExpenseDto;
import ge.tastyerp.payment.service.audit.AuditControlService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * REST Controller for the Audit Control & Reconciliation dashboard (BOR-74).
 *
 * IMPORTANT: Controllers contain NO business logic — all logic is delegated to
 * {@link AuditControlService}.
 */
@RestController
@RequestMapping("/api/audit-control")
@RequiredArgsConstructor
@Tag(name = "Audit Control", description = "Inventory, write-off and reconciliation audit dashboard")
public class AuditControlController {

    private final AuditControlService auditControlService;

    @GetMapping("/dashboard")
    @Operation(summary = "Get the full audit-control dashboard for a date range")
    public ResponseEntity<ApiResponse<AuditDashboardDto>> getDashboard(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String product) {
        AuditDashboardDto dashboard = auditControlService.getDashboard(startDate, endDate, product);
        return ResponseEntity.ok(ApiResponse.success(dashboard));
    }

    @GetMapping("/targeted-expense")
    @Operation(summary = "Get total expenses isolated for the configured target ID over a range")
    public ResponseEntity<ApiResponse<TargetedExpenseDto>> getTargetedExpense(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        TargetedExpenseDto result = auditControlService.computeTargetedExpense(startDate, endDate);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/exceptions")
    @Operation(summary = "List tracked reconciliation exceptions")
    public ResponseEntity<ApiResponse<List<AuditExceptionDto>>> getExceptions() {
        return ResponseEntity.ok(ApiResponse.success(auditControlService.getExceptions()));
    }

    @PostMapping("/exceptions")
    @Operation(summary = "Create or update a reconciliation exception")
    public ResponseEntity<ApiResponse<AuditExceptionDto>> saveException(
            @RequestBody AuditExceptionDto exception) {
        AuditExceptionDto saved = auditControlService.saveException(exception);
        return ResponseEntity.ok(ApiResponse.success(saved, "Exception saved"));
    }

    @DeleteMapping("/exceptions/{id}")
    @Operation(summary = "Delete a reconciliation exception")
    public ResponseEntity<ApiResponse<Void>> deleteException(@PathVariable String id) {
        auditControlService.deleteException(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Exception deleted"));
    }

    @PutMapping("/reconciliation/{key}/paid")
    @Operation(summary = "Manually mark a customer/transaction balance as paid (override API status)")
    public ResponseEntity<ApiResponse<Void>> setManualPaid(
            @PathVariable String key,
            @RequestParam boolean markedPaid,
            @RequestParam(required = false) String note) {
        auditControlService.setManualPaid(key, markedPaid, note);
        return ResponseEntity.ok(ApiResponse.success(null, "Override updated"));
    }
}
