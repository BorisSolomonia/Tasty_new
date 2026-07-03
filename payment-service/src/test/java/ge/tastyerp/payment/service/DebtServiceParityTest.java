package ge.tastyerp.payment.service;

import ge.tastyerp.common.dto.payment.CustomerDebtDto;
import ge.tastyerp.common.dto.payment.DebtOverviewDto;
import ge.tastyerp.payment.service.DebtService.DebtInput;
import ge.tastyerp.payment.service.DebtService.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PARITY GATE for the single-source-of-truth debt (BOR device-consistency).
 *
 * Pins the new server aggregation against an INDEPENDENT reference reproduction
 * of the legacy payments-page formula
 *   currentDebt = startingDebt + totalSales - totalPayments(all sources)
 * grouped by RAW id. The ONLY allowed divergences are the two intended
 * corrections, each asserted explicitly:
 *   (1) leading-zero-split customers merge into one row (sum), and
 *   (2) values are exact 2dp BigDecimal.
 * The headline TOTAL must be byte-exact equal (summation is associative, so
 * merging rows never changes the total).
 */
class DebtServiceParityTest {

    private static BigDecimal bd(String v) { return new BigDecimal(v); }
    private static BigDecimal round(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP); }

    /** Reference: legacy formula, grouped by RAW id, BigDecimal, non-excluded total. */
    private static Map<String, Object> reference(List<DebtInput> inputs, Set<String> excludedRaw) {
        Map<String, BigDecimal> start = new HashMap<>(), sales = new HashMap<>(), pay = new HashMap<>();
        for (DebtInput in : inputs) {
            String id = in.customerId();
            switch (in.kind()) {
                case INITIAL -> start.merge(id, in.amount(), BigDecimal::add);
                case SALE -> sales.merge(id, in.amount(), BigDecimal::add);
                case BANK_PAYMENT, CASH_PAYMENT -> pay.merge(id, in.amount(), BigDecimal::add);
            }
        }
        Set<String> ids = new HashSet<>();
        ids.addAll(start.keySet()); ids.addAll(sales.keySet()); ids.addAll(pay.keySet());
        Map<String, BigDecimal> debtByRaw = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (String id : ids) {
            BigDecimal s = start.getOrDefault(id, BigDecimal.ZERO);
            BigDecimal sa = sales.getOrDefault(id, BigDecimal.ZERO);
            BigDecimal p = pay.getOrDefault(id, BigDecimal.ZERO);
            debtByRaw.put(id, round(s.add(sa).subtract(p)));
            if (!excludedRaw.contains(id)) total = total.add(s.add(sa).subtract(p));
        }
        Map<String, Object> out = new HashMap<>();
        out.put("debtByRaw", debtByRaw);
        out.put("total", round(total));
        return out;
    }

    private static CustomerDebtDto row(DebtOverviewDto o, String canonicalId) {
        return o.getCustomers().stream().filter(c -> c.getCustomerId().equals(canonicalId))
                .findFirst().orElseThrow(() -> new AssertionError("no row for " + canonicalId));
    }

    @Test
    @DisplayName("Core parity: total is byte-exact; non-split customers match the legacy formula")
    void coreParity() {
        List<DebtInput> inputs = List.of(
                new DebtInput("204900358", "Company A", bd("1000.00"), Kind.INITIAL),
                new DebtInput("204900358", "Company A", bd("2500.50"), Kind.SALE),
                new DebtInput("204900358", null, bd("1200.00"), Kind.BANK_PAYMENT),
                new DebtInput("402297787", "Company B", bd("0"), Kind.INITIAL),
                new DebtInput("402297787", "Company B", bd("999.99"), Kind.SALE),
                new DebtInput("402297787", null, bd("300.00"), Kind.CASH_PAYMENT)
        );
        DebtOverviewDto o = DebtService.aggregate(inputs, Set.of());
        @SuppressWarnings("unchecked")
        Map<String, BigDecimal> refDebt = (Map<String, BigDecimal>) reference(inputs, Set.of()).get("debtByRaw");
        BigDecimal refTotal = (BigDecimal) reference(inputs, Set.of()).get("total");

        // A: 1000 + 2500.50 - 1200 = 2300.50 ; B: 0 + 999.99 - 300 = 699.99
        assertEquals(0, row(o, "204900358").getCurrentDebt().compareTo(refDebt.get("204900358")));
        assertEquals(0, row(o, "402297787").getCurrentDebt().compareTo(refDebt.get("402297787")));
        assertEquals(0, o.getTotalOutstanding().compareTo(refTotal), "headline total must match legacy exactly");
        assertEquals(0, o.getTotalOutstanding().compareTo(bd("3000.49")));
    }

    @Test
    @DisplayName("Intended correction #1: leading-zero-split customer merges into one row = sum; total unchanged")
    void leadingZeroMerge() {
        // Same individual: RS.ge sales use "1008057492", Excel payment + initial debt use "01008057492".
        List<DebtInput> inputs = List.of(
                new DebtInput("01008057492", "Nino", bd("500.00"), Kind.INITIAL),
                new DebtInput("1008057492", "Nino Mushkudiani", bd("800.00"), Kind.SALE),
                new DebtInput("01008057492", null, bd("300.00"), Kind.BANK_PAYMENT)
        );
        DebtOverviewDto o = DebtService.aggregate(inputs, Set.of());

        // Legacy would show TWO rows; the fix shows ONE canonical row "1008057492".
        assertEquals(1, o.getCustomers().size(), "split ids must merge to a single customer");
        CustomerDebtDto merged = row(o, "1008057492");
        // 500 + 800 - 300 = 1000.00
        assertEquals(0, merged.getCurrentDebt().compareTo(bd("1000.00")));
        assertEquals(0, merged.getStartingDebt().compareTo(bd("500.00")));
        assertEquals(0, merged.getTotalSales().compareTo(bd("800.00")));
        assertEquals(0, merged.getTotalPayments().compareTo(bd("300.00")));
        // Sales name wins for display.
        assertEquals("Nino Mushkudiani", merged.getCustomerName());

        // Total is identical whether split or merged (summation is associative).
        @SuppressWarnings("unchecked")
        BigDecimal refTotal = (BigDecimal) reference(inputs, Set.of()).get("total");
        assertEquals(0, o.getTotalOutstanding().compareTo(refTotal));
        assertEquals(0, o.getTotalOutstanding().compareTo(bd("1000.00")));
    }

    @Test
    @DisplayName("Shared exclusion drops the customer from totals (by canonical id) but not per-customer debt")
    void exclusion() {
        List<DebtInput> inputs = List.of(
                new DebtInput("204900358", "A", bd("1000.00"), Kind.SALE),
                new DebtInput("01008057492", "Nino", bd("400.00"), Kind.SALE)
        );
        // Exclude using the leading-zero form; canonicalization must still match.
        DebtOverviewDto o = DebtService.aggregate(inputs, Set.of("01008057492"));

        CustomerDebtDto excluded = row(o, "1008057492");
        assertTrue(excluded.isExcluded());
        assertEquals(0, excluded.getCurrentDebt().compareTo(bd("400.00")), "per-customer debt is unaffected");

        CustomerDebtDto included = row(o, "204900358");
        assertFalse(included.isExcluded());
        // Total excludes Nino's 400 -> only 1000.
        assertEquals(0, o.getTotalOutstanding().compareTo(bd("1000.00")));
    }

    @Test
    @DisplayName("Intended correction #2: sub-cent amounts round to exact 2dp HALF_UP")
    void rounding() {
        List<DebtInput> inputs = List.of(
                new DebtInput("204900358", "A", bd("100.005"), Kind.SALE),   // -> 100.01
                new DebtInput("204900358", null, bd("0.004"), Kind.BANK_PAYMENT) // -> negligible
        );
        DebtOverviewDto o = DebtService.aggregate(inputs, Set.of());
        // 100.005 - 0.004 = 100.001 -> 100.00
        assertEquals(0, row(o, "204900358").getCurrentDebt().compareTo(bd("100.00")));
        assertEquals(2, row(o, "204900358").getCurrentDebt().scale());
    }

    @Test
    @DisplayName("Deterministic: input order does not change output")
    void deterministicOrdering() {
        List<DebtInput> a = new ArrayList<>(List.of(
                new DebtInput("204900358", "A", bd("100.00"), Kind.SALE),
                new DebtInput("402297787", "B", bd("200.00"), Kind.SALE),
                new DebtInput("01008057492", "C", bd("300.00"), Kind.SALE)
        ));
        List<DebtInput> b = new ArrayList<>(a);
        Collections.reverse(b);
        DebtOverviewDto oa = DebtService.aggregate(a, Set.of());
        DebtOverviewDto ob = DebtService.aggregate(b, Set.of());

        assertEquals(oa.getCustomers().size(), ob.getCustomers().size());
        for (int i = 0; i < oa.getCustomers().size(); i++) {
            assertEquals(oa.getCustomers().get(i).getCustomerId(), ob.getCustomers().get(i).getCustomerId(),
                    "customer ordering must be deterministic");
        }
        assertEquals(0, oa.getTotalOutstanding().compareTo(ob.getTotalOutstanding()));
    }
}
