package ge.tastyerp.payment.service.audit;

import ge.tastyerp.common.dto.audit.*;
import ge.tastyerp.common.dto.config.CategoryLedgerInputDto;
import ge.tastyerp.common.dto.config.FormalSalesCustomerDto;
import ge.tastyerp.common.dto.waybill.WaybillType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity tests for BOR-76 dual-ledger analytics against the issue's worked
 * examples (3 per part). {@link DualLedgerService#compute} is pure, so no
 * network is involved.
 */
class DualLedgerServiceTest {

    private final DualLedgerService svc = new DualLedgerService(null);
    private static final LocalDate S = LocalDate.of(2026, 6, 1);
    private static final LocalDate E = LocalDate.of(2026, 6, 30);
    private static final String CAT = ProductHierarchy.BEEF;

    private static BigDecimal bd(String v) { return new BigDecimal(v); }

    private static ProductMovementDto pm(WaybillType type, String category, String kg, String amount, String counterparty) {
        return ProductMovementDto.builder()
                .date(S).type(type).productName("x").parentCategory(category)
                .quantityKg(bd(kg)).unit("კგ").amount(bd(amount)).counterpartyId(counterparty)
                .build();
    }

    private DualLedgerDto run(List<ProductMovementDto> movements,
                              Map<String, CategoryLedgerInputDto> inputs,
                              Map<String, FormalSalesCustomerDto> formal,
                              Map<String, BigDecimal> writeOff) {
        return run(movements, inputs, formal, writeOff, Map.of());
    }

    private DualLedgerDto run(List<ProductMovementDto> movements,
                              Map<String, CategoryLedgerInputDto> inputs,
                              Map<String, FormalSalesCustomerDto> formal,
                              Map<String, BigDecimal> writeOff,
                              Map<String, BigDecimal> productVatRates) {
        return svc.compute(movements, S, E, null,
                inputs == null ? Map.of() : inputs,
                formal == null ? Map.of() : formal,
                writeOff == null ? Map.of() : writeOff,
                productVatRates == null ? Map.of() : productVatRates);
    }

    private static Map<String, CategoryLedgerInputDto> input(CategoryLedgerInputDto in) {
        return Map.of(CAT, in);
    }

    private static void assertGap(BigDecimal actual, String expected) {
        assertEquals(0, actual.compareTo(bd(expected)), "gap should be " + expected + " but was " + actual);
    }

    // ==================== Part 1: Purchase Cash Shortage ====================

    @Test
    void purchaseShortage_standard() {
        // 100kg @20.06 documented (=2006), real 100kg @26 (=2600) -> -594
        DualLedgerDto r = run(List.of(pm(WaybillType.PURCHASE, CAT, "100", "2006", null)),
                input(CategoryLedgerInputDto.builder().category(CAT).realPurchasePrice(bd("26")).build()),
                null, null);
        assertGap(r.getPurchaseShortages().get(0).getGap(), "-594");
        assertGap(r.getTotalPurchaseShortage(), "-594");
    }

    @Test
    void purchaseShortage_highMarginGap() {
        // 500kg @15 (=7500), real @22 (=11000) -> -3500
        DualLedgerDto r = run(List.of(pm(WaybillType.PURCHASE, CAT, "500", "7500", null)),
                input(CategoryLedgerInputDto.builder().category(CAT).realPurchasePrice(bd("22")).build()),
                null, null);
        assertGap(r.getPurchaseShortages().get(0).getGap(), "-3500");
    }

    @Test
    void purchaseShortage_zeroGap() {
        // 200kg @25 (=5000), real @25 -> 0 (real defaults to documented when no override)
        DualLedgerDto r = run(List.of(pm(WaybillType.PURCHASE, CAT, "200", "5000", null)),
                null, null, null);
        assertGap(r.getPurchaseShortages().get(0).getGap(), "0");
    }

    // ==================== Part 2: Sales Cash Surplus ====================

    @Test
    void saleSurplus_standard() {
        // 100kg @24 (=2400) documented, real @28 (=2800) -> +400
        DualLedgerDto r = run(List.of(pm(WaybillType.SALE, CAT, "100", "2400", null)),
                input(CategoryLedgerInputDto.builder().category(CAT).realSalePrice(bd("28")).build()),
                null, null);
        assertGap(r.getSaleSurpluses().get(0).getGap(), "400");
        assertGap(r.getTotalSaleSurplus(), "400");
    }

    @Test
    void saleSurplus_premiumRealSale() {
        // 300kg @20 (=6000), real @30 (=9000) -> +3000
        DualLedgerDto r = run(List.of(pm(WaybillType.SALE, CAT, "300", "6000", null)),
                input(CategoryLedgerInputDto.builder().category(CAT).realSalePrice(bd("30")).build()),
                null, null);
        assertGap(r.getSaleSurpluses().get(0).getGap(), "3000");
    }

    @Test
    void saleSurplus_discountedPaper() {
        // 50kg @10 (=500), real @20 (=1000) -> +500
        DualLedgerDto r = run(List.of(pm(WaybillType.SALE, CAT, "50", "500", null)),
                input(CategoryLedgerInputDto.builder().category(CAT).realSalePrice(bd("20")).build()),
                null, null);
        assertGap(r.getSaleSurpluses().get(0).getGap(), "500");
    }

    // ==================== Part 3: Formal Commission AR ====================

    private DualLedgerDto formalRun(String kg, String amount, String rate, String id) {
        Map<String, FormalSalesCustomerDto> formal = new LinkedHashMap<>();
        formal.put(id, FormalSalesCustomerDto.builder().customerId(id).customerName("D").commissionPerKg(bd(rate)).build());
        return run(List.of(pm(WaybillType.SALE, CAT, kg, amount, id)), null, formal, null);
    }

    @Test
    void formalCommission_standard() {
        // 200kg @0.50 -> +100 ; documented AR (6844) ignored for real cash
        DualLedgerDto r = formalRun("200", "6844", "0.50", "12345678");
        FormalCommissionDto f = r.getFormalCommissions().get(0);
        assertEquals(0, f.getCommissionAr().compareTo(bd("100")));
        assertEquals(0, f.getDocumentedAr().compareTo(bd("6844")));
        assertEquals(0, r.getTotalFormalCommission().compareTo(bd("100")));
    }

    @Test
    void formalCommission_highVolume() {
        DualLedgerDto r = formalRun("1000", "34220", "1.00", "12345678");
        assertEquals(0, r.getFormalCommissions().get(0).getCommissionAr().compareTo(bd("1000")));
    }

    @Test
    void formalCommission_lowRate() {
        DualLedgerDto r = formalRun("50", "1711", "0.25", "12345678");
        assertEquals(0, r.getFormalCommissions().get(0).getCommissionAr().compareTo(bd("12.50")));
    }

    @Test
    void formalCommission_mergesLeadingZeroTin() {
        // Movement counterparty "012345678" must match a formal customer stored as canonical "12345678".
        Map<String, FormalSalesCustomerDto> formal = new LinkedHashMap<>();
        formal.put("12345678", FormalSalesCustomerDto.builder().customerId("12345678").commissionPerKg(bd("0.50")).build());
        DualLedgerDto r = run(List.of(pm(WaybillType.SALE, CAT, "200", "6844", "012345678")), null, formal, null);
        assertEquals(0, r.getFormalCommissions().get(0).getCommissionAr().compareTo(bd("100")));
    }

    // ==================== Part 4: VAT ====================

    @Test
    void vat_actualOutputMinusInput() {
        // Actual: purchase 2006 -> input VAT 306.00 ; sale 2395.40 -> output VAT 365.40 ; payable 59.40
        DualLedgerDto r = run(List.of(
                pm(WaybillType.PURCHASE, CAT, "100", "2006", null),
                pm(WaybillType.SALE, CAT, "70", "2395.40", null)), null, null, null);
        CategoryVatDto v = r.getVat().get(0);
        assertEquals(0, v.getPurchaseVat().compareTo(bd("306.00")), "input VAT");
        assertEquals(0, v.getSalesVat().compareTo(bd("365.40")), "output VAT");
        assertEquals(0, v.getVatPayable().compareTo(bd("59.40")), "VAT payable");
    }

    @Test
    void vat_projection30Percent() {
        // What-if: 100kg purchased, 30% write-off -> 70kg sold @34.22 -> proj VAT payable 59.40
        DualLedgerDto r = run(List.of(pm(WaybillType.PURCHASE, CAT, "100", "2006", null)),
                input(CategoryLedgerInputDto.builder().category(CAT).docSalePrice(bd("34.22")).build()),
                null, Map.of(CAT, bd("30")));
        assertEquals(0, r.getVat().get(0).getProjectedVatPayable().compareTo(bd("59.40")));
    }

    @Test
    void vat_projection0Percent() {
        DualLedgerDto r = run(List.of(pm(WaybillType.PURCHASE, CAT, "100", "2006", null)),
                input(CategoryLedgerInputDto.builder().category(CAT).docSalePrice(bd("34.22")).build()),
                null, Map.of(CAT, bd("0")));
        assertEquals(0, r.getVat().get(0).getProjectedVatPayable().compareTo(bd("216.00")));
    }

    @Test
    void vat_projection15Percent() {
        // 85kg @34.22 = 2908.70 -> VAT 443.70 -> payable 137.70.
        // (The ticket shows 443.69/137.69 — a 1-tetri rounding slip; 2908.70×18/118 = 443.70 exactly.)
        DualLedgerDto r = run(List.of(pm(WaybillType.PURCHASE, CAT, "100", "2006", null)),
                input(CategoryLedgerInputDto.builder().category(CAT).docSalePrice(bd("34.22")).build()),
                null, Map.of(CAT, bd("15")));
        assertEquals(0, r.getVat().get(0).getProjectedVatPayable().compareTo(bd("137.70")));
    }

    @Test
    void vat_perProductZeroRateHasNoOutputVat() {
        // A product set to 0% VAT (VAT-exempt sheep/chicken) yields no output VAT.
        DualLedgerDto r = run(List.of(pm(WaybillType.SALE, CAT, "100", "3422", null)),
                null, null, null, Map.of("x", bd("0")));
        CategoryVatDto v = r.getVat().get(0);
        assertEquals(0, v.getSalesVat().compareTo(bd("0")), "0% product -> no output VAT");
        assertEquals(0, v.getVatPayable().compareTo(bd("0")));
    }

    // ==================== Supplies ====================

    @Test
    void supplies_inputVatDeductibleAndExcludedFromMeatMath() {
        // Supplies purchase 118 @ default 18% -> input VAT 18.00 (deductible).
        DualLedgerDto r = run(List.of(pm(WaybillType.PURCHASE, ProductHierarchy.SUPPLIES, "0", "118", null)),
                null, null, null, Map.of());
        assertEquals(1, r.getSupplies().size());
        assertEquals(0, r.getTotalSuppliesSpend().compareTo(bd("118")));
        assertEquals(0, r.getTotalSuppliesInputVat().compareTo(bd("18")), "18% of 118");
        // Excluded from cash gaps and from the meat VAT list...
        assertTrue(r.getPurchaseShortages().isEmpty(), "supplies not a purchase shortage");
        assertTrue(r.getVat().isEmpty(), "supplies not a meat VAT row");
        // ...but its input VAT reduces the overall payable: 0 (meat) - 18 = -18.
        assertEquals(0, r.getTotalVatPayable().compareTo(bd("-18")));
    }

    // ==================== Determinism ====================

    @Test
    void determinism_repeatedCallsIdentical() {
        List<ProductMovementDto> m = List.of(
                pm(WaybillType.PURCHASE, CAT, "100", "2006", null),
                pm(WaybillType.SALE, CAT, "70", "2395.40", null));
        DualLedgerDto a = run(m, null, null, null);
        DualLedgerDto b = run(m, null, null, null);
        assertEquals(a.getTotalVatPayable(), b.getTotalVatPayable());
        assertEquals(a.getTotalPurchaseShortage(), b.getTotalPurchaseShortage());
    }
}
