package ge.tastyerp.config.controller;

import ge.tastyerp.common.dto.ApiResponse;
import ge.tastyerp.config.service.SettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/config/product-sales-customers")
@RequiredArgsConstructor
@Tag(name = "Product Sales Customers", description = "Manage product-sales page customer list")
public class ProductSalesCustomerController {

    private final SettingsService settingsService;

    @GetMapping
    @Operation(summary = "Get product sales customer list")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getCustomers() {
        List<Map<String, Object>> customers = settingsService.getProductSalesCustomers();
        return ResponseEntity.ok(ApiResponse.success(customers));
    }

    @SuppressWarnings("unchecked")
    @PutMapping
    @Operation(summary = "Save product sales customer list")
    public ResponseEntity<ApiResponse<Void>> saveCustomers(@RequestBody List<Map<String, Object>> customers) {
        settingsService.saveProductSalesCustomers(customers);
        return ResponseEntity.ok(ApiResponse.success(null, "Product sales customers saved"));
    }
}
