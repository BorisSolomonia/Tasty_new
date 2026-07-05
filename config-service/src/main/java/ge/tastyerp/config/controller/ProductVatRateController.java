package ge.tastyerp.config.controller;

import ge.tastyerp.common.dto.ApiResponse;
import ge.tastyerp.common.dto.config.ProductVatRateDto;
import ge.tastyerp.config.service.ProductVatRateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Manage per-product VAT-rate overrides used by the Audit Control VAT module.
 * Products default to 18%; override per product on the Product Categories page.
 * Controller only delegates to the service (no business logic).
 */
@RestController
@RequestMapping("/api/config/product-vat-rates")
@RequiredArgsConstructor
@Tag(name = "Product VAT Rates", description = "Editable per-product VAT percentage (default 18)")
public class ProductVatRateController {

    private final ProductVatRateService service;

    @GetMapping
    @Operation(summary = "Get all per-product VAT-rate overrides")
    public ResponseEntity<ApiResponse<List<ProductVatRateDto>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success(service.getAll()));
    }

    @PutMapping
    @Operation(summary = "Upsert a product's VAT rate (one rate per name)")
    public ResponseEntity<ApiResponse<ProductVatRateDto>> setRate(@RequestBody ProductVatRateDto request) {
        ProductVatRateDto saved = service.setRate(request.getName(), request.getPercent());
        return ResponseEntity.ok(ApiResponse.success(saved, "VAT rate saved"));
    }

    @DeleteMapping
    @Operation(summary = "Remove a product VAT override (revert to 18% default)")
    public ResponseEntity<ApiResponse<Void>> deleteRate(@RequestParam("name") String name) {
        service.deleteRate(name);
        return ResponseEntity.ok(ApiResponse.success(null, "VAT override removed"));
    }
}
