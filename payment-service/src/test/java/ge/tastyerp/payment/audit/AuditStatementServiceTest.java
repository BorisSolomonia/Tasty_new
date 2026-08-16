package ge.tastyerp.payment.audit;

import ge.tastyerp.common.dto.audit.ProductMovementDto;
import ge.tastyerp.common.dto.auditlayer.AuditCategoryDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingSplitDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingStatus;
import ge.tastyerp.common.dto.auditlayer.AuditSourceRowDto;
import ge.tastyerp.common.dto.auditlayer.AuditSourceType;
import ge.tastyerp.common.dto.auditlayer.AuditStatementDto;
import ge.tastyerp.common.dto.auditlayer.AuditStatementDto.Party;
import ge.tastyerp.common.dto.auditlayer.AuditStatementDto.Selection;
import ge.tastyerp.common.dto.auditlayer.AuditStatementTransactionDto;
import ge.tastyerp.common.dto.auditlayer.AuditSubgroupDto;
import ge.tastyerp.common.dto.payment.PaymentDto;
import ge.tastyerp.common.dto.waybill.WaybillType;
import ge.tastyerp.common.exception.ValidationException;
import ge.tastyerp.payment.repository.PaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The statement's arithmetic (BOR-92 v2). Every row is checked against a hand
 * computed figure, and "chosen" is checked both empty (null, never zero) and
 * with a selection.
 */
class AuditStatementServiceTest {

    private static final LocalDate D1 = LocalDate.of(2026, 8, 1);
    private static final LocalDate D5 = LocalDate.of(2026, 8, 5);
    private static final LocalDate D9 = LocalDate.of(2026, 8, 9);
    private static final String SUP_A = "200000001";
    private static final String SUP_B = "200000002";
    private static final String CUST_REAL = "300000001";
    private static final String CUST_UNREAL = "300000002";

    private final AuditStatementService service = new AuditStatementService(
            mock(AuditSourceRowService.class), mock(AuditMappingService.class), mock(AuditConfigClient.class),
            mock(AuditLayerRepository.class), mock(PaymentRepository.class));

    private static ProductMovementDto mv(WaybillType type, LocalDate d, String product, String cat, String kg, String amount, String tin, String name) {
        return ProductMovementDto.builder().type(type).date(d).productName(product).parentCategory(cat)
                .quantityKg(new BigDecimal(kg)).unit("კგ").amount(new BigDecimal(amount)).counterpartyId(tin).counterpartyName(name).waybillId("w-" + product.hashCode()).build();
    }

    private static AuditMappingSplitDto split(String cat, String sub, String tin, String amount) {
        return AuditMappingSplitDto.builder().categoryCode(cat).subgroupCode(sub).counterpartyTin(tin).amount(new BigDecimal(amount)).build();
    }

    private static AuditSourceRowDto bank(String id, String direction, String amount, String tin, List<AuditMappingSplitDto> splits) {
        AuditMappingDto mapping = splits == null ? null : AuditMappingDto.builder().sourceType(AuditSourceType.BANK).sourceRowId(id)
                .status(AuditMappingStatus.MANUALLY_MAPPED).splits(splits).linkedSourceRows(List.of()).build();
        BigDecimal covered = mapping == null ? BigDecimal.ZERO : AuditMappingService.splitTotal(splits);
        return AuditSourceRowDto.builder().sourceType(AuditSourceType.BANK).sourceRowId(id).direction(direction).date(D5)
                .amount(new BigDecimal(amount)).counterpartyTin(tin).resolvedCounterpartyTin(tin).counterpartyName(tin == null ? "ATM" : "cp " + tin)
                .mapping(mapping).status(mapping == null ? AuditMappingStatus.UNMAPPED : AuditMappingStatus.MANUALLY_MAPPED)
                .unresolvedAmount(new BigDecimal(amount).subtract(covered)).build();
    }

    private static PaymentDto pay(String id, String tin, String name, String amount, String source) {
        return PaymentDto.builder().id(id).customerId(tin).customerName(name).amount(new BigDecimal(amount)).paymentDate(D5).source(source).build();
    }

    private AuditStatementService.Inputs inputs() {
        List<ProductMovementDto> movements = List.of(
                mv(WaybillType.PURCHASE, D1, "beef carcass", "BEEF", "100", "2000", SUP_A, "Supplier A"),
                mv(WaybillType.PURCHASE, D5, "beef carcass", "BEEF", "50", "1100", SUP_B, "Supplier B"),
                mv(WaybillType.PURCHASE, D9, "pork half", "PORK", "40", "600", SUP_A, "Supplier A"),
                mv(WaybillType.SALE, D5, "beef carcass", "BEEF", "60", "1800", CUST_REAL, "Real Customer"),
                mv(WaybillType.SALE, D9, "beef carcass", "BEEF", "20", "700", CUST_UNREAL, "Unreal Customer"));
        // Document rows aligned with movements (what toDocumentRows produces).
        List<AuditSourceRowDto> docs = new ArrayList<>();
        int i = 0;
        for (ProductMovementDto m : movements) {
            docs.add(AuditSourceRowDto.builder().sourceType(AuditSourceType.RS_GE).sourceRowId("doc-" + (i++))
                    .direction(m.getType().name()).amount(m.getAmount()).counterpartyTin(m.getCounterpartyId())
                    .status(AuditMappingStatus.UNMAPPED).build());
        }
        List<AuditSourceRowDto> bankRows = List.of(
                bank("b1", "DEBIT", "1500", SUP_A, List.of(
                        split(AuditCategories.SUPPLIER_BANK_PAYMENT, AuditSubgroups.CHECK_NEEDED, SUP_A, "1000"),
                        split(AuditCategories.NON_SUPPLIER_EXPENSE, null, null, "200"))),          // 300 unmapped
                bank("b2", "DEBIT", "400", SUP_B, List.of(
                        split(AuditCategories.SUPPLIER_BANK_PAYMENT, AuditSubgroups.CHECK_RECEIVED, SUP_B, "400"))),
                bank("b3", "CREDIT", "999", CUST_REAL, null),
                bank("b4", "DEBIT", "250", null, null));                                              // fully unmapped, no TIN
        List<PaymentDto> bankPayments = List.of(
                pay("p1", CUST_REAL, "Real Customer", "500", "tbc"),
                pay("p2", CUST_UNREAL, "Unreal Customer", "120", "tbc"));
        List<PaymentDto> cashPayments = List.of(pay("c1", CUST_REAL, "Real Customer", "80", "manual-cash"));
        Map<String, AuditCategoryDto> categories = new LinkedHashMap<>();
        AuditCategories.builtIns().forEach(c -> categories.put(c.getCode(), c));
        Map<String, AuditSubgroupDto> subgroups = new LinkedHashMap<>();
        AuditSubgroups.builtIns().forEach(s -> subgroups.put(s.getCode(), s));
        return new AuditStatementService.Inputs(movements, docs, bankRows, bankPayments, cashPayments, categories, subgroups,
                Map.of(), Map.of("BEEF", new BigDecimal("28"), "PORK", new BigDecimal("25")),
                Set.of(CUST_UNREAL), Map.of());
    }

    private AuditStatementDto run(List<String> suppliers, List<String> customers) {
        return service.build(D1, D9, "boris", Selection.builder().suppliers(suppliers).customers(customers).build(), inputs());
    }

    private static Party party(List<Party> parties, String tin) {
        return parties.stream().filter(p -> tin.equals(p.getTin())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("nothing chosen → every 'chosen' figure is null, never zero, and the notes say so")
    void nothingChosen() {
        AuditStatementDto s = run(List.of(), List.of());
        assertNull(s.getPurchases().getChosen());
        assertNull(s.getBankPaymentsToSuppliers().getChosen());
        assertNull(s.getCashOutflow().getChosen());
        assertNull(s.getSales().getChosen());
        assertNull(s.getBankInflow().getChosen());
        assertNull(s.getCashInflow().getChosen());
        assertTrue(s.getNotes().stream().anyMatch(n -> n.contains("No supplier is chosen")));
        assertTrue(s.getPurchases().getParties().stream().noneMatch(Party::isChosen));
    }

    @Test
    @DisplayName("purchases: total 3700 / 190 kg; chosen supplier A → 2600 / 140 kg; groups carry chosen columns")
    void purchases() {
        AuditStatementDto s = run(List.of(SUP_A), List.of());
        assertEquals(new BigDecimal("3700.00"), s.getPurchases().getTotal());
        assertEquals(new BigDecimal("190.000"), s.getPurchases().getTotalKg());
        assertEquals(new BigDecimal("2600.00"), s.getPurchases().getChosen());
        assertEquals(new BigDecimal("140.000"), s.getPurchases().getChosenKg());
        assertTrue(party(s.getPurchases().getParties(), SUP_A).isChosen());
        assertFalse(party(s.getPurchases().getParties(), SUP_B).isChosen());
        assertEquals("Supplier B", party(s.getPurchases().getParties(), SUP_B).getName(), "name from the RS.ge document");
        assertEquals("BEEF", s.getPurchases().getProducts().get(0).getCategory());
        assertEquals(new BigDecimal("3100.00"), s.getPurchases().getProducts().get(0).getAmount());
        assertEquals(new BigDecimal("2000.00"), s.getPurchases().getProducts().get(0).getChosenAmount());
        assertEquals(1, s.getPurchases().getProducts().get(0).getProductCount());
    }

    @Test
    @DisplayName("bank payments to suppliers: only supplier-settlement slices count (1400); chosen A → 1000")
    void bankPaymentsToSuppliers() {
        AuditStatementDto s = run(List.of(SUP_A), List.of());
        assertEquals(new BigDecimal("1400.00"), s.getBankPaymentsToSuppliers().getTotal());
        assertEquals(new BigDecimal("1000.00"), s.getBankPaymentsToSuppliers().getChosen());
        assertEquals(2, s.getBankPaymentsToSuppliers().getParties().size());
    }

    @Test
    @DisplayName("cash outflow: every debit (2150), unmapped 550 on 3 rows; chosen A = whole row b1 (1500); nameless row listed as its own party")
    void cashOutflow() {
        AuditStatementDto s = run(List.of(SUP_A), List.of());
        assertEquals(new BigDecimal("2150.00"), s.getCashOutflow().getTotal());
        assertEquals(new BigDecimal("550.00"), s.getCashOutflow().getSecondary());
        assertEquals("unmapped", s.getCashOutflow().getSecondaryLabel());
        assertEquals(new BigDecimal("1500.00"), s.getCashOutflow().getChosen());
        assertEquals(3, s.getCashOutflow().getRowCount());
        Party a = party(s.getCashOutflow().getParties(), SUP_A);
        assertEquals(new BigDecimal("1500.00"), a.getAmount());
        assertEquals(new BigDecimal("300.00"), a.getSecondary(), "unmapped part of A's row");
        Party atm = party(s.getCashOutflow().getParties(), "name:ATM");
        assertEquals(new BigDecimal("250.00"), atm.getAmount());
        assertFalse(atm.isChosen(), "a party without a TIN can never be chosen");
    }

    @Test
    @DisplayName("sales: total 2500, real 1800 (unreal customer excluded); chosen customers use the customer set")
    void sales() {
        AuditStatementDto s = run(List.of(SUP_A), List.of(CUST_UNREAL));
        assertEquals(new BigDecimal("2500.00"), s.getSales().getTotal());
        assertEquals(new BigDecimal("1800.00"), s.getSales().getSecondary());
        assertEquals("real", s.getSales().getSecondaryLabel());
        assertEquals(new BigDecimal("700.00"), s.getSales().getChosen());
        assertTrue(party(s.getSales().getParties(), CUST_UNREAL).isUnreal());
        assertFalse(party(s.getSales().getParties(), CUST_REAL).isUnreal());
    }

    @Test
    @DisplayName("inflows come from the payments module as-is: bank 620 (chosen unreal 120), cash 80")
    void inflows() {
        AuditStatementDto s = run(List.of(), List.of(CUST_UNREAL));
        assertEquals(new BigDecimal("620.00"), s.getBankInflow().getTotal());
        assertEquals(new BigDecimal("120.00"), s.getBankInflow().getChosen());
        assertEquals(new BigDecimal("80.00"), s.getCashInflow().getTotal());
        assertEquals(new BigDecimal("0.00"), s.getCashInflow().getChosen());
        assertEquals("Real Customer", party(s.getCashInflow().getParties(), CUST_REAL).getName());
    }

    @Test
    @DisplayName("inventory: BEEF net 28 kg valued at the period's avg purchase price (3100/150 = 20.67 → 578.67); LIFO from B; PORK unpriced sold nothing")
    void inventory() {
        AuditStatementDto s = run(List.of(), List.of());
        AuditStatementDto.Level beef = s.getInventory().getLevels().stream().filter(l -> l.getCategory().equals("BEEF")).findFirst().orElseThrow();
        assertEquals(new BigDecimal("28.000"), beef.getNetKg());          // 150 − 42 − 80
        assertEquals(new BigDecimal("20.67"), beef.getAvgPurchasePricePerKg());
        assertEquals(new BigDecimal("578.67"), beef.getValue());          // 28 × 20.6667
        assertEquals(1, beef.getStockBySupplier().size());
        assertEquals(SUP_B, beef.getStockBySupplier().get(0).getTin());
        AuditStatementDto.Level pork = s.getInventory().getLevels().stream().filter(l -> l.getCategory().equals("PORK")).findFirst().orElseThrow();
        assertEquals(new BigDecimal("30.000"), pork.getNetKg());          // 40 − 10 − 0
        assertNotNull(pork.getValue());
        assertEquals(new BigDecimal("58.000"), s.getInventory().getTotalKg());
    }

    @Test
    @DisplayName("transactions: purchases by supplier / by category, outflow by counterparty (slices and unmapped remainder), inflow by customer")
    void transactions() {
        AuditStatementService.Inputs in = inputs();
        List<AuditStatementTransactionDto> a = service.transactions("purchases", SUP_A, null, 0, in);
        assertEquals(2, a.size());
        assertEquals("doc-2", a.get(0).getSourceRowId(), "newest first, id from the document row");
        assertEquals("PORK", a.get(0).getCategory());
        List<AuditStatementTransactionDto> beef = service.transactions("purchases", null, "BEEF", 0, in);
        assertEquals(2, beef.size());
        assertTrue(beef.stream().allMatch(t -> "BEEF".equals(t.getCategory())));

        List<AuditStatementTransactionDto> outA = service.transactions("cashOutflow", SUP_A, null, 0, in);
        assertEquals(1, outA.size());
        assertEquals(0, new BigDecimal("300").compareTo(outA.get(0).getUnresolvedAmount()));
        assertTrue(outA.get(0).getMappingSummary().contains("Check needed"), outA.get(0).getMappingSummary());
        List<AuditStatementTransactionDto> atm = service.transactions("cashOutflow", "name:ATM", null, 0, in);
        assertEquals(1, atm.size());
        List<AuditStatementTransactionDto> supB = service.transactions("bankPaymentsToSuppliers", SUP_B, null, 0, in);
        assertEquals(1, supB.size());

        List<AuditStatementTransactionDto> in1 = service.transactions("bankInflow", CUST_REAL, null, 0, in);
        assertEquals(1, in1.size());
        assertEquals("PAYMENT", in1.get(0).getKind());
        assertEquals(1, service.transactions("cashInflow", null, null, 0, in).size());
        assertThrows(ValidationException.class, () -> service.transactions("nope", null, null, 0, in));
    }

    @Test
    @DisplayName("selection: TINs are canonicalised and de-duplicated; the operator key is normalised; blank operator refused")
    void selection() {
        Selection s = AuditStatementService.normalize(Selection.builder()
                .suppliers(List.of(" 200000001 ", "200000001", "")).customers(null).build());
        assertEquals(List.of("200000001"), s.getSuppliers());
        assertEquals(List.of(), s.getCustomers());
        assertEquals("boris_s", AuditStatementService.operatorKey("Boris S"));
        assertThrows(ValidationException.class, () -> AuditStatementService.operatorKey("  "));
    }
}
