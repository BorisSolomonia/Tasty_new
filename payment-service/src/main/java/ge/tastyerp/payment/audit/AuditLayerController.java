package ge.tastyerp.payment.audit;

import ge.tastyerp.common.dto.ApiResponse;
import ge.tastyerp.common.dto.auditlayer.AuditCategoryDto;
import ge.tastyerp.common.dto.auditlayer.AuditChangeLogDto;
import ge.tastyerp.common.dto.auditlayer.AuditDrilldownDto;
import ge.tastyerp.common.dto.auditlayer.AuditFlowsDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingRuleDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingStatus;
import ge.tastyerp.common.dto.auditlayer.AuditSourceRowPageDto;
import ge.tastyerp.common.dto.auditlayer.AuditSourceType;
import ge.tastyerp.common.dto.auditlayer.CheckEvidenceDto;
import ge.tastyerp.common.dto.auditlayer.CounterpartyAliasDto;
import ge.tastyerp.common.dto.auditlayer.RealInventoryOverrideDto;
import ge.tastyerp.common.dto.auditlayer.RealSupplierDebtDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

/**
 * The BOR-89 audit layer.
 *
 * <p>Mounted under the existing {@code /api/audit-control} prefix, which already
 * routes to payment-service in the production reverse proxy, rather than claiming
 * a new top-level path that would need routing changes in three places.</p>
 *
 * <p>Controllers hold no business logic — everything is delegated, matching the
 * convention set by {@code AuditControlController}.</p>
 */
@RestController
@RequestMapping("/api/audit-control/layer")
@RequiredArgsConstructor
@Tag(name = "Audit Layer",
        description = "Three-flow audit: inventory, cash and documentation reconciliation")
public class AuditLayerController {

    private final AuditFlowService flowService;
    private final AuditMappingService mappingService;
    private final AuditOverrideService overrideService;
    private final AuditDrilldownService drilldownService;
    private final AuditSourceRowService sourceRowService;
    private final BankStatementMirrorService bankStatementMirrorService;
    private final AuditSuggestionEngine suggestionEngine;
    private final AuditMappingRuleService mappingRuleService;
    private final AuditConfigClient configClient;

    // ==================== canonical payload ====================

    @GetMapping("/flows")
    @Operation(summary = "The canonical three-flow payload every UX variant renders")
    public ResponseEntity<ApiResponse<AuditFlowsDto>> getFlows(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String product) {
        return ResponseEntity.ok(ApiResponse.success(
                flowService.getFlows(startDate, endDate, product)));
    }

    @GetMapping("/source-rows")
    @Operation(summary = "Individually mappable source rows, with an explicit truncation report")
    public ResponseEntity<ApiResponse<AuditSourceRowPageDto>> getSourceRows(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) AuditSourceType sourceType,
            @RequestParam(required = false) AuditMappingStatus status,
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "500") int limit) {
        return ResponseEntity.ok(ApiResponse.success(
                sourceRowService.query(startDate, endDate, sourceType, status, search, limit)));
    }

    @GetMapping("/drilldown")
    @Operation(summary = "Expand one aggregate into the exact source rows composing it")
    public ResponseEntity<ApiResponse<AuditDrilldownDto>> drilldown(
            @RequestParam String key,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String subject) {
        return ResponseEntity.ok(ApiResponse.success(
                drilldownService.expand(key, startDate, endDate, subject)));
    }

    @PostMapping(value = "/import-statement", consumes = "multipart/form-data")
    @Operation(summary = "Backfill the audit layer from a bank statement "
            + "(mirrors both directions into bankTransactions; never writes payments)")
    public ResponseEntity<ApiResponse<BankStatementMirrorService.MirrorResult>> importStatement(
            @RequestParam("file") MultipartFile file,
            @RequestParam String bank,
            @RequestParam String operator) {
        BankStatementMirrorService.MirrorResult result =
                bankStatementMirrorService.mirrorUpload(file, bank);
        mappingService.log(operator, "STATEMENT_IMPORT",
                result.bank() + "|" + result.sheetName(), "mirroredRows",
                null, String.valueOf(result.mirroredRows()),
                "Audit backfill from " + file.getOriginalFilename());
        return ResponseEntity.ok(ApiResponse.success(result,
                "Mirrored " + result.mirroredRows() + " statement rows"));
    }

    @PostMapping("/suggest-mappings")
    @Operation(summary = "Run the suggestion engine over unmapped bank rows "
            + "(suggestions count toward nothing until accepted)")
    public ResponseEntity<ApiResponse<java.util.Map<String, Integer>>> suggestMappings(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam String operator,
            @RequestParam(required = false, defaultValue = "false") boolean replaceAutomatic) {
        java.util.Map<String, AuditMappingDto> index = mappingService.loadMappingIndex();
        AuditSupplierRegistry suppliers = AuditSupplierRegistry.from(
                sourceRowService.loadProductMovements(startDate, endDate));
        java.util.Map<String, Integer> counts = mappingService.applySuggestions(
                sourceRowService.loadBankRows(startDate, endDate, index),
                suggestionEngine, suppliers, operator, replaceAutomatic);
        return ResponseEntity.ok(ApiResponse.success(counts, "Suggestions applied"));
    }

    // ==================== reusable mapping rules (BOR-91) ====================

    @GetMapping("/mappings/rules/preview")
    @Operation(summary = "What each similarity criterion would catch, shown before the "
            + "user commits to a reusable mapping")
    public ResponseEntity<ApiResponse<List<AuditMappingRuleDto.Preview>>> previewRule(
            @RequestParam String sourceRowId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(ApiResponse.success(
                mappingRuleService.preview(sourceRowId, startDate, endDate)));
    }

    @GetMapping("/mappings/rules")
    @Operation(summary = "Saved reusable mappings, so they can be reviewed and changed later")
    public ResponseEntity<ApiResponse<List<AuditMappingRuleDto>>> getRules() {
        return ResponseEntity.ok(ApiResponse.success(mappingRuleService.getRules()));
    }

    @PutMapping("/mappings/rules")
    @Operation(summary = "Create or edit a reusable mapping and apply it to matching rows")
    public ResponseEntity<ApiResponse<AuditMappingRuleDto>> saveRule(
            @RequestBody AuditMappingRuleDto rule,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam String operator) {
        AuditMappingRuleDto saved = mappingRuleService.saveRule(rule, startDate, endDate, operator);
        return ResponseEntity.ok(ApiResponse.success(saved,
                "Rule saved and applied to " + saved.getAppliedCount() + " transactions"));
    }

    @DeleteMapping("/mappings/rules/{id}")
    @Operation(summary = "Withdraw a reusable mapping and un-map exactly what it created")
    public ResponseEntity<ApiResponse<Integer>> revokeRule(
            @PathVariable String id,
            @RequestParam String operator,
            @RequestParam(required = false) String reason) {
        int undone = mappingRuleService.revokeRule(id, operator, reason);
        return ResponseEntity.ok(ApiResponse.success(undone,
                "Rule withdrawn; " + undone + " mappings un-mapped"));
    }

    @GetMapping("/product-categories")
    @Operation(summary = "Categories a product line can belong to (the inventory vocabulary)")
    public ResponseEntity<ApiResponse<List<String>>> getProductCategories() {
        return ResponseEntity.ok(ApiResponse.success(configClient.productCategories()));
    }

    @PutMapping("/product-categories")
    @Operation(summary = "Set a product's category in the shared store /audit-control uses. "
            + "Applies to the product everywhere, not to one document row.")
    public ResponseEntity<ApiResponse<Void>> setProductCategory(
            @RequestParam String productName,
            @RequestParam String category,
            @RequestParam String operator) {
        if (!ge.tastyerp.common.dto.audit.ProductHierarchy.isValidCategory(category)) {
            throw new ge.tastyerp.common.exception.ValidationException("category",
                    "Unknown product category '" + category + "'");
        }
        configClient.setProductCategory(productName, category);
        mappingService.log(operator, "PRODUCT_CATEGORY", productName, "category",
                null, category, "Applies to this product everywhere");
        return ResponseEntity.ok(ApiResponse.success(null,
                "'" + productName + "' is now " + category + " everywhere"));
    }

    // ==================== categories ====================

    @GetMapping("/categories")
    @Operation(summary = "Built-in and user-created mapping categories")
    public ResponseEntity<ApiResponse<List<AuditCategoryDto>>> getCategories() {
        return ResponseEntity.ok(ApiResponse.success(mappingService.getCategories()));
    }

    @PostMapping("/categories")
    @Operation(summary = "Create a custom mapping category")
    public ResponseEntity<ApiResponse<AuditCategoryDto>> saveCategory(
            @RequestBody AuditCategoryDto category,
            @RequestParam String operator) {
        return ResponseEntity.ok(ApiResponse.success(
                mappingService.saveCategory(category, operator), "Category saved"));
    }

    @DeleteMapping("/categories/{code}")
    @Operation(summary = "Delete a custom mapping category")
    public ResponseEntity<ApiResponse<Void>> deleteCategory(
            @PathVariable String code,
            @RequestParam String operator) {
        mappingService.deleteCategory(code, operator);
        return ResponseEntity.ok(ApiResponse.success(null, "Category deleted"));
    }

    // ==================== mappings ====================

    @PutMapping("/mappings")
    @Operation(summary = "Create or replace the mapping for one source row")
    public ResponseEntity<ApiResponse<AuditMappingDto>> saveMapping(
            @RequestBody AuditMappingDto mapping,
            @RequestParam String operator) {
        return ResponseEntity.ok(ApiResponse.success(
                mappingService.saveMapping(mapping, operator), "Mapping saved"));
    }

    @DeleteMapping("/mappings/{id}")
    @Operation(summary = "Void a mapping, retaining its history")
    public ResponseEntity<ApiResponse<Void>> voidMapping(
            @PathVariable String id,
            @RequestParam String operator,
            @RequestParam(required = false) String reason) {
        mappingService.voidMapping(id, operator, reason);
        return ResponseEntity.ok(ApiResponse.success(null, "Mapping voided"));
    }

    @GetMapping("/mappings/{sourceType}/{sourceRowId}/history")
    @Operation(summary = "Permanent change history for one source row")
    public ResponseEntity<ApiResponse<List<AuditChangeLogDto>>> getMappingHistory(
            @PathVariable AuditSourceType sourceType,
            @PathVariable String sourceRowId) {
        return ResponseEntity.ok(ApiResponse.success(
                mappingService.getHistory(sourceType, sourceRowId)));
    }

    // ==================== manual reality inputs ====================

    @GetMapping("/real-inventory")
    @Operation(summary = "Manually confirmed real on-hand inventory")
    public ResponseEntity<ApiResponse<List<RealInventoryOverrideDto>>> getRealInventory() {
        return ResponseEntity.ok(ApiResponse.success(overrideService.getRealInventory()));
    }

    @PutMapping("/real-inventory")
    @Operation(summary = "Confirm real on-hand inventory for a product on a date")
    public ResponseEntity<ApiResponse<RealInventoryOverrideDto>> saveRealInventory(
            @RequestBody RealInventoryOverrideDto override,
            @RequestParam String operator) {
        return ResponseEntity.ok(ApiResponse.success(
                overrideService.saveRealInventory(override, operator), "Real inventory saved"));
    }

    @DeleteMapping("/real-inventory/{id}")
    @Operation(summary = "Withdraw a real-inventory confirmation "
            + "(different from confirming zero — this returns the product to unconfirmed)")
    public ResponseEntity<ApiResponse<Void>> deleteRealInventory(
            @PathVariable String id,
            @RequestParam String operator,
            @RequestParam(required = false) String reason) {
        overrideService.deleteRealInventory(id, operator, reason);
        return ResponseEntity.ok(ApiResponse.success(null, "Real-inventory confirmation withdrawn"));
    }

    @GetMapping("/counterparty-aliases")
    @Operation(summary = "Name-to-tax-code links used when a statement names a "
            + "counterparty without numbering it")
    public ResponseEntity<ApiResponse<List<CounterpartyAliasDto>>> getCounterpartyAliases() {
        return ResponseEntity.ok(ApiResponse.success(overrideService.getCounterpartyAliases()));
    }

    @PutMapping("/counterparty-aliases")
    @Operation(summary = "Teach the audit layer that a counterparty name belongs to a tax code")
    public ResponseEntity<ApiResponse<CounterpartyAliasDto>> saveCounterpartyAlias(
            @RequestBody CounterpartyAliasDto alias,
            @RequestParam String operator) {
        return ResponseEntity.ok(ApiResponse.success(
                overrideService.saveCounterpartyAlias(alias, operator), "Alias saved"));
    }

    @DeleteMapping("/counterparty-aliases/{id}")
    @Operation(summary = "Remove a counterparty alias")
    public ResponseEntity<ApiResponse<Void>> deleteCounterpartyAlias(
            @PathVariable String id,
            @RequestParam String operator,
            @RequestParam(required = false) String reason) {
        overrideService.deleteCounterpartyAlias(id, operator, reason);
        return ResponseEntity.ok(ApiResponse.success(null, "Alias removed"));
    }

    @GetMapping("/supplier-debt")
    @Operation(summary = "Manually maintained real outstanding supplier debt")
    public ResponseEntity<ApiResponse<List<RealSupplierDebtDto>>> getSupplierDebts() {
        return ResponseEntity.ok(ApiResponse.success(overrideService.getSupplierDebts()));
    }

    @PutMapping("/supplier-debt")
    @Operation(summary = "Set real outstanding debt for a supplier")
    public ResponseEntity<ApiResponse<RealSupplierDebtDto>> saveSupplierDebt(
            @RequestBody RealSupplierDebtDto debt,
            @RequestParam String operator) {
        return ResponseEntity.ok(ApiResponse.success(
                overrideService.saveSupplierDebt(debt, operator), "Supplier debt saved"));
    }

    @GetMapping("/check-evidence")
    @Operation(summary = "Checks and payment evidence held for counterparties")
    public ResponseEntity<ApiResponse<List<CheckEvidenceDto>>> getCheckEvidence(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(ApiResponse.success(
                overrideService.getCheckEvidence(startDate, endDate)));
    }

    @PutMapping("/check-evidence")
    @Operation(summary = "Record or amend payment evidence")
    public ResponseEntity<ApiResponse<CheckEvidenceDto>> saveCheckEvidence(
            @RequestBody CheckEvidenceDto evidence,
            @RequestParam String operator) {
        return ResponseEntity.ok(ApiResponse.success(
                overrideService.saveCheckEvidence(evidence, operator), "Payment evidence saved"));
    }

    @DeleteMapping("/check-evidence/{id}")
    @Operation(summary = "Remove payment evidence")
    public ResponseEntity<ApiResponse<Void>> deleteCheckEvidence(
            @PathVariable String id,
            @RequestParam String operator,
            @RequestParam(required = false) String reason) {
        overrideService.deleteCheckEvidence(id, operator, reason);
        return ResponseEntity.ok(ApiResponse.success(null, "Payment evidence deleted"));
    }

    // ==================== history ====================

    @GetMapping("/change-log")
    @Operation(summary = "Permanent audit change log")
    public ResponseEntity<ApiResponse<List<AuditChangeLogDto>>> getChangeLog(
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false, defaultValue = "200") int limit) {
        return ResponseEntity.ok(ApiResponse.success(
                mappingService.getChangeLog(entityId, limit)));
    }
}
