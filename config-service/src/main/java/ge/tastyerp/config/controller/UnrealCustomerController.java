package ge.tastyerp.config.controller;

import ge.tastyerp.common.dto.ApiResponse;
import ge.tastyerp.config.service.UnrealCustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Shared "unreal / exception" customer set — customers RS.ge issues waybills for
 * but who are not real business partners. Controller only delegates.
 */
@RestController
@RequestMapping("/api/config/unreal-customers")
@RequiredArgsConstructor
@Tag(name = "Unreal Customers", description = "Shared unreal/exception customer set for Audit Control")
public class UnrealCustomerController {

    private final UnrealCustomerService service;

    @GetMapping
    @Operation(summary = "List unreal customer ids (canonical)")
    public ResponseEntity<ApiResponse<List<String>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success(service.getAll()));
    }

    @PutMapping
    @Operation(summary = "Mark a customer as unreal")
    public ResponseEntity<ApiResponse<List<String>>> add(@RequestParam("customerId") String customerId) {
        return ResponseEntity.ok(ApiResponse.success(service.add(customerId), "Marked unreal"));
    }

    @DeleteMapping
    @Operation(summary = "Un-mark a customer (back to real)")
    public ResponseEntity<ApiResponse<List<String>>> remove(@RequestParam("customerId") String customerId) {
        return ResponseEntity.ok(ApiResponse.success(service.remove(customerId), "Marked real"));
    }
}
