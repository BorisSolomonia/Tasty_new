package ge.tastyerp.payment.audit;

import ge.tastyerp.common.dto.audit.ProductMovementDto;
import ge.tastyerp.common.dto.auditlayer.AuditCategoryDto;
import ge.tastyerp.common.dto.auditlayer.AuditFlowsDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingSplitDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingStatus;
import ge.tastyerp.common.dto.auditlayer.AuditSourceRowDto;
import ge.tastyerp.common.dto.auditlayer.AuditSourceType;
import ge.tastyerp.common.dto.auditlayer.CheckEvidenceDto;
import ge.tastyerp.common.dto.auditlayer.CounterpartyIdentitySource;
import ge.tastyerp.common.dto.auditlayer.RealInventoryOverrideDto;
import ge.tastyerp.common.dto.auditlayer.RealSupplierDebtDto;
import ge.tastyerp.common.dto.waybill.WaybillType;
import ge.tastyerp.payment.service.audit.WriteOffCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes the one canonical three-flow payload every UX variant renders
 * (BOR-89 §4, §8, §10).
 *
 * <h3>Where each number comes from</h3>
 * <ul>
 *   <li><b>Inventory</b> — RS.ge document movements from waybill-service, the
 *       existing kg-based {@link WriteOffCalculator} rate, and manually confirmed
 *       real stock. Nothing is re-derived that waybill-service already computes.</li>
 *   <li><b>Cash</b> — bank rows plus their mappings. A bank row contributes only
 *       what a human (or an accepted rule) said it contributes; anything else
 *       stays in the unresolved column where it is visible.</li>
 *   <li><b>Documentation</b> — the same document rows, read for their paper
 *       effects rather than their inventory effects.</li>
 * </ul>
 *
 * <h3>Definitions that are easy to get wrong</h3>
 * <p><b>Cash withdrawals</b> are the cash-side outflow pool: everything paid out
 * that is <em>not</em> a direct bank transfer to a supplier — supplier cash
 * settlements, non-supplier expenses, redeposits, and the still-unresolved
 * remainder. An outflow nobody has classified counts as unresolved, never as a
 * supplier payment (§13).</p>
 *
 * <p><b>Supplier settlement</b> deliberately excludes total withdrawals. Only the
 * portion explicitly allocated to supplier settlement counts, because a
 * withdrawal does not prove a supplier was paid.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditFlowService {

    private static final int MONEY_SCALE = 2;
    private static final int KG_SCALE = 3;

    /** Unit substrings that mark a line as NOT measured in kilograms. */
    private static final List<String> NON_KG_UNITS = List.of(
            "ცალ", "piece", "pcs", "ლიტრ", "liter", "litre",
            "შეკვრ", "კომპლ", "pack", "set", "წყვილ", "გრამ", "gram");

    private final AuditSourceRowService sourceRowService;
    private final AuditMappingService mappingService;
    private final AuditLayerRepository repository;
    private final AuditConfigClient configClient;
    private final WriteOffCalculator writeOffCalculator;
    private final AuditAlertEngine alertEngine;

    /**
     * @param productFilter optional case-insensitive product-name filter; null or
     *                      blank includes everything
     */
    public AuditFlowsDto getFlows(LocalDate startDate, LocalDate endDate, String productFilter) {
        Map<String, AuditMappingDto> mappings = mappingService.loadMappingIndex();
        Map<String, AuditCategoryDto> categories = mappingService.categoriesByCode();

        List<ProductMovementDto> movements = sourceRowService
                .loadProductMovements(startDate, endDate);
        if (productFilter != null && !productFilter.isBlank()) {
            String needle = productFilter.trim().toLowerCase();
            movements = movements.stream()
                    .filter(m -> m.getProductName() != null
                            && m.getProductName().toLowerCase().contains(needle))
                    .toList();
        }

        List<AuditSourceRowDto> bankRows = sourceRowService.loadBankRows(startDate, endDate, mappings);
        List<AuditSourceRowDto> documentRows = sourceRowService.toDocumentRows(movements, mappings);
        List<CheckEvidenceDto> checks = checksInPeriod(startDate, endDate);
        List<RealSupplierDebtDto> debts = repository.findSupplierDebts();

        AuditFlowsDto.Inventory inventory = buildInventory(movements, endDate);
        AuditFlowsDto.Cash cash = buildCash(bankRows, documentRows, checks, debts, categories, movements);
        AuditFlowsDto.Documentation documentation = buildDocumentation(movements, documentRows, categories, checks);
        // Write-off is computed once, in the inventory pass, and shared — the two
        // flows must never be able to report different write-off totals.
        documentation.setWriteOffKg(inventory.getDocumentWriteOffKg());

        AuditFlowsDto flows = AuditFlowsDto.builder()
                .startDate(startDate)
                .endDate(endDate)
                .inventory(inventory)
                .cash(cash)
                .documentation(documentation)
                .dataWarnings(dataWarnings(bankRows, movements))
                .build();
        flows.setAlerts(alertEngine.evaluate(flows, checks));
        return flows;
    }

    // ==================== inventory ====================

    private AuditFlowsDto.Inventory buildInventory(List<ProductMovementDto> movements, LocalDate asOf) {
        Map<String, BigDecimal> rates = configClient.writeOffRates();
        Map<String, BigDecimal> realByProduct = latestRealInventory(asOf);

        Map<String, ProductAccumulator> byProduct = new LinkedHashMap<>();
        for (ProductMovementDto movement : movements) {
            if (movement.getProductName() == null || !isKilogram(movement.getUnit())) {
                // Non-kg lines cannot participate in a kg conservation check.
                continue;
            }
            ProductAccumulator acc = byProduct.computeIfAbsent(
                    movement.getProductName(), k -> new ProductAccumulator(k, movement.getParentCategory()));
            BigDecimal qty = nz(movement.getQuantityKg());
            if (movement.getType() == WaybillType.PURCHASE) {
                acc.purchaseKg = acc.purchaseKg.add(qty);
            } else if (movement.getType() == WaybillType.SALE) {
                acc.saleKg = acc.saleKg.add(qty);
            }
        }

        List<AuditFlowsDto.Inventory.ProductRow> rows = new ArrayList<>();
        BigDecimal totalPurchase = BigDecimal.ZERO;
        BigDecimal totalSale = BigDecimal.ZERO;
        BigDecimal totalWriteOff = BigDecimal.ZERO;
        BigDecimal totalDocStock = BigDecimal.ZERO;
        BigDecimal totalRealStock = BigDecimal.ZERO;
        int positiveGaps = 0;
        int negativeGaps = 0;

        for (ProductAccumulator acc : byProduct.values()) {
            // Reuse the existing kg-based possible-write-off model rather than a
            // second one: write-off is a percentage of what was received.
            BigDecimal percent = rates.getOrDefault(
                    acc.category == null ? "" : acc.category,
                    WriteOffCalculator.DEFAULT_WRITE_OFF_PERCENT);
            BigDecimal rate = percent.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
            BigDecimal writeOffKg = acc.purchaseKg.multiply(rate);

            BigDecimal docStock = acc.purchaseKg.subtract(acc.saleKg).subtract(writeOffKg);
            Boolean confirmed = realByProduct.containsKey(acc.product);
            BigDecimal realStock = realByProduct.getOrDefault(acc.product, BigDecimal.ZERO);
            BigDecimal gap = docStock.subtract(realStock);

            if (gap.signum() > 0) {
                positiveGaps++;
            } else if (gap.signum() < 0) {
                negativeGaps++;
            }

            rows.add(AuditFlowsDto.Inventory.ProductRow.builder()
                    .productName(acc.product)
                    .category(acc.category)
                    .purchaseKg(kg(acc.purchaseKg))
                    .saleKg(kg(acc.saleKg))
                    .writeOffKg(kg(writeOffKg))
                    .writeOffPercent(percent.setScale(2, RoundingMode.HALF_UP))
                    .documentStockKg(kg(docStock))
                    .realStockKg(kg(realStock))
                    .gapKg(kg(gap))
                    .realStockConfirmed(confirmed)
                    // Left null rather than guessed: attributing a cash gap to a
                    // single product needs mappings that carry a product, and an
                    // invented figure here would be worse than an empty cell.
                    .relatedCashGap(null)
                    .flaggedDocumentCount(0)
                    .build());

            totalPurchase = totalPurchase.add(acc.purchaseKg);
            totalSale = totalSale.add(acc.saleKg);
            totalWriteOff = totalWriteOff.add(writeOffKg);
            totalDocStock = totalDocStock.add(docStock);
            totalRealStock = totalRealStock.add(realStock);
        }

        rows.sort(Comparator.comparing(
                (AuditFlowsDto.Inventory.ProductRow r) -> r.getGapKg().abs()).reversed());

        return AuditFlowsDto.Inventory.builder()
                .products(rows)
                .documentPurchaseKg(kg(totalPurchase))
                .documentSaleKg(kg(totalSale))
                .documentWriteOffKg(kg(totalWriteOff))
                .documentStockKg(kg(totalDocStock))
                .realStockKg(kg(totalRealStock))
                .gapKg(kg(totalDocStock.subtract(totalRealStock)))
                .positiveGapProducts(positiveGaps)
                .negativeGapProducts(negativeGaps)
                .build();
    }

    /** Latest confirmation at or before {@code asOf}, per product. */
    private Map<String, BigDecimal> latestRealInventory(LocalDate asOf) {
        Map<String, RealInventoryOverrideDto> latest = new HashMap<>();
        for (RealInventoryOverrideDto override : repository.findRealInventory()) {
            if (override.getProductName() == null || override.getAsOfDate() == null
                    || override.getAsOfDate().isAfter(asOf)) {
                continue;
            }
            RealInventoryOverrideDto current = latest.get(override.getProductName());
            if (current == null || override.getAsOfDate().isAfter(current.getAsOfDate())) {
                latest.put(override.getProductName(), override);
            }
        }
        Map<String, BigDecimal> result = new HashMap<>();
        latest.forEach((product, override) -> result.put(product, nz(override.getRealKg())));
        return result;
    }

    // ==================== cash ====================

    private AuditFlowsDto.Cash buildCash(List<AuditSourceRowDto> bankRows,
                                         List<AuditSourceRowDto> documentRows,
                                         List<CheckEvidenceDto> checks,
                                         List<RealSupplierDebtDto> debts,
                                         Map<String, AuditCategoryDto> categories,
                                         List<ProductMovementDto> movements) {

        BigDecimal bankInflow = BigDecimal.ZERO;
        BigDecimal bankOutflow = BigDecimal.ZERO;
        BigDecimal directBankSupplierPayments = BigDecimal.ZERO;
        BigDecimal supplierCashSettlements = BigDecimal.ZERO;
        BigDecimal customerBankReceipts = BigDecimal.ZERO;
        BigDecimal otherIncome = BigDecimal.ZERO;
        BigDecimal nonSupplierExpenses = BigDecimal.ZERO;
        BigDecimal cashReturned = BigDecimal.ZERO;
        BigDecimal refunds = BigDecimal.ZERO;
        BigDecimal unresolvedOutflow = BigDecimal.ZERO;   // nobody has touched the row
        BigDecimal unresolvedInflow = BigDecimal.ZERO;
        BigDecimal parkedOutflow = BigDecimal.ZERO;        // explicitly parked as unresolved
        BigDecimal parkedInflow = BigDecimal.ZERO;
        BigDecimal paperOnlyCustomerReceipts = BigDecimal.ZERO;
        BigDecimal paperOnlySupplierPayments = BigDecimal.ZERO;
        int unmappedInflowCount = 0;
        int unmappedOutflowCount = 0;
        Set<String> breachSubjects = new LinkedHashSet<>();

        for (AuditSourceRowDto row : bankRows) {
            boolean outflow = isDebit(row);
            BigDecimal amount = nz(row.getAmount()).abs();
            if (outflow) {
                bankOutflow = bankOutflow.add(amount);
            } else {
                bankInflow = bankInflow.add(amount);
            }

            BigDecimal unresolved = nz(row.getUnresolvedAmount());
            if (unresolved.signum() > 0) {
                if (outflow) {
                    unresolvedOutflow = unresolvedOutflow.add(unresolved);
                    unmappedOutflowCount++;
                } else {
                    unresolvedInflow = unresolvedInflow.add(unresolved);
                    unmappedInflowCount++;
                }
            }

            for (AuditMappingSplitDto split : AuditMappingService.effectiveSplits(row.getMapping())) {
                BigDecimal value = nz(split.getAmount()).abs();
                AuditCategoryDto category = categories.get(split.getCategoryCode());
                if (category == null) {
                    continue;
                }
                switch (split.getCategoryCode()) {
                    case AuditCategories.SUPPLIER_BANK_PAYMENT ->
                            directBankSupplierPayments = directBankSupplierPayments.add(value);
                    case AuditCategories.OTHER_INCOME -> otherIncome = otherIncome.add(value);
                    case AuditCategories.CUSTOMER_REFUND, AuditCategories.SUPPLIER_REFUND,
                         AuditCategories.BANK_REVERSAL -> refunds = refunds.add(value);
                    default -> { /* handled by flag below */ }
                }
                // Flag-driven so user-created categories behave like built-ins.
                if (category.isSupplierSettlement()
                        && !AuditCategories.SUPPLIER_BANK_PAYMENT.equals(split.getCategoryCode())) {
                    supplierCashSettlements = supplierCashSettlements.add(value);
                    if (split.getCounterpartyName() != null) {
                        breachSubjects.add(split.getCounterpartyName());
                    }
                }
                if (category.isCustomerReceipt()) {
                    customerBankReceipts = customerBankReceipts.add(value);
                }
                if (category.isNonSupplierExpense()
                        && !AuditCategories.CUSTOMER_REFUND.equals(split.getCategoryCode())) {
                    nonSupplierExpenses = nonSupplierExpenses.add(value);
                }
                if (category.isCashReturn()) {
                    cashReturned = cashReturned.add(value);
                }
                if (category.isUnresolved()) {
                    // Allocating a row to an explicitly unresolved category is not
                    // resolving it. Without this, auto-classifying a withdrawal as
                    // "cash withdrawal — unresolved" would zero the row's
                    // unallocated remainder and the gap would vanish from the
                    // dashboard — the exact disappearance §13 forbids.
                    if (outflow) {
                        parkedOutflow = parkedOutflow.add(value);
                    } else {
                        parkedInflow = parkedInflow.add(value);
                    }
                }
            }
        }

        // Paper-only effects live on the document rows, not the bank rows.
        for (AuditSourceRowDto row : documentRows) {
            for (AuditMappingSplitDto split : AuditMappingService.effectiveSplits(row.getMapping())) {
                BigDecimal value = nz(split.getAmount()).abs();
                if (AuditCategories.PAPER_ONLY_CUSTOMER_RECEIPT.equals(split.getCategoryCode())) {
                    paperOnlyCustomerReceipts = paperOnlyCustomerReceipts.add(value);
                } else if (AuditCategories.PAPER_ONLY_SUPPLIER_PAYMENT.equals(split.getCategoryCode())) {
                    paperOnlySupplierPayments = paperOnlySupplierPayments.add(value);
                }
            }
        }

        // Checks are evidence, never money. Supported = the part linked to a real
        // bank movement; anything else is unsupported until someone explains it.
        Map<String, BigDecimal> supportByCheck = supportedAmountsByCheck(bankRows);
        BigDecimal checksOnHand = BigDecimal.ZERO;
        BigDecimal checksSupported = BigDecimal.ZERO;
        int unclassifiedChecks = 0;
        for (CheckEvidenceDto check : checks) {
            BigDecimal amount = nz(check.getAmount()).abs();
            BigDecimal supported = supportByCheck.getOrDefault(check.getId(), BigDecimal.ZERO).min(amount);
            checksOnHand = checksOnHand.add(amount);
            checksSupported = checksSupported.add(supported);
            if (amount.subtract(supported).signum() > 0 && !check.isClassified()) {
                unclassifiedChecks++;
            }
        }
        BigDecimal unsupportedChecks = checksOnHand.subtract(checksSupported).max(BigDecimal.ZERO);

        // On-paper sales value comes from document rows classified paper-only.
        BigDecimal onPaperSales = paperOnlyValue(documentRows, AuditCategories.PAPER_ONLY_SALE);
        BigDecimal realAgainstPaperSales = BigDecimal.ZERO; // no linkage recorded yet
        BigDecimal paperCashCreated = paperOnlyCustomerReceipts
                .subtract(realAgainstPaperSales).max(BigDecimal.ZERO);

        BigDecimal onPaperSupplierPaymentValue = paperOnlySupplierPayments.add(checksOnHand);
        BigDecimal paperCashReduced = onPaperSupplierPaymentValue
                .subtract(checksSupported).max(BigDecimal.ZERO);

        BigDecimal realDebt = BigDecimal.ZERO;
        for (RealSupplierDebtDto debt : debts) {
            realDebt = realDebt.add(nz(debt.getOutstandingAmount()));
        }

        BigDecimal documentedSupplierPurchases = BigDecimal.ZERO;
        for (ProductMovementDto movement : movements) {
            if (movement.getType() == WaybillType.PURCHASE) {
                documentedSupplierPurchases = documentedSupplierPurchases.add(nz(movement.getAmount()));
            }
        }

        // Who RS.ge says actually sold to us, reconciled against who the bank
        // says we paid. This is the check that turns "looks like a supplier"
        // into evidence — and, more usefully, exposes money paid to
        // counterparties that never sold anything at all.
        AuditSupplierRegistry suppliers = AuditSupplierRegistry.from(movements);
        Map<String, BigDecimal> paidByTin = new LinkedHashMap<>();
        Map<String, String> nameByTin = new LinkedHashMap<>();
        Map<String, Integer> rowsByTin = new LinkedHashMap<>();
        BigDecimal outflowNoCounterparty = BigDecimal.ZERO;
        int outflowNoCounterpartyRows = 0;
        BigDecimal outflowByName = BigDecimal.ZERO;
        int outflowByNameRows = 0;
        BigDecimal outflowAmbiguous = BigDecimal.ZERO;
        int outflowAmbiguousRows = 0;
        for (AuditSourceRowDto r : bankRows) {
            if (!isDebit(r)) {
                continue;
            }
            // Resolved identity: the printed tax code where there is one, else
            // the one inferred from the counterparty name. Using the printed
            // column alone understated payments to real suppliers by millions.
            String tin = r.getResolvedCounterpartyTin() == null
                    ? null : r.getResolvedCounterpartyTin().trim();
            BigDecimal amt = nz(r.getAmount()).abs();
            if (r.getCounterpartyIdentitySource() == CounterpartyIdentitySource.RESOLVED_BY_NAME
                    || r.getCounterpartyIdentitySource() == CounterpartyIdentitySource.MANUAL_ALIAS) {
                outflowByName = outflowByName.add(amt);
                outflowByNameRows++;
            } else if (r.getCounterpartyIdentitySource() == CounterpartyIdentitySource.AMBIGUOUS) {
                outflowAmbiguous = outflowAmbiguous.add(amt);
                outflowAmbiguousRows++;
            }
            if (tin == null || tin.isEmpty()) {
                outflowNoCounterparty = outflowNoCounterparty.add(amt);
                outflowNoCounterpartyRows++;
                continue;
            }
            paidByTin.merge(tin, amt, BigDecimal::add);
            rowsByTin.merge(tin, 1, Integer::sum);
            nameByTin.putIfAbsent(tin, r.getCounterpartyName());
        }
        Map<String, BigDecimal> debtByTin = new LinkedHashMap<>();
        for (RealSupplierDebtDto d : debts) {
            if (d.getSupplierTin() != null && !d.getSupplierTin().isBlank()) {
                debtByTin.merge(d.getSupplierTin().trim(), nz(d.getOutstandingAmount()), BigDecimal::add);
            }
        }

        List<AuditFlowsDto.Cash.SupplierCoverageRow> coverage = new ArrayList<>();
        BigDecimal paidUndocumented = BigDecimal.ZERO;
        int undocumentedCount = 0;
        Set<String> allTins = new LinkedHashSet<>(suppliers.purchaseValueByTin().keySet());
        allTins.addAll(paidByTin.keySet());
        int unpaidDocumented = 0;
        BigDecimal unpaidDocumentedValue = BigDecimal.ZERO;
        for (String tin : allTins) {
            BigDecimal documented = suppliers.documentedPurchases(tin);
            BigDecimal paid = paidByTin.getOrDefault(tin, BigDecimal.ZERO);
            BigDecimal debt = debtByTin.getOrDefault(tin, BigDecimal.ZERO);
            boolean paidWithoutDocs = paid.signum() > 0 && documented.signum() == 0;
            boolean documentedUnpaid = documented.signum() > 0 && paid.signum() == 0;
            if (paidWithoutDocs) {
                paidUndocumented = paidUndocumented.add(paid);
                undocumentedCount++;
            }
            if (documentedUnpaid) {
                unpaidDocumented++;
                unpaidDocumentedValue = unpaidDocumentedValue.add(documented);
            }
            coverage.add(AuditFlowsDto.Cash.SupplierCoverageRow.builder()
                    .counterpartyTin(tin)
                    .counterpartyName(nameByTin.get(tin))
                    .documentedPurchases(money(documented))
                    .bankPaid(money(paid))
                    .realDebt(money(debt))
                    .uncovered(money(documented.subtract(paid).subtract(debt)))
                    .paidWithoutDocumentation(paidWithoutDocs)
                    .documentedButUnpaid(documentedUnpaid)
                    .bankRowCount(rowsByTin.getOrDefault(tin, 0))
                    .build());
        }
        coverage.sort(Comparator.comparing(
                (AuditFlowsDto.Cash.SupplierCoverageRow r) ->
                        nz(r.getUncovered()).abs().max(nz(r.getBankPaid()))).reversed());

        BigDecimal settlementAndDebt = directBankSupplierPayments
                .add(supplierCashSettlements).add(realDebt);
        BigDecimal excess = settlementAndDebt.subtract(documentedSupplierPurchases).max(BigDecimal.ZERO);

        // The cash-side outflow pool: everything paid out that is not a direct
        // bank transfer to a supplier.
        // "Unresolved" spans two states that must not be conflated in the maths
        // but do belong in the same headline: a row nobody has touched, and a row
        // a person deliberately parked as unexplained cash.
        BigDecimal totalUnresolvedOutflow = unresolvedOutflow.add(parkedOutflow);
        BigDecimal cashWithdrawals = supplierCashSettlements
                .add(nonSupplierExpenses).add(cashReturned).add(totalUnresolvedOutflow);

        return AuditFlowsDto.Cash.builder()
                .bankInflow(money(bankInflow))
                .bankOutflow(money(bankOutflow))
                .cashWithdrawals(money(cashWithdrawals))
                .mappedWithdrawalAmount(money(cashWithdrawals.subtract(totalUnresolvedOutflow)))
                .unresolvedWithdrawalAmount(money(totalUnresolvedOutflow))
                .directBankSupplierPayments(money(directBankSupplierPayments))
                .supplierAllocatedCashSettlements(money(supplierCashSettlements))
                .customerBankReceipts(money(customerBankReceipts))
                .realCustomerCashReceipts(null)
                .otherIncome(money(otherIncome))
                .nonSupplierExpenses(money(nonSupplierExpenses))
                .refundsAndReversals(money(refunds))
                .cashReturnedOrRedeposited(money(cashReturned))
                .checksOnHand(money(checksOnHand))
                .checksSupportedByRealMoney(money(checksSupported))
                .unsupportedChecks(money(unsupportedChecks))
                .unclassifiedCheckCount(unclassifiedChecks)
                .onPaperSalesValue(money(onPaperSales))
                .onPaperCustomerReceiptValue(money(paperOnlyCustomerReceipts))
                .realMoneyReceivedAgainstPaperSales(money(realAgainstPaperSales))
                .paperCashCreated(money(paperCashCreated))
                .onPaperSupplierPaymentValue(money(onPaperSupplierPaymentValue))
                .realMoneySupportingSupplierPayments(money(checksSupported))
                .paperCashReduced(money(paperCashReduced))
                .netUnexplainedPaperCash(money(paperCashCreated.subtract(paperCashReduced)))
                .realOutstandingSupplierDebt(money(realDebt))
                .supplierSettlementAndDebt(money(settlementAndDebt))
                .documentedSupplierPurchases(money(documentedSupplierPurchases))
                .excessOverDocumentedPurchases(money(excess))
                .uncoveredPurchaseBalance(money(documentedSupplierPurchases.subtract(settlementAndDebt)))
                .coverageBreach(excess.signum() > 0)
                .coverageBreachSubjects(new ArrayList<>(breachSubjects))
                .unmappedInflowCount(unmappedInflowCount)
                .unmappedInflowAmount(money(unresolvedInflow))
                .unmappedOutflowCount(unmappedOutflowCount)
                .unmappedOutflowAmount(money(unresolvedOutflow))
                .bankRowCount(bankRows.size())
                .supplierCoverage(coverage)
                .paidToUndocumentedCounterparties(money(paidUndocumented))
                .undocumentedCounterpartyCount(undocumentedCount)
                .unpaidDocumentedSupplierCount(unpaidDocumented)
                .unpaidDocumentedSupplierPurchases(money(unpaidDocumentedValue))
                .outflowWithoutCounterpartyId(money(outflowNoCounterparty))
                .outflowWithoutCounterpartyIdCount(outflowNoCounterpartyRows)
                .outflowIdentifiedByName(money(outflowByName))
                .outflowIdentifiedByNameCount(outflowByNameRows)
                .outflowAmbiguousCounterparty(money(outflowAmbiguous))
                .outflowAmbiguousCounterpartyCount(outflowAmbiguousRows)
                .build();
    }

    /**
     * Sums, per check id, the bank-row splits whose mapping links to that check.
     * A link is written as {@code CHECK:<id>} in {@code linkedSourceRows}.
     */
    private Map<String, BigDecimal> supportedAmountsByCheck(List<AuditSourceRowDto> bankRows) {
        Map<String, BigDecimal> byCheck = new HashMap<>();
        for (AuditSourceRowDto row : bankRows) {
            AuditMappingDto mapping = row.getMapping();
            if (mapping == null || mapping.getLinkedSourceRows() == null
                    || mapping.getStatus() == AuditMappingStatus.VOIDED
                    || mapping.getStatus() == AuditMappingStatus.SUGGESTED) {
                continue;
            }
            BigDecimal allocated = AuditMappingService.splitTotal(mapping.getSplits());
            for (String link : mapping.getLinkedSourceRows()) {
                if (link != null && link.startsWith("CHECK:")) {
                    String checkId = link.substring("CHECK:".length());
                    byCheck.merge(checkId, allocated, BigDecimal::add);
                }
            }
        }
        return byCheck;
    }

    private BigDecimal paperOnlyValue(List<AuditSourceRowDto> rows, String categoryCode) {
        BigDecimal total = BigDecimal.ZERO;
        for (AuditSourceRowDto row : rows) {
            for (AuditMappingSplitDto split : AuditMappingService.effectiveSplits(row.getMapping())) {
                if (categoryCode.equals(split.getCategoryCode())) {
                    total = total.add(nz(split.getAmount()).abs());
                }
            }
        }
        return total;
    }

    // ==================== documentation ====================

    private AuditFlowsDto.Documentation buildDocumentation(List<ProductMovementDto> movements,
                                                           List<AuditSourceRowDto> documentRows,
                                                           Map<String, AuditCategoryDto> categories,
                                                           List<CheckEvidenceDto> checks) {
        BigDecimal purchaseValue = BigDecimal.ZERO;
        BigDecimal purchaseKg = BigDecimal.ZERO;
        BigDecimal salesValue = BigDecimal.ZERO;
        BigDecimal salesKg = BigDecimal.ZERO;
        for (ProductMovementDto movement : movements) {
            BigDecimal amount = nz(movement.getAmount());
            BigDecimal qty = isKilogram(movement.getUnit()) ? nz(movement.getQuantityKg()) : BigDecimal.ZERO;
            if (movement.getType() == WaybillType.PURCHASE) {
                purchaseValue = purchaseValue.add(amount);
                purchaseKg = purchaseKg.add(qty);
            } else if (movement.getType() == WaybillType.SALE) {
                salesValue = salesValue.add(amount);
                salesKg = salesKg.add(qty);
            }
        }

        BigDecimal paperSales = BigDecimal.ZERO;
        int paperSalesCount = 0;
        BigDecimal paperReceipts = BigDecimal.ZERO;
        int paperReceiptCount = 0;
        BigDecimal unmappedValue = BigDecimal.ZERO;
        int unmappedCount = 0;
        BigDecimal flaggedValue = BigDecimal.ZERO;
        int flaggedCount = 0;

        for (AuditSourceRowDto row : documentRows) {
            BigDecimal amount = nz(row.getAmount()).abs();
            boolean flagged = false;
            if (row.getStatus() == AuditMappingStatus.UNMAPPED) {
                unmappedCount++;
                unmappedValue = unmappedValue.add(amount);
                flagged = true;
            }
            for (AuditMappingSplitDto split : AuditMappingService.effectiveSplits(row.getMapping())) {
                AuditCategoryDto category = categories.get(split.getCategoryCode());
                BigDecimal value = nz(split.getAmount()).abs();
                if (AuditCategories.PAPER_ONLY_SALE.equals(split.getCategoryCode())) {
                    paperSales = paperSales.add(value);
                    paperSalesCount++;
                }
                if (AuditCategories.PAPER_ONLY_CUSTOMER_RECEIPT.equals(split.getCategoryCode())) {
                    paperReceipts = paperReceipts.add(value);
                    paperReceiptCount++;
                }
                if (category != null && category.isPaperOnly()) {
                    flagged = true;
                }
            }
            if (flagged) {
                flaggedCount++;
                flaggedValue = flaggedValue.add(amount);
            }
        }

        BigDecimal unsupportedSupplierDocs = BigDecimal.ZERO;
        int unsupportedSupplierDocCount = 0;
        for (CheckEvidenceDto check : checks) {
            if (!check.isClassified()) {
                unsupportedSupplierDocs = unsupportedSupplierDocs.add(nz(check.getAmount()).abs());
                unsupportedSupplierDocCount++;
            }
        }

        return AuditFlowsDto.Documentation.builder()
                .documentPurchaseValue(money(purchaseValue))
                .documentPurchaseKg(kg(purchaseKg))
                .documentSalesValue(money(salesValue))
                .documentSalesKg(kg(salesKg))
                .writeOffKg(null)
                .paperOnlySalesValue(money(paperSales))
                .paperOnlySalesCount(paperSalesCount)
                .paperOnlyCustomerPaymentValue(money(paperReceipts))
                .paperOnlyCustomerPaymentCount(paperReceiptCount)
                .unsupportedSupplierDocumentValue(money(unsupportedSupplierDocs))
                .unsupportedSupplierDocumentCount(unsupportedSupplierDocCount)
                .unmappedDocumentRowCount(unmappedCount)
                .unmappedDocumentValue(money(unmappedValue))
                .flaggedDocumentCount(flaggedCount)
                .flaggedDocumentValue(money(flaggedValue))
                .documentRowCount(documentRows.size())
                .build();
    }

    // ==================== warnings ====================

    /**
     * Says out loud when a flow is empty because data is missing rather than
     * because the books are clean. An empty cash flow that looks tidy is the most
     * dangerous output this module could produce.
     */
    private List<String> dataWarnings(List<AuditSourceRowDto> bankRows,
                                      List<ProductMovementDto> movements) {
        List<String> warnings = new ArrayList<>();
        if (bankRows.isEmpty()) {
            warnings.add("No bank statement rows exist for this period. "
                    + "The cash flow is empty because no data was imported, not because nothing happened.");
        } else if (bankRows.stream().noneMatch(this::isDebit)) {
            warnings.add("No bank outflows exist for this period — only money-in rows were found. "
                    + "Withdrawals and supplier transfers cannot be audited until an outflow-bearing "
                    + "statement is imported.");
        }
        if (movements.isEmpty()) {
            warnings.add("No RS.ge document rows exist for this period. "
                    + "The inventory and documentation flows are empty for want of source data.");
        }
        return warnings;
    }

    // ==================== helpers ====================

    private boolean isDebit(AuditSourceRowDto row) {
        return row.getDirection() != null && "DEBIT".equalsIgnoreCase(row.getDirection().trim());
    }

    /**
     * Blank or unknown units default to kilograms: meat lines are overwhelmingly
     * kg and RS.ge's unit encoding is not guaranteed, so only units explicitly
     * recognised as pieces/volume are excluded. Mirrors the rule already used by
     * {@code AuditControlService}.
     */
    private boolean isKilogram(String unit) {
        if (unit == null || unit.isBlank()) {
            return true;
        }
        String lower = unit.toLowerCase();
        return NON_KG_UNITS.stream().noneMatch(lower::contains);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal kg(BigDecimal value) {
        return value == null ? null : value.setScale(KG_SCALE, RoundingMode.HALF_UP);
    }

    private List<CheckEvidenceDto> checksInPeriod(LocalDate startDate, LocalDate endDate) {
        List<CheckEvidenceDto> result = new ArrayList<>();
        for (CheckEvidenceDto check : repository.findCheckEvidence()) {
            if (check.getDate() == null
                    || (!check.getDate().isBefore(startDate) && !check.getDate().isAfter(endDate))) {
                result.add(check);
            }
        }
        return result;
    }

    private static final class ProductAccumulator {
        private final String product;
        private final String category;
        private BigDecimal purchaseKg = BigDecimal.ZERO;
        private BigDecimal saleKg = BigDecimal.ZERO;

        private ProductAccumulator(String product, String category) {
            this.product = product;
            this.category = category;
        }
    }
}
