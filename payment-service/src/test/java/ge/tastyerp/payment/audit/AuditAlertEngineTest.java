package ge.tastyerp.payment.audit;

import ge.tastyerp.common.dto.auditlayer.AuditAlertDto;
import ge.tastyerp.common.dto.auditlayer.AuditFlowsDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The supplier purchase-coverage control (BOR-89 §4B) and the transparency
 * contract every rule owes the reader.
 *
 * <p>The control asks a deliberately narrow question: can the settlement the
 * business claims to have made, plus the debt it says it still owes, be
 * explained by what it documented buying? It uses only supplier-allocated
 * settlement — never total withdrawals, because a withdrawal does not prove a
 * supplier was paid.</p>
 */
class AuditAlertEngineTest {

    private final AuditAlertEngine engine = new AuditAlertEngine();

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private AuditFlowsDto flowsWithCoverage(String directBank, String cashSettlement,
                                            String debt, String documentedPurchases) {
        BigDecimal settlement = bd(directBank).add(bd(cashSettlement)).add(bd(debt));
        BigDecimal purchases = bd(documentedPurchases);
        BigDecimal excess = settlement.subtract(purchases).max(BigDecimal.ZERO);

        AuditFlowsDto.Cash cash = AuditFlowsDto.Cash.builder()
                .directBankSupplierPayments(bd(directBank))
                .supplierAllocatedCashSettlements(bd(cashSettlement))
                .realOutstandingSupplierDebt(bd(debt))
                .supplierSettlementAndDebt(settlement)
                .documentedSupplierPurchases(purchases)
                .excessOverDocumentedPurchases(excess)
                .uncoveredPurchaseBalance(purchases.subtract(settlement))
                .coverageBreach(excess.signum() > 0)
                .coverageBreachSubjects(List.of("Supplier A"))
                .build();

        return AuditFlowsDto.builder()
                .cash(cash)
                .inventory(AuditFlowsDto.Inventory.builder().products(List.of()).build())
                .documentation(AuditFlowsDto.Documentation.builder().build())
                .build();
    }

    private Optional<AuditAlertDto> find(List<AuditAlertDto> alerts, String ruleId) {
        return alerts.stream().filter(a -> ruleId.equals(a.getRuleId())).findFirst();
    }

    @Test
    void settlementAndDebtAboveDocumentedPurchasesIsCritical() {
        // 76,000 + 31,500 + 21,300 = 128,800 claimed against 100,000 documented.
        List<AuditAlertDto> alerts = engine.evaluate(
                flowsWithCoverage("76000", "31500", "21300", "100000"), List.of());

        AuditAlertDto breach = find(alerts, "SUPPLIER_COVERAGE_BREACH").orElse(null);
        assertNotNull(breach, "claiming more settlement than was ever bought must fire");
        assertEquals("CRITICAL", breach.getSeverity());
        assertEquals(0, breach.getAmount().compareTo(bd("28800")),
                "the excess is 128,800 − 100,000");
        assertEquals(List.of("Supplier A"), breach.getSubjects(),
                "the counterparties responsible must be named");
    }

    @Test
    void coverageWithinDocumentedPurchasesDoesNotFire() {
        List<AuditAlertDto> alerts = engine.evaluate(
                flowsWithCoverage("76000", "31500", "21300", "154600"), List.of());

        assertTrue(find(alerts, "SUPPLIER_COVERAGE_BREACH").isEmpty(),
                "settlement inside documented purchases is the normal case");
    }

    @Test
    void anUncoveredResidualIsReportedButIsNotCritical() {
        List<AuditAlertDto> alerts = engine.evaluate(
                flowsWithCoverage("76000", "31500", "21300", "154600"), List.of());

        AuditAlertDto residual = find(alerts, "UNCOVERED_PURCHASE_BALANCE").orElse(null);
        assertNotNull(residual, "a positive residual must still be visible");
        assertEquals("MEDIUM", residual.getSeverity(),
                "a residual is not automatically an error — it may be a legitimate explanation");
        assertEquals(0, residual.getAmount().compareTo(bd("25800")));
    }

    @Test
    void everyAlertExplainsItsFormulaAndInputs() {
        List<AuditAlertDto> alerts = engine.evaluate(
                flowsWithCoverage("76000", "31500", "21300", "100000"), List.of());

        assertTrue(alerts.size() > 0, "the fixture is meant to fire at least one rule");
        for (AuditAlertDto alert : alerts) {
            assertNotNull(alert.getFormula(), alert.getRuleId() + " must state its formula");
            assertNotNull(alert.getInputs(), alert.getRuleId() + " must expose its inputs");
            assertTrue(alert.getInputs().size() > 0,
                    alert.getRuleId() + " must show the numbers it was evaluated on");
        }
    }

    @Test
    void unresolvedWithdrawalsAreReportedWithTheirRowCount() {
        AuditFlowsDto flows = flowsWithCoverage("0", "0", "0", "0");
        flows.getCash().setBankOutflow(bd("62000"));
        flows.getCash().setMappedWithdrawalAmount(bd("52100"));
        flows.getCash().setUnresolvedWithdrawalAmount(bd("9900"));
        flows.getCash().setUnmappedOutflowCount(7);

        AuditAlertDto alert = find(engine.evaluate(flows, List.of()), "CASH_WITHDRAWAL_UNRESOLVED")
                .orElse(null);

        assertNotNull(alert, "unresolved cash must never be silently absorbed");
        assertEquals(0, alert.getAmount().compareTo(bd("9900")));
        assertEquals(7, alert.getAffectedRowCount());
    }

    @Test
    void criticalAlertsSortAboveLesserOnes() {
        AuditFlowsDto flows = flowsWithCoverage("76000", "31500", "21300", "100000");
        flows.getCash().setUnmappedInflowAmount(bd("7400"));
        flows.getCash().setUnmappedInflowCount(2);

        List<AuditAlertDto> alerts = engine.evaluate(flows, List.of());

        assertEquals("CRITICAL", alerts.get(0).getSeverity(),
                "the worst finding must be the first one a reader sees");
    }
}
