package ge.tastyerp.config.controller;

import ge.tastyerp.common.dto.ApiResponse;
import ge.tastyerp.common.dto.config.ProductCategoryDto;
import ge.tastyerp.config.service.ProductCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Manage product-name -> category overrides used by the Audit Control page.
 * Controller only delegates to the service (no business logic).
 */
@RestController
@RequestMapping("/api/config/product-categories")
@RequiredArgsConstructor
@Tag(name = "Product Categories", description = "Editable product-to-category mapping for audit")
public class ProductCategoryController {

    private final ProductCategoryService service;

    @GetMapping
    @Operation(summary = "Get all product category overrides")
    public ResponseEntity<ApiResponse<List<ProductCategoryDto>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success(service.getAll()));
    }

    @PutMapping
    @Operation(summary = "Upsert a product's category (one category per name)")
    public ResponseEntity<ApiResponse<ProductCategoryDto>> setCategory(@RequestBody ProductCategoryDto request) {
        ProductCategoryDto saved = service.setCategory(request.getName(), request.getCategory());
        return ResponseEntity.ok(ApiResponse.success(saved, "Category saved"));
    }

    @DeleteMapping
    @Operation(summary = "Remove a product override (revert to auto-classification)")
    public ResponseEntity<ApiResponse<Void>> deleteCategory(@RequestParam("name") String name) {
        service.deleteCategory(name);
        return ResponseEntity.ok(ApiResponse.success(null, "Override removed"));
    }
}
