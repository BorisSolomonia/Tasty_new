package ge.tastyerp.config.controller;

import ge.tastyerp.common.dto.ApiResponse;
import ge.tastyerp.config.service.ExcludedCustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Shared "exclude from debt total" customer set (device-consistent replacement
 * for the old per-browser localStorage set). Controller only delegates.
 */
@RestController
@RequestMapping("/api/config/excluded-customers")
@RequiredArgsConstructor
@Tag(name = "Excluded Customers", description = "Shared exclude-from-total set")
public class ExcludedCustomerController {

    private final ExcludedCustomerService service;

    @GetMapping
    @Operation(summary = "List excluded customer ids (canonical)")
    public ResponseEntity<ApiResponse<List<String>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success(service.getAll()));
    }

    @PutMapping
    @Operation(summary = "Add a customer to the exclude set")
    public ResponseEntity<ApiResponse<List<String>>> add(@RequestParam("customerId") String customerId) {
        return ResponseEntity.ok(ApiResponse.success(service.add(customerId), "Excluded"));
    }

    @DeleteMapping
    @Operation(summary = "Remove a customer from the exclude set")
    public ResponseEntity<ApiResponse<List<String>>> remove(@RequestParam("customerId") String customerId) {
        return ResponseEntity.ok(ApiResponse.success(service.remove(customerId), "Un-excluded"));
    }
}
