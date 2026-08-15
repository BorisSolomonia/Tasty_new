package ge.tastyerp.payment.audit;

import ge.tastyerp.common.dto.audit.ProductMovementDto;
import ge.tastyerp.common.dto.auditlayer.AuditCategoryDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingSplitDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingStatus;
import ge.tastyerp.common.dto.auditlayer.AuditOverviewDto;
import ge.tastyerp.common.dto.auditlayer.AuditSourceRowDto;
import ge.tastyerp.common.dto.auditlayer.AuditSourceType;
import ge.tastyerp.common.dto.auditlayer.AuditSubgroupDto;
import ge.tastyerp.common.dto.waybill.WaybillType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * BOR-92: the top strip's arithmetic, on a hand-built period. Every figure is
 * checked against the definition in the DTO javadoc.
 */
class AuditOverviewServiceTest {

    private static final LocalDate D1 = LocalDate.of(2026, 8, 1);
    private static final LocalDate D5 = LocalDate.of(2026, 8, 5);
    private static final LocalDate D9 = LocalDate.of(2026, 8, 9);
    private static final String SUP_A = "204900358", SUP_B = "402297787", CUST_REAL = "111111111", CUST_UNREAL = "222222222";

    private final AuditOverviewService service = new AuditOverviewService(
            mock(AuditSourceRowService.class), mock(AuditMappingService.class), mock(AuditConfigClient.class));

    private static ProductMovementDto mv(WaybillType type, LocalDate d, String product, String cat, String kg, String amount, String tin) {
        return ProductMovementDto.builder().type(type).date(d).productName(product).parentCategory(cat)
                .quantityKg(new BigDecimal(kg)).unit("კგ").amount(new BigDecimal(amount)).counterpartyId(tin).waybillId("w").build();
    }

    private static AuditMappingSplitDto split(String cat, String sub, String tin, String amount) {
        return AuditMappingSplitDto.builder().categoryCode(cat).subgroupCode(sub).counterpartyTin(tin).amount(new BigDecimal(amount)).build();
    }

    private static AuditSourceRowDto bank(String id, String direction, String amount, String tin, List<AuditMappingSplitDto> splits) {
        AuditMappingDto mapping = splits == null ? null : AuditMappingDto.builder().sourceType(AuditSourceType.BANK).sourceRowId(id)
                .status(AuditMappingStatus.MANUALLY_MAPPED).splits(splits).linkedSourceRows(List.of()).build();
        return AuditSourceRowDto.builder().sourceType(AuditSourceType.BANK).sourceRowId(id).direction(direction)
                .amount(new BigDecimal(amount)).counterpartyTin(tin).resolvedCounterpartyTin(tin).counterpartyName("cp " + tin).mapping(mapping).build();
    }

    private static AuditSourceRowDto doc(String id, String direction, String amount, String tin, List<AuditMappingSplitDto> splits) {
        AuditMappingDto mapping = splits == null ? null : AuditMappingDto.builder().sourceType(AuditSourceType.RS_GE).sourceRowId(id)
                .status(AuditMappingStatus.MANUALLY_MAPPED).splits(splits).linkedSourceRows(List.of()).build();
        return AuditSourceRowDto.builder().sourceType(AuditSourceType.RS_GE).sourceRowId(id).direction(direction)
                .amount(new BigDecimal(amount)).counterpartyTin(tin).mapping(mapping).build();
    }

    private AuditOverviewDto run(String chosen) {
        List<ProductMovementDto> movements = List.of(
                mv(WaybillType.PURCHASE, D1, "beef carcass", "BEEF", "100", "2000", SUP_A),
                mv(WaybillType.PURCHASE, D5, "beef carcass", "BEEF", "50", "1100", SUP_B),
                mv(WaybillType.PURCHASE, D9, "pork half", "PORK", "40", "600", SUP_A),
                mv(WaybillType.SALE, D5, "beef carcass", "BEEF", "60", "1800", CUST_REAL),
                mv(WaybillType.SALE, D9, "beef carcass", "BEEF", "20", "700", CUST_UNREAL));
        List<AuditSourceRowDto> bankRows = List.of(
                bank("b1", "DEBIT", "1500", SUP_A, List.of(
                        split(AuditCategories.SUPPLIER_BANK_PAYMENT, AuditSubgroups.CHECK_NEEDED, SUP_A, "1000"),
                        split(AuditCategories.NON_SUPPLIER_EXPENSE, null, null, "200"))),          // 300 unmapped
                bank("b2", "DEBIT", "400", SUP_B, List.of(
                        split(AuditCategories.SUPPLIER_BANK_PAYMENT, AuditSubgroups.CHECK_RECEIVED, SUP_B, "400"))),
                bank("b3", "CREDIT", "999", CUST_REAL, null),
                bank("b4", "DEBIT", "250", null, null));                                              // fully unmapped
        List<AuditSourceRowDto> documentRows = List.of(
                doc("d-unreal", "SALE", "700", CUST_UNREAL, List.of(
                        split(AuditCategories.PAPER_ONLY_SUPPLIER_PAYMENT, AuditSubgroups.CHECK_NEEDED, SUP_A, "500"))),
                doc("d-real", "SALE", "1800", CUST_REAL, null));
        Map<String, AuditCategoryDto> categories = new LinkedHashMap<>();
        AuditCategories.builtIns().forEach(c -> categories.put(c.getCode(), c));
        Map<String, AuditSubgroupDto> subgroups = new LinkedHashMap<>();
        AuditSubgroups.builtIns().forEach(s -> subgroups.put(s.getCode(), s));
        return service.build(D1, D9, chosen, movements, bankRows, documentRows, categories, subgroups,
                Map.of(), Map.of("BEEF", new BigDecimal("28"), "PORK", new BigDecimal("25")),
                Set.of(CUST_UNREAL), Map.of(SUP_A, "Supplier A", SUP_B, "Supplier B"));
    }

    @Test
    @DisplayName("purchases: total, chosen supplier, by category")
    void purchases() {
        AuditOverviewDto o = run(SUP_A);
        assertEquals(new BigDecimal("3700.00"), o.getPurchases().getTotal());
        assertEquals(new BigDecimal("2600.00"), o.getPurchases().getChosen());
        assertEquals("BEEF", o.getPurchases().getByCategory().get(0).getCategory());
        assertEquals(new BigDecimal("3100.00"), o.getPurchases().getByCategory().get(0).getAmount());
        assertEquals(new BigDecimal("2000.00"), o.getPurchases().getByCategory().get(0).getChosenAmount());
        assertEquals("Supplier A", o.getSupplierName());
        assertNull(run(null).getPurchases().getChosen(), "no supplier chosen → null, not zero");
    }

    @Test
    @DisplayName("bank payments to suppliers: only supplier-settlement slices of DEBIT rows count; expense and unmapped do not")
    void bankPayments() {
        AuditOverviewDto o = run(SUP_A);
        assertEquals(new BigDecimal("1400.00"), o.getBankPaymentsToSuppliers().getTotal());
        assertEquals(new BigDecimal("1000.00"), o.getBankPaymentsToSuppliers().getToChosen());
        assertEquals(SUP_A, o.getBankPaymentsToSuppliers().getBySupplier().get(0).getTin());
    }

    @Test
    @DisplayName("cash outflow: every DEBIT row; unmapped = uncovered part; group → subgroup → counterparty tree")
    void cashOutflow() {
        AuditOverviewDto o = run(SUP_A);
        AuditOverviewDto.CashOutflow c = o.getCashOutflow();
        assertEquals(new BigDecimal("2150.00"), c.getTotal());          // 1500 + 400 + 250
        assertEquals(new BigDecimal("550.00"), c.getUnmapped());        // 300 + 250
        assertEquals(new BigDecimal("1600.00"), c.getMapped());
        assertEquals(3, c.getDebitRowCount());
        assertEquals(2, c.getUnmappedRowCount());
        assertEquals(new BigDecimal("1500.00"), c.getToChosen());       // the whole row went to A: 1000 + 200 (expense, row tin) + 300 unmapped

        AuditOverviewDto.Bucket supplierGroup = c.getGroups().stream()
                .filter(g -> AuditCategories.SUPPLIER_BANK_PAYMENT.equals(g.getCode())).findFirst().orElseThrow();
        assertEquals(new BigDecimal("1400.00"), supplierGroup.getAmount());
        AuditOverviewDto.Bucket checkNeeded = supplierGroup.getChildren().stream()
                .filter(s -> AuditSubgroups.CHECK_NEEDED.equals(s.getCode())).findFirst().orElseThrow();
        assertEquals(new BigDecimal("1000.00"), checkNeeded.getAmount());
        assertEquals(SUP_A, checkNeeded.getChildren().get(0).getTin());
        assertEquals("Supplier A", checkNeeded.getChildren().get(0).getLabel());
        AuditOverviewDto.Bucket unmappedGroup = c.getGroups().stream()
                .filter(g -> AuditSubgroups.UNMAPPED.equals(g.getCode())).findFirst().orElseThrow();
        assertEquals(new BigDecimal("550.00"), unmappedGroup.getAmount());
        // a slice with no subgroup lands under NONE, never disappears
        AuditOverviewDto.Bucket expense = c.getGroups().stream()
                .filter(g -> AuditCategories.NON_SUPPLIER_EXPENSE.equals(g.getCode())).findFirst().orElseThrow();
        assertEquals(AuditSubgroups.NONE, expense.getChildren().get(0).getCode());
    }

    @Test
    @DisplayName("sales: unreal = to unreal customers; real = total − unreal; the chained slice is paper outflow under the supplier, never real")
    void salesAndPaperChain() {
        AuditOverviewDto o = run(null);
        assertEquals(new BigDecimal("2500.00"), o.getSales().getTotal());
        assertEquals(new BigDecimal("700.00"), o.getSales().getUnreal());
        assertEquals(new BigDecimal("1800.00"), o.getSales().getReal());
        assertEquals(new BigDecimal("500.00"), o.getSales().getUnrealMapped());
        assertEquals(new BigDecimal("200.00"), o.getSales().getUnrealUnmapped());
        assertEquals(CUST_UNREAL, o.getSales().getUnrealCustomers().get(0).getTin());

        AuditOverviewDto.CashOutflow c = o.getCashOutflow();
        assertEquals(new BigDecimal("500.00"), c.getPaperTotal());
        assertEquals(new BigDecimal("2150.00"), c.getTotal(), "paper never enters the real total");
        AuditOverviewDto.Bucket paper = c.getPaperGroups().get(0);
        assertEquals(AuditCategories.PAPER_ONLY_SUPPLIER_PAYMENT, paper.getCode());
        assertEquals(AuditSubgroups.CHECK_NEEDED, paper.getChildren().get(0).getCode());
        assertEquals(SUP_A, paper.getChildren().get(0).getChildren().get(0).getTin());
        assertEquals(new BigDecimal("500.00"), o.getSuppliers().get(0).getPaperOutflow());
    }

    @Test
    @DisplayName("inventory: net = purchased − write-off − sold; remaining kg attributed latest-first (LIFO)")
    void inventoryLifo() {
        AuditOverviewDto o = run(null);
        AuditOverviewDto.InventoryCategory beef = o.getInventory().getByCategory().stream()
                .filter(x -> "BEEF".equals(x.getCategory())).findFirst().orElseThrow();
        // 150 purchased × (1 − 0.28) = 108 net purchases − 80 sold = 28 kg left
        assertEquals(new BigDecimal("150.000"), beef.getPurchasedKg());
        assertEquals(new BigDecimal("80.000"), beef.getSoldKg());
        assertEquals(new BigDecimal("28.000"), beef.getNetKg());
        // LIFO: latest lot is Supplier B on D5 (50 kg × 0.72 = 36 net) → covers all 28 kg
        assertEquals(1, beef.getStockBySupplier().size());
        assertEquals(SUP_B, beef.getStockBySupplier().get(0).getTin());
        assertEquals(new BigDecimal("28.000"), beef.getStockBySupplier().get(0).getQuantityKg());
        // Pork: 40 × 0.75 = 30 kg, nothing sold → all from A
        AuditOverviewDto.InventoryCategory pork = o.getInventory().getByCategory().stream()
                .filter(x -> "PORK".equals(x.getCategory())).findFirst().orElseThrow();
        assertEquals(new BigDecimal("30.000"), pork.getNetKg());
        assertEquals(SUP_A, pork.getStockBySupplier().get(0).getTin());
        assertTrue(o.getNotes().stream().anyMatch(n -> n.contains("Opening stock is not recorded")));
    }

    @Test
    @DisplayName("LIFO walks across lots and never attributes more than what remains")
    void lifoAcrossLots() {
        List<AuditOverviewService.Lot> lots = List.of(
                new AuditOverviewService.Lot(SUP_A, D1, new BigDecimal("100")),
                new AuditOverviewService.Lot(SUP_B, D5, new BigDecimal("10")));
        List<AuditOverviewDto.SupplierKg> out = AuditOverviewService.lifo(lots, new BigDecimal("50"), BigDecimal.ZERO, t -> t);
        assertEquals(2, out.size());
        assertEquals(SUP_A, out.get(0).getTin());
        assertEquals(new BigDecimal("40.000"), out.get(0).getQuantityKg());
        assertEquals(new BigDecimal("10.000"), out.get(1).getQuantityKg());
        assertTrue(AuditOverviewService.lifo(lots, new BigDecimal("-5"), BigDecimal.ZERO, t -> t).isEmpty());
    }
}
