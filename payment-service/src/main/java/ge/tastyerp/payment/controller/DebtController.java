package ge.tastyerp.payment.controller;

import ge.tastyerp.common.dto.ApiResponse;
import ge.tastyerp.common.dto.payment.DebtOverviewDto;
import ge.tastyerp.payment.service.DebtService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Single authoritative debt endpoint (device-consistency fix).
 * Every page reads this instead of computing debt client-side.
 */
@RestController
@RequestMapping("/api/payments/debts")
@RequiredArgsConstructor
@Tag(name = "Debts", description = "Authoritative customer debt (single source of truth)")
public class DebtController {

    private final DebtService debtService;

    @GetMapping
    @Operation(summary = "Get authoritative debt overview for all customers (with shared exclusions)")
    public ResponseEntity<ApiResponse<DebtOverviewDto>> getOverview() {
        return ResponseEntity.ok(ApiResponse.success(debtService.getOverview()));
    }
}
