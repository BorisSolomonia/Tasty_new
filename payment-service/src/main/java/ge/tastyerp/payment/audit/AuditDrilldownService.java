package ge.tastyerp.payment.audit;

import ge.tastyerp.common.dto.audit.ProductMovementDto;
import ge.tastyerp.common.dto.auditlayer.AuditDrilldownDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingSplitDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingStatus;
import ge.tastyerp.common.dto.auditlayer.AuditSourceRowDto;
import ge.tastyerp.common.dto.auditlayer.AuditSourceType;
import ge.tastyerp.common.dto.auditlayer.CheckEvidenceDto;
import ge.tastyerp.common.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Expands one aggregate into the exact source rows that compose it (BOR-89 §11).
 *
 * <p>The chain the ticket asks for — {@code total → subgroup → source rows →
 * individual record → mapping/history} — terminates here. Each response carries
 * a plain-language {@code definition} of the set, because a drill-down whose
 * membership rule is invisible cannot be checked.</p>
 *
 * <p>{@code total} is recomputed from the returned rows rather than copied from
 * the headline figure, so a drill-down that fails to add up shows it.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditDrilldownService {

    private final AuditSourceRowService sourceRowService;
    private final AuditMappingService mappingService;
    private final AuditLayerRepository repository;

    public AuditDrilldownDto expand(String key, LocalDate startDate, LocalDate endDate, String subject) {
        if (key == null || key.isBlank()) {
            throw new ValidationException("key", "A drill-down key is required");
        }
        Map<String, AuditMappingDto> mappings = mappingService.loadMappingIndex();

        return switch (key) {
            case "cash.unresolvedWithdrawals" -> bank(key, startDate, endDate, mappings,
                    "Unresolved bank outflow",
                    "Money-out rows whose split allocations do not cover the full row amount. "
                            + "An outflow nobody has classified stays here rather than being "
                            + "assumed to be a supplier payment.",
                    row -> isDebit(row) && positive(row.getUnresolvedAmount()),
                    AuditSourceRowDto::getUnresolvedAmount);

            case "cash.unmappedInflows" -> bank(key, startDate, endDate, mappings,
                    "Unmapped bank inflow",
                    "Money-in rows whose split allocations do not cover the full row amount.",
                    row -> !isDebit(row) && positive(row.getUnresolvedAmount()),
                    AuditSourceRowDto::getUnresolvedAmount);

            case "cash.supplierSettlement" -> bank(key, startDate, endDate, mappings,
                    "Supplier settlement",
                    "Bank rows carrying at least one split in a category flagged as supplier "
                            + "settlement. Only these count toward the coverage control — total "
                            + "withdrawals deliberately do not.",
                    row -> hasSupplierSettlement(row),
                    AuditSourceRowDto::getAmount);

            case "cash.bankRows" -> bank(key, startDate, endDate, mappings,
                    "All bank statement rows",
                    "Every imported statement row in the period, mapped or not.",
                    row -> true,
                    AuditSourceRowDto::getAmount);

            case "cash.paperCash", "documentation.paperOnlySales", "documentation.paperOnlyReceipts" ->
                    documents(key, startDate, endDate, mappings,
                            "Paper-only documentation",
                            "Document rows a person classified as having no real transaction "
                                    + "behind them. These create or remove accounting cash without "
                                    + "money moving.",
                            row -> hasCategory(row, paperCategoriesFor(key)));

            case "documentation.unmapped" -> documents(key, startDate, endDate, mappings,
                    "Unmapped RS.ge document rows",
                    "Document rows nobody has classified yet.",
                    row -> row.getStatus() == AuditMappingStatus.UNMAPPED);

            case "documentation.rows" -> documents(key, startDate, endDate, mappings,
                    "All RS.ge document rows",
                    "Every document line in the period.",
                    row -> true);

            case "inventory.positiveGap" -> documents(key, startDate, endDate, mappings,
                    "Documents behind a positive inventory gap",
                    "Document rows for products whose document stock exceeds confirmed real "
                            + "stock." + subjectSuffix(subject),
                    row -> matchesProduct(row, subject));

            case "inventory.negativeGap" -> documents(key, startDate, endDate, mappings,
                    "Documents behind a negative inventory gap",
                    "Document rows for products whose sales and write-offs exceed documented "
                            + "purchases." + subjectSuffix(subject),
                    row -> matchesProduct(row, subject));

            case "cash.unsupportedChecks" -> checks(key, startDate, endDate, mappings, false);

            // BOR-91: one dedicated set per alert. Three alerts previously shared
            // cash.supplierSettlement, which is none of their record sets — a
            // drill-down that answers a different question than the one clicked
            // is worse than no drill-down.
            case "cash.checksUnclassified" -> checks(key, startDate, endDate, mappings, true);

            case "cash.paidWithoutDocumentation" -> {
                AuditSupplierRegistry suppliers = AuditSupplierRegistry.from(
                        sourceRowService.loadProductMovements(startDate, endDate));
                yield bank(key, startDate, endDate, mappings,
                        "Paid to counterparties with no purchase documentation",
                        "Money-out rows whose counterparty never appears as a seller on any "
                                + "RS.ge purchase document. These cannot be supplier settlements "
                                + "on the evidence available.",
                        row -> isDebit(row) && identified(row)
                                && !suppliers.isDocumentedSupplier(identityOf(row)),
                        AuditSourceRowDto::getAmount);
            }

            case "cash.outflowWithoutCounterparty" -> bank(key, startDate, endDate, mappings,
                    "Outflow with no identifiable counterparty",
                    "Money-out rows where the statement printed no tax code and the name "
                            + "could not be resolved to one — ATM withdrawals and similar.",
                    row -> isDebit(row) && !identified(row),
                    AuditSourceRowDto::getAmount);

            case "cash.supplierNeverPaid" -> {
                List<ProductMovementDto> movements =
                        sourceRowService.loadProductMovements(startDate, endDate);
                Set<String> paidTins = new HashSet<>();
                for (AuditSourceRowDto row : sourceRowService.loadBankRows(startDate, endDate, mappings)) {
                    if (isDebit(row) && identified(row)) {
                        paidTins.add(identityOf(row));
                    }
                }
                yield documentsFrom(key, movements, mappings,
                        "Documented suppliers never paid by bank",
                        "Purchase-document rows from counterparties that received no bank "
                                + "payment at all in this period.",
                        row -> row.getCounterpartyTin() != null
                                && !paidTins.contains(row.getCounterpartyTin().trim())
                                && "PURCHASE".equals(row.getDirection()));
            }

            case "cash.uncoveredPurchase" -> documents(key, startDate, endDate, mappings,
                    "Documented purchases behind the uncovered balance",
                    "Every RS.ge purchase-document row in the period. The uncovered balance is "
                            + "what remains of these after supplier settlement and real debt.",
                    row -> "PURCHASE".equals(row.getDirection()));

            default -> throw new ValidationException("key", "Unknown drill-down key: " + key);
        };
    }

    // ==================== builders ====================

    private interface AmountOf {
        BigDecimal of(AuditSourceRowDto row);
    }

    private AuditDrilldownDto bank(String key, LocalDate startDate, LocalDate endDate,
                                   Map<String, AuditMappingDto> mappings,
                                   String label, String definition,
                                   Predicate<AuditSourceRowDto> filter, AmountOf amountOf) {
        List<AuditSourceRowDto> rows = new ArrayList<>();
        for (AuditSourceRowDto row : sourceRowService.loadBankRows(startDate, endDate, mappings)) {
            if (filter.test(row)) {
                rows.add(row);
            }
        }
        return finish(key, label, definition, rows, amountOf);
    }

    private AuditDrilldownDto documentsFrom(String key, List<ProductMovementDto> movements,
                                            Map<String, AuditMappingDto> mappings,
                                            String label, String definition,
                                            Predicate<AuditSourceRowDto> filter) {
        List<AuditSourceRowDto> rows = new ArrayList<>();
        for (AuditSourceRowDto row : sourceRowService.toDocumentRows(movements, mappings)) {
            if (filter.test(row)) {
                rows.add(row);
            }
        }
        return finish(key, label, definition, rows, AuditSourceRowDto::getAmount);
    }

    private AuditDrilldownDto documents(String key, LocalDate startDate, LocalDate endDate,
                                        Map<String, AuditMappingDto> mappings,
                                        String label, String definition,
                                        Predicate<AuditSourceRowDto> filter) {
        List<ProductMovementDto> movements = sourceRowService.loadProductMovements(startDate, endDate);
        List<AuditSourceRowDto> rows = new ArrayList<>();
        for (AuditSourceRowDto row : sourceRowService.toDocumentRows(movements, mappings)) {
            if (filter.test(row)) {
                rows.add(row);
            }
        }
        return finish(key, label, definition, rows, AuditSourceRowDto::getAmount);
    }

    /**
     * Check evidence is not a bank row and never becomes one. It is surfaced as
     * {@link AuditSourceType#CHECK} so the drill-down can show it beside real
     * money without implying the two are the same thing.
     */
    private AuditDrilldownDto checks(String key, LocalDate startDate, LocalDate endDate,
                                     Map<String, AuditMappingDto> mappings,
                                     boolean unclassifiedOnly) {
        List<AuditSourceRowDto> rows = new ArrayList<>();
        for (CheckEvidenceDto check : repository.findCheckEvidence()) {
            if (check.getDate() != null
                    && (check.getDate().isBefore(startDate) || check.getDate().isAfter(endDate))) {
                continue;
            }
            if (unclassifiedOnly && check.isClassified()) {
                continue;
            }
            rows.add(AuditSourceRowDto.builder()
                    .sourceType(AuditSourceType.CHECK)
                    .sourceRowId(check.getId())
                    .date(check.getDate())
                    .direction("EVIDENCE")
                    .amount(check.getAmount())
                    .counterpartyName(check.getCounterpartyName())
                    .counterpartyTin(check.getCounterpartyTin())
                    .description(check.getExplanation())
                    .reference(check.getDocumentNumber())
                    .status(check.isClassified()
                            ? AuditMappingStatus.MANUALLY_MAPPED : AuditMappingStatus.UNMAPPED)
                    .build());
        }
        return finish(key, "Payment evidence",
                "Checks and receipts held for counterparties. Evidence is never proof that "
                        + "money moved; the supported portion is derived from bank rows linked "
                        + "to each check.",
                rows, AuditSourceRowDto::getAmount);
    }

    private AuditDrilldownDto finish(String key, String label, String definition,
                                     List<AuditSourceRowDto> rows, AmountOf amountOf) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal quantity = BigDecimal.ZERO;
        for (AuditSourceRowDto row : rows) {
            BigDecimal amount = amountOf.of(row);
            if (amount != null) {
                total = total.add(amount.abs());
            }
            if (row.getQuantityKg() != null) {
                quantity = quantity.add(row.getQuantityKg());
            }
        }
        int fullCount = rows.size();
        boolean truncated = fullCount > AuditSourceRowService.MAX_ROWS;
        List<AuditSourceRowDto> page = truncated
                ? new ArrayList<>(rows.subList(0, AuditSourceRowService.MAX_ROWS))
                : rows;
        if (truncated) {
            log.info("Drill-down {} truncated {} rows to {}", key, fullCount,
                    AuditSourceRowService.MAX_ROWS);
        }
        // History is deliberately NOT attached here. Doing so cost one Firestore
        // query per returned row — an N+1 that put a document drill-down at ~135s
        // even over a 1.5-week window. History is per-row detail nobody reads for
        // 2,000 rows at once, so the UI fetches it for the single row it opens via
        // GET /mappings/{sourceType}/{sourceRowId}/history.
        return AuditDrilldownDto.builder()
                .drilldownKey(key)
                .label(label)
                .definition(definition)
                .total(total)
                .totalQuantityKg(quantity)
                .rowCount(fullCount)
                .rows(page)
                .truncated(truncated)
                .build();
    }

    // ==================== predicates ====================

    private static List<String> paperCategoriesFor(String key) {
        return switch (key) {
            case "documentation.paperOnlySales" -> List.of(AuditCategories.PAPER_ONLY_SALE);
            case "documentation.paperOnlyReceipts" -> List.of(AuditCategories.PAPER_ONLY_CUSTOMER_RECEIPT);
            default -> List.of(AuditCategories.PAPER_ONLY_SALE,
                    AuditCategories.PAPER_ONLY_CUSTOMER_RECEIPT,
                    AuditCategories.PAPER_ONLY_SUPPLIER_PAYMENT);
        };
    }

    private static boolean hasCategory(AuditSourceRowDto row, List<String> categoryCodes) {
        for (AuditMappingSplitDto split : AuditMappingService.effectiveSplits(row.getMapping())) {
            if (categoryCodes.contains(split.getCategoryCode())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasSupplierSettlement(AuditSourceRowDto row) {
        Map<String, ge.tastyerp.common.dto.auditlayer.AuditCategoryDto> categories =
                mappingService.categoriesByCode();
        for (AuditMappingSplitDto split : AuditMappingService.effectiveSplits(row.getMapping())) {
            var category = categories.get(split.getCategoryCode());
            if (category != null && category.isSupplierSettlement()) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesProduct(AuditSourceRowDto row, String subject) {
        if (subject == null || subject.isBlank()) {
            return true;
        }
        return row.getProductName() != null
                && row.getProductName().equalsIgnoreCase(subject.trim());
    }

    private static String subjectSuffix(String subject) {
        return subject == null || subject.isBlank() ? "" : " Filtered to '" + subject + "'.";
    }

    private static boolean identified(AuditSourceRowDto row) {
        return identityOf(row) != null;
    }

    private static String identityOf(AuditSourceRowDto row) {
        String t = row.getResolvedCounterpartyTin();
        if (t == null || t.isBlank()) {
            t = row.getCounterpartyTin();
        }
        return t == null || t.isBlank() ? null : t.trim();
    }

    private static boolean isDebit(AuditSourceRowDto row) {
        return row.getDirection() != null && "DEBIT".equalsIgnoreCase(row.getDirection().trim());
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }
}
