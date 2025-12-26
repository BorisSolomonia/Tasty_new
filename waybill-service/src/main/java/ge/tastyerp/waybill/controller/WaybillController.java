package ge.tastyerp.waybill.controller;

import ge.tastyerp.common.dto.ApiResponse;
import ge.tastyerp.common.dto.waybill.WaybillDto;
import ge.tastyerp.common.dto.waybill.WaybillFetchRequest;
import ge.tastyerp.common.dto.waybill.WaybillFetchResponse;
import ge.tastyerp.common.dto.waybill.WaybillType;
import ge.tastyerp.common.dto.waybill.WaybillVatSummaryDto;
import ge.tastyerp.waybill.service.WaybillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST Controller for waybill operations.
 *
 * IMPORTANT: Controllers contain NO business logic.
 * All logic is delegated to WaybillService.
 */
@RestController
@RequestMapping("/api/waybills")
@RequiredArgsConstructor
@Tag(name = "Waybills", description = "Waybill management and RS.ge integration")
@Slf4j
public class WaybillController {

    private final WaybillService waybillService;

    @PostMapping("/fetch")
    @Operation(summary = "Fetch waybills from RS.ge API")
    public ResponseEntity<ApiResponse<WaybillFetchResponse>> fetchWaybills(
            @Valid @RequestBody WaybillFetchRequest request) {

        log.info("HTTP POST /api/waybills/fetch startDate={} endDate={}", request.getStartDate(), request.getEndDate());
        WaybillFetchResponse response = waybillService.fetchWaybillsFromRsGe(request);
        return ResponseEntity.ok(ApiResponse.success(response, response.getMessage()));
    }

    @PostMapping("/purchase/fetch")
    @Operation(summary = "Fetch purchase (buyer) waybills from RS.ge API")
    public ResponseEntity<ApiResponse<WaybillFetchResponse>> fetchPurchaseWaybills(
            @Valid @RequestBody WaybillFetchRequest request) {

        log.info("HTTP POST /api/waybills/purchase/fetch startDate={} endDate={}", request.getStartDate(), request.getEndDate());
        WaybillFetchResponse response = waybillService.fetchPurchaseWaybillsFromRsGe(request);
        return ResponseEntity.ok(ApiResponse.success(response, response.getMessage()));
    }

    @GetMapping("/sales/all")
    @Operation(summary = "Get ALL sale waybills from RS.ge for aggregation")
    public ResponseEntity<List<WaybillDto>> getAllSalesWaybills() {
        log.info("HTTP GET /api/waybills/sales/all");
        List<WaybillDto> waybills = waybillService.getAllSalesWaybills();
        log.info("HTTP GET /api/waybills/sales/all -> {} records", waybills.size());
        return ResponseEntity.ok(waybills);
    }

    @GetMapping
    @Operation(summary = "Get all waybills with optional filters (DEPRECATED - uses Firebase)")
    public ResponseEntity<ApiResponse<List<WaybillDto>>> getAllWaybills(
            @RequestParam(required = false) String customerId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) Boolean afterCutoffOnly,
            @RequestParam(required = false) WaybillType type) {

        log.info("HTTP GET /api/waybills customerId={} startDate={} endDate={} afterCutoffOnly={} type={}",
                customerId, startDate, endDate, afterCutoffOnly, type);
        List<WaybillDto> waybills = waybillService.getWaybills(customerId, startDate, endDate, afterCutoffOnly, type);
        log.info("HTTP GET /api/waybills -> {} records", waybills.size());
        return ResponseEntity.ok(ApiResponse.success(waybills));
    }

    @GetMapping("/stats")
    @Operation(summary = "Get waybill statistics")
    public ResponseEntity<ApiResponse<Object>> getWaybillStats() {
        Object stats = waybillService.getWaybillStatistics();
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @GetMapping("/vat")
    @Operation(summary = "Get sold vs purchased VAT summary (legacy rules, backend-calculated)")
    public ResponseEntity<ApiResponse<WaybillVatSummaryDto>> getVatSummary(
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(required = false) Boolean afterCutoffOnly) {

        log.info("HTTP GET /api/waybills/vat startDate={} endDate={} afterCutoffOnly={}",
                startDate, endDate, afterCutoffOnly);
        WaybillVatSummaryDto summary = waybillService.getVatSummary(startDate, endDate, afterCutoffOnly);
        return ResponseEntity.ok(ApiResponse.success(summary));
    }

    @GetMapping("/customer/{customerId}")
    @Operation(summary = "Get all waybills for a customer")
    public ResponseEntity<ApiResponse<List<WaybillDto>>> getCustomerWaybills(
            @PathVariable String customerId,
            @RequestParam(required = false, defaultValue = "true") Boolean afterCutoffOnly) {

        List<WaybillDto> waybills = waybillService.getWaybillsByCustomer(customerId, afterCutoffOnly);
        return ResponseEntity.ok(ApiResponse.success(waybills));
    }

    @GetMapping("/customer/{customerId}/total")
    @Operation(summary = "Get total sales amount for a customer")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCustomerTotal(
            @PathVariable String customerId) {

        Map<String, Object> total = waybillService.getCustomerTotalSales(customerId);
        return ResponseEntity.ok(ApiResponse.success(total));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get waybill by ID")
    public ResponseEntity<ApiResponse<WaybillDto>> getWaybillById(@PathVariable String id) {
        WaybillDto waybill = waybillService.getWaybillById(id);
        return ResponseEntity.ok(ApiResponse.success(waybill));
    }
}
