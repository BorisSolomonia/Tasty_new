package ge.tastyerp.config.controller;

import ge.tastyerp.common.dto.ApiResponse;
import ge.tastyerp.common.dto.config.InitialDebtDto;
import ge.tastyerp.config.service.InitialDebtService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for initial debt management.
 *
 * IMPORTANT: Controllers contain NO business logic.
 * All logic is delegated to InitialDebtService.
 */
@RestController
@RequestMapping("/api/config/debts")
@RequiredArgsConstructor
@Tag(name = "Initial Debts", description = "Customer initial debt management")
public class InitialDebtController {

    private final InitialDebtService initialDebtService;

    @GetMapping
    @Operation(summary = "Get all initial debts")
    public ResponseEntity<ApiResponse<List<InitialDebtDto>>> getAllDebts() {
        List<InitialDebtDto> debts = initialDebtService.getAllDebts();
        return ResponseEntity.ok(ApiResponse.success(debts));
    }

    @GetMapping("/{customerId}")
    @Operation(summary = "Get initial debt for a specific customer")
    public ResponseEntity<ApiResponse<InitialDebtDto>> getDebt(@PathVariable String customerId) {
        InitialDebtDto debt = initialDebtService.getDebt(customerId);
        return ResponseEntity.ok(ApiResponse.success(debt));
    }

    @PostMapping
    @Operation(summary = "Add a new initial debt entry")
    public ResponseEntity<ApiResponse<InitialDebtDto>> addDebt(@Valid @RequestBody InitialDebtDto debtDto) {
        InitialDebtDto created = initialDebtService.addDebt(debtDto);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(created, "Initial debt added successfully"));
    }

    @PutMapping("/{customerId}")
    @Operation(summary = "Update an existing initial debt")
    public ResponseEntity<ApiResponse<InitialDebtDto>> updateDebt(
            @PathVariable String customerId,
            @Valid @RequestBody InitialDebtDto debtDto) {
        InitialDebtDto updated = initialDebtService.updateDebt(customerId, debtDto);
        return ResponseEntity.ok(ApiResponse.success(updated, "Initial debt updated successfully"));
    }

    @DeleteMapping("/{customerId}")
    @Operation(summary = "Delete an initial debt entry")
    public ResponseEntity<ApiResponse<Void>> deleteDebt(@PathVariable String customerId) {
        initialDebtService.deleteDebt(customerId);
        return ResponseEntity.ok(ApiResponse.success(null, "Initial debt deleted successfully"));
    }

    @PostMapping("/bulk")
    @Operation(summary = "Bulk import initial debts")
    public ResponseEntity<ApiResponse<List<InitialDebtDto>>> bulkImport(
            @Valid @RequestBody List<InitialDebtDto> debts) {
        List<InitialDebtDto> imported = initialDebtService.bulkImport(debts);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(imported, "Bulk import completed"));
    }
}
