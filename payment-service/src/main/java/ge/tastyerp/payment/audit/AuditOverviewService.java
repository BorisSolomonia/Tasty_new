package ge.tastyerp.payment.audit;

import ge.tastyerp.common.dto.audit.ProductMovementDto;
import ge.tastyerp.common.dto.auditlayer.AuditCategoryDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingSplitDto;
import ge.tastyerp.common.dto.auditlayer.AuditOverviewDto;
import ge.tastyerp.common.dto.auditlayer.AuditOverviewDto.Bucket;
import ge.tastyerp.common.dto.auditlayer.AuditOverviewDto.CategoryAmount;
import ge.tastyerp.common.dto.auditlayer.AuditOverviewDto.Counterparty;
import ge.tastyerp.common.dto.auditlayer.AuditOverviewDto.InventoryCategory;
import ge.tastyerp.common.dto.auditlayer.AuditOverviewDto.SupplierKg;
import ge.tastyerp.common.dto.auditlayer.AuditSourceRowDto;
import ge.tastyerp.common.dto.auditlayer.AuditSubgroupDto;
import ge.tastyerp.common.dto.waybill.WaybillType;
import ge.tastyerp.common.util.TinValidator;
import ge.tastyerp.common.util.UnitClassifier;
import ge.tastyerp.payment.service.audit.WriteOffCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The audit page's top strip (BOR-92): purchases · bank payments to suppliers ·
 * cash outflow · sales, each as a period total and the same figure for the
 * chosen supplier, with the drill-down tree each tile opens.
 *
 * <p>Everything is derived from what the page already loads — RS.ge document
 * lines, bank rows, mappings, categories/subgroups — so a mapping saved in the
 * workbench moves these numbers on the next read. Pure aggregation; the only
 * I/O is through the injected services.</p>
 *
 * <p>Definitions (stated again in the DTO javadoc, and repeated in the UI):</p>
 * <ul>
 *   <li><b>purchases</b> — ₾ of RS.ge PURCHASE lines; kg only where the unit is kg.</li>
 *   <li><b>bank payments to suppliers</b> — real bank DEBIT slices whose category is
 *       flagged supplierSettlement, attributed to the slice's counterparty (else the
 *       row's resolved TIN).</li>
 *   <li><b>cash outflow</b> — every bank DEBIT row; unmapped = the part no slice covers;
 *       tree = category → subgroup → counterparty. Paper outflow (unreal-sale chains
 *       mapped to a supplier) is a separate tree and never enters the real total.</li>
 *   <li><b>sales</b> — ₾ of RS.ge SALE lines; unreal = lines to customers marked unreal
 *       on /audit-control or mapped as paper-only sale; real = total − unreal.</li>
 *   <li><b>inventory</b> — purchased − write-off − sold per category (opening stock is
 *       not recorded); the remaining kg is attributed to suppliers latest-first (LIFO).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditOverviewService {

    private static final int MONEY = 2;
    private static final int KG = 3;

    private final AuditSourceRowService sourceRowService;
    private final AuditMappingService mappingService;
    private final AuditConfigClient configClient;

    public AuditOverviewDto overview(LocalDate startDate, LocalDate endDate, String supplierTin) {
        String chosen = TinValidator.canonicalId(supplierTin);
        if (chosen != null && chosen.isBlank()) {
            chosen = null;
        }
        Map<String, AuditMappingDto> mappings = mappingService.loadMappingIndex();
        Map<String, AuditCategoryDto> categories = mappingService.categoriesByCode();
        Map<String, AuditSubgroupDto> subgroups = mappingService.subgroupsByCode();
        List<ProductMovementDto> movements = sourceRowService.loadProductMovements(startDate, endDate);
        List<AuditSourceRowDto> bankRows = sourceRowService.loadBankRows(startDate, endDate, mappings);
        List<AuditSourceRowDto> documentRows = sourceRowService.toDocumentRows(movements, mappings);
        Map<String, String> categoryOverrides = configClient.categoryOverrides();
        Map<String, BigDecimal> writeOffRates = configClient.writeOffRates();
        Set<String> unreal = configClient.unrealCustomers();
        Map<String, String> names = new LinkedHashMap<>(configClient.customerNames());
        // Bank rows teach names too (statement counterparties).
        for (AuditSourceRowDto row : bankRows) {
            String tin = canonical(firstNonBlank(row.getResolvedCounterpartyTin(), row.getCounterpartyTin()));
            if (tin != null && row.getCounterpartyName() != null) {
                names.putIfAbsent(tin, row.getCounterpartyName());
            }
        }

        return build(startDate, endDate, chosen, movements, bankRows, documentRows,
                categories, subgroups, categoryOverrides, writeOffRates, unreal, names);
    }

    /** Pure aggregation — testable without I/O. */
    AuditOverviewDto build(LocalDate startDate, LocalDate endDate, String chosen,
                           List<ProductMovementDto> movements,
                           List<AuditSourceRowDto> bankRows,
                           List<AuditSourceRowDto> documentRows,
                           Map<String, AuditCategoryDto> categories,
                           Map<String, AuditSubgroupDto> subgroups,
                           Map<String, String> categoryOverrides,
                           Map<String, BigDecimal> writeOffRates,
                           Set<String> unrealCustomers,
                           Map<String, String> names) {
        List<String> notes = new ArrayList<>();
        Function<String, String> nameOf = tin -> {
            String n = names.get(tin);
            return n != null ? n : tin;
        };

        // ---------------- purchases & sales from movements ----------------
        Map<String, CatAcc> purByCat = new LinkedHashMap<>();
        Map<String, CatAcc> saleByCat = new LinkedHashMap<>();
        Map<String, CpAcc> purBySupplier = new LinkedHashMap<>();
        Map<String, CpAcc> saleByCustomer = new LinkedHashMap<>();
        BigDecimal purTotal = BigDecimal.ZERO, purKg = BigDecimal.ZERO, purChosen = BigDecimal.ZERO, purChosenKg = BigDecimal.ZERO;
        BigDecimal saleTotal = BigDecimal.ZERO, saleKg = BigDecimal.ZERO;
        int unrealRows = 0;
        BigDecimal unrealTotal = BigDecimal.ZERO;

        // Per-category kg accumulators for inventory + LIFO purchase lots.
        Map<String, InvAcc> inv = new LinkedHashMap<>();

        for (ProductMovementDto m : movements) {
            String category = ProductCategoryResolver.resolve(m.getProductName(), m.getParentCategory(), categoryOverrides);
            if (category == null) category = "OTHER";
            BigDecimal amount = nz(m.getAmount());
            boolean isKg = UnitClassifier.isKilogram(m.getUnit());
            BigDecimal kg = isKg ? nz(m.getQuantityKg()) : BigDecimal.ZERO;
            String tin = canonical(m.getCounterpartyId());
            boolean isChosen = chosen != null && chosen.equals(tin);
            if (m.getType() == WaybillType.PURCHASE) {
                purTotal = purTotal.add(amount); purKg = purKg.add(kg);
                CatAcc c = purByCat.computeIfAbsent(category, CatAcc::new);
                c.amount = c.amount.add(amount); c.kg = c.kg.add(kg); c.rows++;
                if (isChosen) { purChosen = purChosen.add(amount); purChosenKg = purChosenKg.add(kg); c.chosenAmount = c.chosenAmount.add(amount); c.chosenKg = c.chosenKg.add(kg); }
                if (tin != null) {
                    CpAcc s = purBySupplier.computeIfAbsent(tin, CpAcc::new);
                    s.purchases = s.purchases.add(amount); s.kg = s.kg.add(kg); s.rows++;
                }
                InvAcc ia = inv.computeIfAbsent(category, InvAcc::new);
                ia.purchasedKg = ia.purchasedKg.add(kg);
                if (kg.signum() > 0 && tin != null) ia.lots.add(new Lot(tin, m.getDate(), kg));
            } else if (m.getType() == WaybillType.SALE) {
                saleTotal = saleTotal.add(amount); saleKg = saleKg.add(kg);
                CatAcc c = saleByCat.computeIfAbsent(category, CatAcc::new);
                c.amount = c.amount.add(amount); c.kg = c.kg.add(kg); c.rows++;
                if (tin != null) {
                    CpAcc s = saleByCustomer.computeIfAbsent(tin, CpAcc::new);
                    s.purchases = s.purchases.add(amount); s.kg = s.kg.add(kg); s.rows++;
                }
                InvAcc ia = inv.computeIfAbsent(category, InvAcc::new);
                ia.soldKg = ia.soldKg.add(kg);
            }
        }

        // ---------------- unreal sales & paper chains from document rows ----------------
        BigDecimal unrealMapped = BigDecimal.ZERO;
        Map<String, CpAcc> unrealByCustomer = new LinkedHashMap<>();
        Tree paperTree = new Tree();
        Map<String, CpAcc> paperBySupplier = new LinkedHashMap<>();
        BigDecimal paperTotal = BigDecimal.ZERO;
        for (AuditSourceRowDto row : documentRows) {
            if (!"SALE".equalsIgnoreCase(row.getDirection())) continue;
            String tin = canonical(row.getCounterpartyTin());
            List<AuditMappingSplitDto> splits = AuditMappingService.effectiveSplits(row.getMapping());
            boolean paperOnlySale = splits.stream().anyMatch(s -> {
                AuditCategoryDto c = categories.get(s.getCategoryCode());
                return AuditCategories.PAPER_ONLY_SALE.equals(s.getCategoryCode()) || (c != null && c.isPaperOnly());
            });
            boolean isUnreal = (tin != null && unrealCustomers.contains(tin)) || paperOnlySale;
            if (!isUnreal) continue;
            unrealRows++;
            BigDecimal amount = nz(row.getAmount()).abs();
            unrealTotal = unrealTotal.add(amount);
            BigDecimal mappedHere = AuditMappingService.splitTotal(splits);
            unrealMapped = unrealMapped.add(mappedHere.min(amount));
            if (tin != null) {
                CpAcc u = unrealByCustomer.computeIfAbsent(tin, CpAcc::new);
                u.purchases = u.purchases.add(amount); u.rows++; u.mapped = u.mapped.add(mappedHere.min(amount));
            }
            // A slice that names a counterparty other than the customer and sits in a
            // supplier-settlement or paper category is the "unreal cash → supplier"
            // chain: paper outflow attributed to that supplier.
            for (AuditMappingSplitDto s : splits) {
                AuditCategoryDto c = categories.get(s.getCategoryCode());
                boolean supplierish = c != null && (c.isSupplierSettlement()
                        || AuditCategories.PAPER_ONLY_SUPPLIER_PAYMENT.equals(s.getCategoryCode()));
                if (!supplierish) continue;
                String cp = canonical(s.getCounterpartyTin());
                String cpName = firstNonBlank(s.getCounterpartyName(), cp == null ? null : nameOf.apply(cp), "unnamed counterparty");
                BigDecimal v = nz(s.getAmount());
                paperTotal = paperTotal.add(v);
                paperTree.add(s.getCategoryCode(), labelOf(categories, s.getCategoryCode()),
                        subgroupCode(s), subgroupLabel(subgroups, s), cp, cpName, v);
                if (cp != null) {
                    CpAcc p = paperBySupplier.computeIfAbsent(cp, CpAcc::new);
                    p.paperOutflow = p.paperOutflow.add(v);
                }
            }
        }

        // ---------------- cash outflow & bank payments from bank rows ----------------
        BigDecimal outflowTotal = BigDecimal.ZERO, outflowUnmapped = BigDecimal.ZERO, outflowMapped = BigDecimal.ZERO;
        BigDecimal outflowToChosen = BigDecimal.ZERO;
        BigDecimal bankToSuppliers = BigDecimal.ZERO, bankToChosen = BigDecimal.ZERO;
        int debitRows = 0, unmappedRows = 0;
        Tree realTree = new Tree();
        Map<String, CpAcc> bankBySupplier = new LinkedHashMap<>();
        for (AuditSourceRowDto row : bankRows) {
            if (!"DEBIT".equalsIgnoreCase(row.getDirection())) continue;
            debitRows++;
            BigDecimal amount = nz(row.getAmount()).abs();
            outflowTotal = outflowTotal.add(amount);
            List<AuditMappingSplitDto> splits = AuditMappingService.effectiveSplits(row.getMapping());
            BigDecimal covered = AuditMappingService.splitTotal(splits).min(amount);
            BigDecimal unresolved = amount.subtract(covered).max(BigDecimal.ZERO);
            outflowMapped = outflowMapped.add(covered);
            String rowTin = canonical(firstNonBlank(row.getResolvedCounterpartyTin(), row.getCounterpartyTin()));
            if (unresolved.signum() > 0) {
                outflowUnmapped = outflowUnmapped.add(unresolved);
                unmappedRows++;
                realTree.add(AuditSubgroups.UNMAPPED, "Unmapped", AuditSubgroups.UNMAPPED, "Unmapped",
                        rowTin, rowTin == null ? firstNonBlank(row.getCounterpartyName(), "no counterparty") : nameOf.apply(rowTin), unresolved);
                if (chosen != null && chosen.equals(rowTin)) outflowToChosen = outflowToChosen.add(unresolved);
            }
            for (AuditMappingSplitDto s : splits) {
                BigDecimal v = nz(s.getAmount());
                String cp = canonical(firstNonBlank(s.getCounterpartyTin(), rowTin));
                String cpName = firstNonBlank(s.getCounterpartyName(), cp == null ? null : nameOf.apply(cp), row.getCounterpartyName(), "no counterparty");
                realTree.add(s.getCategoryCode(), labelOf(categories, s.getCategoryCode()),
                        subgroupCode(s), subgroupLabel(subgroups, s), cp, cpName, v);
                if (chosen != null && chosen.equals(cp)) outflowToChosen = outflowToChosen.add(v);
                AuditCategoryDto c = categories.get(s.getCategoryCode());
                if (c != null && c.isSupplierSettlement()) {
                    bankToSuppliers = bankToSuppliers.add(v);
                    if (chosen != null && chosen.equals(cp)) bankToChosen = bankToChosen.add(v);
                    if (cp != null) {
                        CpAcc b = bankBySupplier.computeIfAbsent(cp, CpAcc::new);
                        b.bankPayments = b.bankPayments.add(v); b.rows++;
                    }
                }
            }
        }

        // ---------------- inventory with LIFO supplier attribution ----------------
        List<InventoryCategory> invRows = new ArrayList<>();
        BigDecimal netTotal = BigDecimal.ZERO;
        for (Map.Entry<String, InvAcc> e : inv.entrySet()) {
            InvAcc a = e.getValue();
            BigDecimal percent = writeOffRates.getOrDefault(e.getKey(), WriteOffCalculator.DEFAULT_WRITE_OFF_PERCENT);
            BigDecimal writeOffKg = a.purchasedKg.multiply(percent).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
            BigDecimal net = a.purchasedKg.subtract(writeOffKg).subtract(a.soldKg);
            netTotal = netTotal.add(net);
            invRows.add(InventoryCategory.builder()
                    .category(e.getKey())
                    .purchasedKg(kg(a.purchasedKg)).writeOffPercent(percent.setScale(2, RoundingMode.HALF_UP))
                    .writeOffKg(kg(writeOffKg)).soldKg(kg(a.soldKg)).netKg(kg(net))
                    .stockBySupplier(lifo(a.lots, net, percent, nameOf))
                    .build());
        }
        invRows.sort(Comparator.comparing(InventoryCategory::getCategory));

        // ---------------- assemble ----------------
        List<Counterparty> suppliers = new ArrayList<>();
        for (Map.Entry<String, CpAcc> e : purBySupplier.entrySet()) {
            CpAcc p = e.getValue();
            CpAcc b = bankBySupplier.getOrDefault(e.getKey(), new CpAcc(e.getKey()));
            CpAcc pp = paperBySupplier.getOrDefault(e.getKey(), new CpAcc(e.getKey()));
            suppliers.add(Counterparty.builder().tin(e.getKey()).name(nameOf.apply(e.getKey()))
                    .purchases(money(p.purchases)).quantityKg(kg(p.kg)).rowCount(p.rows)
                    .bankPayments(money(b.bankPayments)).paperOutflow(money(pp.paperOutflow)).build());
        }
        // Counterparties paid by bank but never seen on a purchase document still belong in the bank list.
        List<Counterparty> bankList = new ArrayList<>();
        for (Map.Entry<String, CpAcc> e : bankBySupplier.entrySet()) {
            CpAcc b = e.getValue();
            CpAcc p = purBySupplier.getOrDefault(e.getKey(), new CpAcc(e.getKey()));
            bankList.add(Counterparty.builder().tin(e.getKey()).name(nameOf.apply(e.getKey()))
                    .bankPayments(money(b.bankPayments)).rowCount(b.rows).purchases(money(p.purchases)).build());
        }
        suppliers.sort(Comparator.comparing(Counterparty::getPurchases, Comparator.reverseOrder()));
        bankList.sort(Comparator.comparing(Counterparty::getBankPayments, Comparator.reverseOrder()));
        List<Counterparty> unrealList = new ArrayList<>();
        for (Map.Entry<String, CpAcc> e : unrealByCustomer.entrySet()) {
            CpAcc u = e.getValue();
            unrealList.add(Counterparty.builder().tin(e.getKey()).name(nameOf.apply(e.getKey()))
                    .purchases(money(u.purchases)).paperOutflow(money(u.mapped)).rowCount(u.rows).build());
        }
        unrealList.sort(Comparator.comparing(Counterparty::getPurchases, Comparator.reverseOrder()));

        if (chosen == null) {
            notes.add("No supplier chosen — the 'chosen' figures are empty, not zero.");
        }
        notes.add("Opening stock is not recorded, so inventory is the period's net movement (purchased − write-off − sold), not a stock level.");
        notes.add("Paper outflow (unreal-sale chains) is shown beside real bank money and never added to it.");
        if (unrealCustomers.isEmpty()) {
            notes.add("No customers are marked unreal on /audit-control (or config-service was unreachable) — 'unreal' here counts only lines mapped as paper-only sale.");
        }

        return AuditOverviewDto.builder()
                .startDate(startDate).endDate(endDate)
                .supplierTin(chosen).supplierName(chosen == null ? null : nameOf.apply(chosen))
                .suppliers(suppliers)
                .purchases(AuditOverviewDto.Purchases.builder()
                        .total(money(purTotal)).totalKg(kg(purKg))
                        .chosen(chosen == null ? null : money(purChosen)).chosenKg(chosen == null ? null : kg(purChosenKg))
                        .byCategory(catRows(purByCat, chosen != null)).bySupplier(suppliers).build())
                .bankPaymentsToSuppliers(AuditOverviewDto.BankPayments.builder()
                        .total(money(bankToSuppliers)).toChosen(chosen == null ? null : money(bankToChosen))
                        .bySupplier(bankList).build())
                .cashOutflow(AuditOverviewDto.CashOutflow.builder()
                        .total(money(outflowTotal)).unmapped(money(outflowUnmapped)).mapped(money(outflowMapped))
                        .toChosen(chosen == null ? null : money(outflowToChosen))
                        .groups(realTree.toBuckets()).paperGroups(paperTree.toBuckets()).paperTotal(money(paperTotal))
                        .debitRowCount(debitRows).unmappedRowCount(unmappedRows).build())
                .sales(AuditOverviewDto.Sales.builder()
                        .total(money(saleTotal)).totalKg(kg(saleKg))
                        .unreal(money(unrealTotal)).real(money(saleTotal.subtract(unrealTotal)))
                        .unrealMapped(money(unrealMapped)).unrealUnmapped(money(unrealTotal.subtract(unrealMapped)))
                        .byCategory(catRows(saleByCat, false)).unrealCustomers(unrealList).unrealRowCount(unrealRows).build())
                .inventory(AuditOverviewDto.Inventory.builder().byCategory(invRows).netKgTotal(kg(netTotal)).build())
                .subgroups(new ArrayList<>(subgroups.values()))
                .notes(notes)
                .build();
    }

    // ==================== LIFO ====================

    /**
     * Attribute the remaining kg to suppliers, latest purchases first. Each lot
     * contributes its post-write-off kg (the same rate the net figure used), so
     * the attribution sums to {@code netKg} exactly. Empty when nothing remains.
     */
    static List<SupplierKg> lifo(List<Lot> lots, BigDecimal netKg, BigDecimal writeOffPercent, Function<String, String> nameOf) {
        List<SupplierKg> out = new ArrayList<>();
        if (netKg == null || netKg.signum() <= 0 || lots.isEmpty()) return out;
        BigDecimal keep = BigDecimal.ONE.subtract(writeOffPercent.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
        List<Lot> sorted = new ArrayList<>(lots);
        sorted.sort(Comparator.comparing((Lot l) -> l.date == null ? LocalDate.MIN : l.date).reversed());
        Map<String, SupplierKg> byTin = new LinkedHashMap<>();
        BigDecimal remaining = netKg;
        for (Lot lot : sorted) {
            if (remaining.signum() <= 0) break;
            BigDecimal net = lot.kg.multiply(keep);
            BigDecimal take = net.min(remaining);
            remaining = remaining.subtract(take);
            SupplierKg acc = byTin.computeIfAbsent(lot.tin, t -> SupplierKg.builder().tin(t).name(nameOf.apply(t))
                    .quantityKg(BigDecimal.ZERO).lastPurchaseDate(lot.date).build());
            acc.setQuantityKg(acc.getQuantityKg().add(take));
            if (lot.date != null && (acc.getLastPurchaseDate() == null || lot.date.isAfter(acc.getLastPurchaseDate()))) {
                acc.setLastPurchaseDate(lot.date);
            }
        }
        for (SupplierKg s : byTin.values()) {
            s.setQuantityKg(kg(s.getQuantityKg()));
            out.add(s);
        }
        out.sort(Comparator.comparing(SupplierKg::getQuantityKg, Comparator.reverseOrder()));
        return out;
    }

    // ==================== helpers ====================

    record Lot(String tin, LocalDate date, BigDecimal kg) {}

    private static final class CatAcc {
        final String category; BigDecimal amount = BigDecimal.ZERO, kg = BigDecimal.ZERO, chosenAmount = BigDecimal.ZERO, chosenKg = BigDecimal.ZERO; int rows;
        CatAcc(String c) { this.category = c; }
    }

    private static final class CpAcc {
        final String tin; BigDecimal purchases = BigDecimal.ZERO, kg = BigDecimal.ZERO, bankPayments = BigDecimal.ZERO, paperOutflow = BigDecimal.ZERO, mapped = BigDecimal.ZERO; int rows;
        CpAcc(String t) { this.tin = t; }
    }

    private static final class InvAcc {
        final String category; BigDecimal purchasedKg = BigDecimal.ZERO, soldKg = BigDecimal.ZERO; final List<Lot> lots = new ArrayList<>();
        InvAcc(String c) { this.category = c; }
    }

    /** category → subgroup → counterparty accumulator that renders to nested Buckets. */
    static final class Tree {
        private final Map<String, Node> groups = new LinkedHashMap<>();

        void add(String groupCode, String groupLabel, String subCode, String subLabel, String tin, String cpName, BigDecimal v) {
            Node g = groups.computeIfAbsent(nz(groupCode, "UNKNOWN"), k -> new Node(k, groupLabel));
            g.amount = g.amount.add(v); g.rows++;
            Node s = g.children.computeIfAbsent(nz(subCode, AuditSubgroups.NONE), k -> new Node(k, subLabel));
            s.amount = s.amount.add(v); s.rows++;
            String cpKey = tin != null ? tin : "name:" + cpName;
            Node c = s.children.computeIfAbsent(cpKey, k -> new Node(k, cpName));
            c.tin = tin; c.amount = c.amount.add(v); c.rows++;
        }

        List<Bucket> toBuckets() {
            List<Bucket> out = new ArrayList<>();
            for (Node g : groups.values()) {
                List<Bucket> subs = new ArrayList<>();
                for (Node s : g.children.values()) {
                    List<Bucket> cps = new ArrayList<>();
                    for (Node c : s.children.values()) {
                        cps.add(Bucket.builder().code(c.code).label(c.label).tin(c.tin).amount(money(c.amount)).rowCount(c.rows).build());
                    }
                    cps.sort(Comparator.comparing(Bucket::getAmount, Comparator.reverseOrder()));
                    subs.add(Bucket.builder().code(s.code).label(s.label).amount(money(s.amount)).rowCount(s.rows).children(cps).build());
                }
                subs.sort(Comparator.comparing(Bucket::getAmount, Comparator.reverseOrder()));
                out.add(Bucket.builder().code(g.code).label(g.label).amount(money(g.amount)).rowCount(g.rows).children(subs).build());
            }
            out.sort(Comparator.comparing(Bucket::getAmount, Comparator.reverseOrder()));
            return out;
        }

        private static final class Node {
            final String code; final String label; String tin; BigDecimal amount = BigDecimal.ZERO; int rows;
            final Map<String, Node> children = new LinkedHashMap<>();
            Node(String code, String label) { this.code = code; this.label = label == null ? code : label; }
        }
    }

    private static List<CategoryAmount> catRows(Map<String, CatAcc> m, boolean withChosen) {
        List<CategoryAmount> out = new ArrayList<>();
        for (CatAcc c : m.values()) {
            out.add(CategoryAmount.builder().category(c.category).amount(money(c.amount)).quantityKg(kg(c.kg))
                    .chosenAmount(withChosen ? money(c.chosenAmount) : null).chosenQuantityKg(withChosen ? kg(c.chosenKg) : null)
                    .rowCount(c.rows).build());
        }
        out.sort(Comparator.comparing(CategoryAmount::getAmount, Comparator.reverseOrder()));
        return out;
    }

    private static String subgroupCode(AuditMappingSplitDto s) {
        return s.getSubgroupCode() == null || s.getSubgroupCode().isBlank() ? AuditSubgroups.NONE : s.getSubgroupCode();
    }

    private static String subgroupLabel(Map<String, AuditSubgroupDto> subgroups, AuditMappingSplitDto s) {
        String code = subgroupCode(s);
        if (AuditSubgroups.NONE.equals(code)) return "No document status";
        AuditSubgroupDto sg = subgroups.get(code);
        return sg != null ? sg.getLabel() : code;
    }

    private static String labelOf(Map<String, AuditCategoryDto> categories, String code) {
        AuditCategoryDto c = categories.get(code);
        return c != null && c.getLabel() != null ? c.getLabel() : code;
    }

    private static String canonical(String tin) {
        if (tin == null || tin.isBlank()) return null;
        String c = TinValidator.canonicalId(tin.trim());
        return c == null || c.isBlank() ? null : c;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static String nz(String v, String d) { return v == null || v.isBlank() ? d : v; }
    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private static BigDecimal money(BigDecimal v) { return nz(v).setScale(MONEY, RoundingMode.HALF_UP); }
    private static BigDecimal kg(BigDecimal v) { return nz(v).setScale(KG, RoundingMode.HALF_UP); }
}
