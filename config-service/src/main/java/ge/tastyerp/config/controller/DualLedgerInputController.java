package ge.tastyerp.config.controller;

import ge.tastyerp.common.dto.ApiResponse;
import ge.tastyerp.common.dto.config.CategoryLedgerInputDto;
import ge.tastyerp.config.service.DualLedgerInputService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Manage per-category dual-ledger input overrides (real prices/quantities and
 * documented-price overrides) used by the Audit Control dual-ledger module.
 * Controller only delegates to the service (no business logic).
 */
@RestController
@RequestMapping("/api/config/dual-ledger-inputs")
@RequiredArgsConstructor
@Tag(name = "Dual-Ledger Inputs", description = "Editable real/documented price & quantity overrides per category")
public class DualLedgerInputController {

    private final DualLedgerInputService service;

    @GetMapping
    @Operation(summary = "Get all per-category dual-ledger input overrides")
    public ResponseEntity<ApiResponse<List<CategoryLedgerInputDto>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success(service.getAll()));
    }

    @PutMapping
    @Operation(summary = "Upsert a category's dual-ledger input overrides (one entry per category)")
    public ResponseEntity<ApiResponse<CategoryLedgerInputDto>> setInput(@RequestBody CategoryLedgerInputDto request) {
        CategoryLedgerInputDto saved = service.setInput(request);
        return ResponseEntity.ok(ApiResponse.success(saved, "Dual-ledger input saved"));
    }
}
