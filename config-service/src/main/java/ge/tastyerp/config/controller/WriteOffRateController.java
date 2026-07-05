package ge.tastyerp.config.controller;

import ge.tastyerp.common.dto.ApiResponse;
import ge.tastyerp.common.dto.config.WriteOffRateDto;
import ge.tastyerp.config.service.WriteOffRateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Manage per-category "possible write-off" rates used by the Audit Control
 * inventory ledger. Controller only delegates to the service (no business logic).
 */
@RestController
@RequestMapping("/api/config/write-off-rates")
@RequiredArgsConstructor
@Tag(name = "Write-off Rates", description = "Editable per-category possible write-off percentages for audit")
public class WriteOffRateController {

    private final WriteOffRateService service;

    @GetMapping
    @Operation(summary = "Get the effective write-off rate for each write-off category")
    public ResponseEntity<ApiResponse<List<WriteOffRateDto>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success(service.getAll()));
    }

    @PutMapping
    @Operation(summary = "Set a category's write-off percentage (one rate per category)")
    public ResponseEntity<ApiResponse<WriteOffRateDto>> setRate(@RequestBody WriteOffRateDto request) {
        WriteOffRateDto saved = service.setRate(request.getCategory(), request.getPercent());
        return ResponseEntity.ok(ApiResponse.success(saved, "Write-off rate saved"));
    }
}
