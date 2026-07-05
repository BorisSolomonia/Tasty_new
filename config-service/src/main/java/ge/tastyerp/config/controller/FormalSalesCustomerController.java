package ge.tastyerp.config.controller;

import ge.tastyerp.common.dto.ApiResponse;
import ge.tastyerp.common.dto.config.FormalSalesCustomerDto;
import ge.tastyerp.config.service.FormalSalesCustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Manage the formal-sales customer set (documentation-only customers earning a
 * per-kg commission — BOR-76 Part 3). Controller only delegates.
 */
@RestController
@RequestMapping("/api/config/formal-sales-customers")
@RequiredArgsConstructor
@Tag(name = "Formal Sales Customers", description = "Documentation-only customers with per-kg commission")
public class FormalSalesCustomerController {

    private final FormalSalesCustomerService service;

    @GetMapping
    @Operation(summary = "List formal-sales customers")
    public ResponseEntity<ApiResponse<List<FormalSalesCustomerDto>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success(service.getAll()));
    }

    @PutMapping
    @Operation(summary = "Add or update a formal-sales customer (with commission GEL/kg)")
    public ResponseEntity<ApiResponse<FormalSalesCustomerDto>> setCustomer(@RequestBody FormalSalesCustomerDto request) {
        return ResponseEntity.ok(ApiResponse.success(service.setCustomer(request), "Formal-sales customer saved"));
    }

    @DeleteMapping
    @Operation(summary = "Remove a formal-sales customer")
    public ResponseEntity<ApiResponse<List<FormalSalesCustomerDto>>> remove(@RequestParam("customerId") String customerId) {
        return ResponseEntity.ok(ApiResponse.success(service.remove(customerId), "Removed"));
    }
}
