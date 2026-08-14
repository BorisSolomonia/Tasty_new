package ge.tastyerp.payment.audit;

import ge.tastyerp.common.dto.auditlayer.AuditAlertDto;
import ge.tastyerp.common.dto.auditlayer.AuditFlowsDto;
import ge.tastyerp.common.dto.auditlayer.CheckEvidenceDto;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Transparent audit rules (BOR-89 §9).
 *
 * <p><b>Every rule owns its drill-down.</b> Three rules once shared
 * {@code cash.supplierSettlement}, which was the record set of none of them, so
 * clicking a problem answered a different question than the one asked. A
 * drill-down that shows unrelated records is worse than none: it invites the
 * reader to reconcile a number against evidence that never produced it
 * (BOR-91).</p>
 *
 * <p>Every rule returns the inputs it was evaluated on, so the UI can show the
 * arithmetic instead of an unexplained red number. That is the ticket's
 * "implement transparent rules first, each alert explains the formula" — a rule
 * whose reasoning cannot be inspected is not usable as audit evidence.</p>
 *
 * <p>This is the first tranche: the highest-value rules of the ticket's 25.
 * Rules that need data the system does not hold yet (per-row withdrawal
 * classification history, refund linkage) are deliberately absent rather than
 * stubbed to always-pass, which would read as "clean".</p>
 */
@Component
public class AuditAlertEngine {

    public static final String FLOW_INVENTORY = "INVENTORY";
    public static final String FLOW_CASH = "CASH";
    public static final String FLOW_DOCUMENTATION = "DOCUMENTATION";

    private static final String CRITICAL = "CRITICAL";
    private static final String HIGH = "HIGH";
    private static final String MEDIUM = "MEDIUM";

    /** A gap below this many kg is noise, not a finding. */
    private static final BigDecimal LARGE_GAP_KG = new BigDecimal("100");

    public List<AuditAlertDto> evaluate(AuditFlowsDto flows, List<CheckEvidenceDto> checks) {
        List<AuditAlertDto> alerts = new ArrayList<>();
        AuditFlowsDto.Inventory inventory = flows.getInventory();
        AuditFlowsDto.Cash cash = flows.getCash();
        AuditFlowsDto.Documentation documentation = flows.getDocumentation();

        // ---- rule 21 (CRITICAL): settlement + debt must not exceed purchases ----
        if (cash != null && cash.isCoverageBreach()) {
            alerts.add(AuditAlertDto.builder()
                    .ruleId("SUPPLIER_COVERAGE_BREACH")
                    .severity(CRITICAL)
                    .flow(FLOW_CASH)
                    .title("Supplier settlement and debt exceed documented purchases")
                    .formula("direct bank supplier payments + supplier-allocated cash/check "
                            + "settlements + real outstanding debt <= documented supplier purchases")
                    .inputs(inputs(
                            "directBankSupplierPayments", cash.getDirectBankSupplierPayments(),
                            "supplierAllocatedCashSettlements", cash.getSupplierAllocatedCashSettlements(),
                            "realOutstandingSupplierDebt", cash.getRealOutstandingSupplierDebt(),
                            "settlementAndDebt", cash.getSupplierSettlementAndDebt(),
                            "documentedSupplierPurchases", cash.getDocumentedSupplierPurchases()))
                    .amount(cash.getExcessOverDocumentedPurchases())
                    .drilldownKey("cash.supplierSettlement")
                    .subjects(cash.getCoverageBreachSubjects())
                    .build());
        }

        // ---- rule 25: large unexplained purchase residual ----
        if (cash != null && positive(cash.getUncoveredPurchaseBalance())
                && positive(cash.getDocumentedSupplierPurchases())) {
            alerts.add(AuditAlertDto.builder()
                    .ruleId("UNCOVERED_PURCHASE_BALANCE")
                    .severity(MEDIUM)
                    .flow(FLOW_CASH)
                    .title("Documented purchases are not fully covered by settlement and debt")
                    .formula("documented supplier purchases − settlement & debt")
                    .inputs(inputs(
                            "documentedSupplierPurchases", cash.getDocumentedSupplierPurchases(),
                            "settlementAndDebt", cash.getSupplierSettlementAndDebt()))
                    .amount(cash.getUncoveredPurchaseBalance())
                    .drilldownKey("cash.uncoveredPurchase")
                    .build());
        }

        // ---- money paid to counterparties that never sold anything ----
        if (cash != null && positive(cash.getPaidToUndocumentedCounterparties())) {
            List<String> subjects = new ArrayList<>();
            if (cash.getSupplierCoverage() != null) {
                cash.getSupplierCoverage().stream()
                        .filter(AuditFlowsDto.Cash.SupplierCoverageRow::isPaidWithoutDocumentation)
                        .limit(10)
                        .forEach(r -> subjects.add(
                                (r.getCounterpartyName() == null ? r.getCounterpartyTin()
                                        : r.getCounterpartyName())
                                        + " (" + r.getBankPaid() + ")"));
            }
            alerts.add(AuditAlertDto.builder()
                    .ruleId("PAID_WITHOUT_PURCHASE_DOCUMENTATION")
                    .severity(CRITICAL)
                    .flow(FLOW_CASH)
                    .title("Money paid to counterparties that never sold anything on RS.ge")
                    .formula("Σ bank money out to a tax code that appears on no purchase document")
                    .inputs(inputs(
                            "paidToUndocumentedCounterparties", cash.getPaidToUndocumentedCounterparties(),
                            "bankOutflow", cash.getBankOutflow()))
                    .amount(cash.getPaidToUndocumentedCounterparties())
                    .affectedRowCount(cash.getUndocumentedCounterpartyCount())
                    .subjects(subjects)
                    .drilldownKey("cash.paidWithoutDocumentation")
                    .build());
        }

        // ---- documented suppliers who were never paid through the bank ----
        if (cash != null && cash.getUnpaidDocumentedSupplierCount() > 0) {
            alerts.add(AuditAlertDto.builder()
                    .ruleId("DOCUMENTED_SUPPLIER_NEVER_PAID_BY_BANK")
                    .severity(HIGH)
                    .flow(FLOW_CASH)
                    .title("Suppliers who sold on paper but received no bank payment")
                    .formula("counterparties with documented purchases > 0 and bank paid = 0")
                    .inputs(inputs(
                            "unpaidDocumentedSupplierPurchases", cash.getUnpaidDocumentedSupplierPurchases(),
                            "documentedSupplierPurchases", cash.getDocumentedSupplierPurchases()))
                    .amount(cash.getUnpaidDocumentedSupplierPurchases())
                    .affectedRowCount(cash.getUnpaidDocumentedSupplierCount())
                    .drilldownKey("cash.supplierNeverPaid")
                    .build());
        }

        // ---- outflow that left with no counterparty identification at all ----
        if (cash != null && positive(cash.getOutflowWithoutCounterpartyId())) {
            alerts.add(AuditAlertDto.builder()
                    .ruleId("OUTFLOW_WITHOUT_COUNTERPARTY_ID")
                    .severity(HIGH)
                    .flow(FLOW_CASH)
                    .title("Money left the account with no counterparty tax code")
                    .formula("Σ money-out rows whose partner tax-code column is empty")
                    .inputs(inputs(
                            "outflowWithoutCounterpartyId", cash.getOutflowWithoutCounterpartyId(),
                            "bankOutflow", cash.getBankOutflow()))
                    .amount(cash.getOutflowWithoutCounterpartyId())
                    .affectedRowCount(cash.getOutflowWithoutCounterpartyIdCount())
                    .drilldownKey("cash.outflowWithoutCounterparty")
                    .build());
        }

        // ---- rules 9 & 16: unresolved / unmapped outflow ----
        if (cash != null && positive(cash.getUnresolvedWithdrawalAmount())) {
            alerts.add(AuditAlertDto.builder()
                    .ruleId("CASH_WITHDRAWAL_UNRESOLVED")
                    .severity(HIGH)
                    .flow(FLOW_CASH)
                    .title("Bank outflow is partially or wholly unmapped")
                    .formula("Σ (outflow row amount − Σ its split allocations)")
                    .inputs(inputs(
                            "bankOutflow", cash.getBankOutflow(),
                            "mappedWithdrawalAmount", cash.getMappedWithdrawalAmount(),
                            "unresolvedWithdrawalAmount", cash.getUnresolvedWithdrawalAmount()))
                    .amount(cash.getUnresolvedWithdrawalAmount())
                    .affectedRowCount(cash.getUnmappedOutflowCount())
                    .drilldownKey("cash.unresolvedWithdrawals")
                    .build());
        }

        // ---- rule 15: unmapped inflow ----
        if (cash != null && positive(cash.getUnmappedInflowAmount())) {
            alerts.add(AuditAlertDto.builder()
                    .ruleId("UNMAPPED_BANK_INFLOW")
                    .severity(MEDIUM)
                    .flow(FLOW_CASH)
                    .title("Money received that nobody has classified")
                    .formula("Σ (inflow row amount − Σ its split allocations)")
                    .inputs(inputs(
                            "bankInflow", cash.getBankInflow(),
                            "unmappedInflowAmount", cash.getUnmappedInflowAmount()))
                    .amount(cash.getUnmappedInflowAmount())
                    .affectedRowCount(cash.getUnmappedInflowCount())
                    .drilldownKey("cash.unmappedInflows")
                    .build());
        }

        // ---- rules 11 & 12: checks without supporting money ----
        if (cash != null && positive(cash.getUnsupportedChecks())) {
            alerts.add(AuditAlertDto.builder()
                    .ruleId("UNSUPPORTED_CHECKS")
                    .severity(HIGH)
                    .flow(FLOW_CASH)
                    .title("Payment evidence is not backed by real money movement")
                    .formula("checks on hand − checks linked to real bank/cash movement")
                    .inputs(inputs(
                            "checksOnHand", cash.getChecksOnHand(),
                            "checksSupportedByRealMoney", cash.getChecksSupportedByRealMoney(),
                            "unsupportedChecks", cash.getUnsupportedChecks()))
                    .amount(cash.getUnsupportedChecks())
                    .affectedRowCount(checks == null ? 0 : checks.size())
                    .drilldownKey("cash.unsupportedChecks")
                    .build());
        }

        // ---- rule 24: evidence entered but never judged ----
        if (cash != null && cash.getUnclassifiedCheckCount() > 0) {
            alerts.add(AuditAlertDto.builder()
                    .ruleId("CHECKS_UNCLASSIFIED")
                    .severity(MEDIUM)
                    .flow(FLOW_CASH)
                    .title("Payment evidence has an unsupported balance nobody has explained")
                    .formula("count of checks where unsupported > 0 and classified = false")
                    .inputs(inputs("unsupportedChecks", cash.getUnsupportedChecks()))
                    .affectedRowCount(cash.getUnclassifiedCheckCount())
                    .drilldownKey("cash.checksUnclassified")
                    .build());
        }

        // ---- rules 8, 22 & 23: paper cash versus real money ----
        if (cash != null && positive(cash.getNetUnexplainedPaperCash())) {
            alerts.add(AuditAlertDto.builder()
                    .ruleId("NET_UNEXPLAINED_PAPER_CASH")
                    .severity(CRITICAL)
                    .flow(FLOW_CASH)
                    .title("Accounting cash exists that real money does not support")
                    .formula("paper cash created (unsupported customer receipts) "
                            + "− paper cash reduced (unsupported supplier payment documents)")
                    .inputs(inputs(
                            "onPaperCustomerReceiptValue", cash.getOnPaperCustomerReceiptValue(),
                            "realMoneyReceivedAgainstPaperSales", cash.getRealMoneyReceivedAgainstPaperSales(),
                            "paperCashCreated", cash.getPaperCashCreated(),
                            "paperCashReduced", cash.getPaperCashReduced()))
                    .amount(cash.getNetUnexplainedPaperCash())
                    .drilldownKey("cash.paperCash")
                    .build());
        }

        // ---- rules 1 & 3: document stock while real stock is zero ----
        if (inventory != null && inventory.getProducts() != null) {
            List<String> inflated = new ArrayList<>();
            BigDecimal inflatedKg = BigDecimal.ZERO;
            List<String> negative = new ArrayList<>();
            BigDecimal negativeKg = BigDecimal.ZERO;
            for (AuditFlowsDto.Inventory.ProductRow row : inventory.getProducts()) {
                BigDecimal gap = row.getGapKg();
                if (gap == null) {
                    continue;
                }
                if (gap.compareTo(LARGE_GAP_KG) > 0) {
                    inflated.add(row.getProductName());
                    inflatedKg = inflatedKg.add(gap);
                } else if (row.getDocumentStockKg() != null
                        && row.getDocumentStockKg().signum() < 0) {
                    negative.add(row.getProductName());
                    negativeKg = negativeKg.add(row.getDocumentStockKg());
                }
            }
            if (!inflated.isEmpty()) {
                alerts.add(AuditAlertDto.builder()
                        .ruleId("DOCUMENT_STOCK_WITHOUT_REAL_STOCK")
                        .severity(CRITICAL)
                        .flow(FLOW_INVENTORY)
                        .title("Document stock exists that confirmed real stock does not")
                        .formula("document stock − confirmed real stock > " + LARGE_GAP_KG + " kg")
                        .inputs(inputs(
                                "documentStockKg", inventory.getDocumentStockKg(),
                                "realStockKg", inventory.getRealStockKg()))
                        .quantityKg(inflatedKg)
                        .affectedRowCount(inflated.size())
                        .subjects(inflated)
                        .drilldownKey("inventory.positiveGap")
                        .build());
            }
            if (!negative.isEmpty()) {
                alerts.add(AuditAlertDto.builder()
                        .ruleId("NEGATIVE_DOCUMENT_INVENTORY")
                        .severity(HIGH)
                        .flow(FLOW_INVENTORY)
                        .title("More was sold or written off than was ever documented as bought")
                        .formula("purchased kg − sold kg − write-off kg < 0")
                        .inputs(inputs(
                                "documentPurchaseKg", inventory.getDocumentPurchaseKg(),
                                "documentSaleKg", inventory.getDocumentSaleKg(),
                                "documentWriteOffKg", inventory.getDocumentWriteOffKg()))
                        .quantityKg(negativeKg)
                        .affectedRowCount(negative.size())
                        .subjects(negative)
                        .drilldownKey("inventory.negativeGap")
                        .build());
            }
        }

        // ---- rules 6 & 7: paper-only documentation ----
        if (documentation != null && positive(documentation.getPaperOnlySalesValue())) {
            alerts.add(AuditAlertDto.builder()
                    .ruleId("PAPER_ONLY_SALE")
                    .severity(HIGH)
                    .flow(FLOW_DOCUMENTATION)
                    .title("Sale documents classified as having no real sale behind them")
                    .formula("Σ document rows mapped to the paper-only sale category")
                    .inputs(inputs("paperOnlySalesValue", documentation.getPaperOnlySalesValue()))
                    .amount(documentation.getPaperOnlySalesValue())
                    .affectedRowCount(documentation.getPaperOnlySalesCount())
                    .drilldownKey("documentation.paperOnlySales")
                    .build());
        }
        if (documentation != null && positive(documentation.getPaperOnlyCustomerPaymentValue())) {
            alerts.add(AuditAlertDto.builder()
                    .ruleId("PAPER_ONLY_CUSTOMER_RECEIPT")
                    .severity(HIGH)
                    .flow(FLOW_DOCUMENTATION)
                    .title("Receipt documents recorded without real money arriving")
                    .formula("Σ document rows mapped to the paper-only customer receipt category")
                    .inputs(inputs("paperOnlyCustomerPaymentValue",
                            documentation.getPaperOnlyCustomerPaymentValue()))
                    .amount(documentation.getPaperOnlyCustomerPaymentValue())
                    .affectedRowCount(documentation.getPaperOnlyCustomerPaymentCount())
                    .drilldownKey("documentation.paperOnlyReceipts")
                    .build());
        }

        // ---- rule 17: unmapped document rows ----
        if (documentation != null && documentation.getUnmappedDocumentRowCount() > 0) {
            alerts.add(AuditAlertDto.builder()
                    .ruleId("UNMAPPED_DOCUMENT_ROWS")
                    .severity(MEDIUM)
                    .flow(FLOW_DOCUMENTATION)
                    .title("RS.ge document rows nobody has classified")
                    .formula("count of document rows with mapping status UNMAPPED")
                    .inputs(inputs("unmappedDocumentValue", documentation.getUnmappedDocumentValue()))
                    .amount(documentation.getUnmappedDocumentValue())
                    .affectedRowCount(documentation.getUnmappedDocumentRowCount())
                    .drilldownKey("documentation.unmapped")
                    .build());
        }

        alerts.sort(Comparator.comparingInt(a -> severityRank(a.getSeverity())));
        return alerts;
    }

    private static int severityRank(String severity) {
        return switch (severity == null ? "" : severity) {
            case CRITICAL -> 0;
            case HIGH -> 1;
            case MEDIUM -> 2;
            default -> 3;
        };
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    /** Builds an ordered input map from alternating name/value pairs. */
    private static Map<String, BigDecimal> inputs(Object... pairs) {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), (BigDecimal) pairs[i + 1]);
        }
        return map;
    }
}
