package ge.tastyerp.payment.controller;

import ge.tastyerp.common.dto.ApiResponse;
import ge.tastyerp.common.dto.payment.CustomerAnalysisDto;
import ge.tastyerp.payment.service.CustomerAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for customer debt analysis.
 *
 * IMPORTANT: Controllers contain NO business logic.
 * All logic is delegated to CustomerAnalysisService.
 *
 * Business Logic:
 * - Starting debt: from initial_debts (as of cutoff date 2025-04-29)
 * - Sales: waybills AFTER cutoff (> 2025-04-29)
 * - Payments: ALL payments AFTER cutoff (>= 2025-04-30)
 * - Debt formula: startingDebt + totalSales - totalPayments
 */
@RestController
@RequestMapping("/api/payments/analysis")
@RequiredArgsConstructor
@Tag(name = "Customer Analysis", description = "Customer debt analysis and reconciliation")
public class CustomerAnalysisController {

    private final CustomerAnalysisService customerAnalysisService;

    @GetMapping
    @Operation(summary = "Get customer analysis for all customers")
    public ResponseEntity<ApiResponse<List<CustomerAnalysisDto>>> analyzeAllCustomers() {
        List<CustomerAnalysisDto> analysis = customerAnalysisService.analyzeAllCustomers();
        return ResponseEntity.ok(ApiResponse.success(analysis));
    }

    @GetMapping("/{customerId}")
    @Operation(summary = "Get customer analysis for a specific customer")
    public ResponseEntity<ApiResponse<CustomerAnalysisDto>> analyzeCustomer(
            @PathVariable String customerId) {
        CustomerAnalysisDto analysis = customerAnalysisService.analyzeCustomer(customerId);
        return ResponseEntity.ok(ApiResponse.success(analysis));
    }
}
