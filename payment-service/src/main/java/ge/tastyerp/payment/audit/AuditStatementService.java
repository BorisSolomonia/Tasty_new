package ge.tastyerp.payment.audit;

import ge.tastyerp.common.dto.audit.ProductMovementDto;
import ge.tastyerp.common.dto.auditlayer.AuditBulkMapRequestDto;
import ge.tastyerp.common.dto.auditlayer.AuditCategoryDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingSplitDto;
import ge.tastyerp.common.dto.auditlayer.AuditSourceRowDto;
import ge.tastyerp.common.dto.auditlayer.AuditSourceType;
import ge.tastyerp.common.dto.auditlayer.AuditStatementDto;
import ge.tastyerp.common.dto.auditlayer.AuditStatementDto.InventoryRow;
import ge.tastyerp.common.dto.auditlayer.AuditStatementDto.Level;
import ge.tastyerp.common.dto.auditlayer.AuditStatementDto.Party;
import ge.tastyerp.common.dto.auditlayer.AuditStatementDto.ProductGroup;
import ge.tastyerp.common.dto.auditlayer.AuditStatementDto.Row;
import ge.tastyerp.common.dto.auditlayer.AuditStatementDto.Selection;
import ge.tastyerp.common.dto.auditlayer.AuditStatementDto.Summary;
import ge.tastyerp.common.dto.auditlayer.AuditStatementDto.Figure;
import ge.tastyerp.common.dto.auditlayer.AuditStatementDto.SupplierKg;
import ge.tastyerp.common.dto.auditlayer.AuditStatementTransactionDto;
import ge.tastyerp.common.dto.auditlayer.AuditSubgroupDto;
import ge.tastyerp.common.dto.payment.PaymentDto;
import ge.tastyerp.common.dto.waybill.WaybillType;
import ge.tastyerp.common.exception.ValidationException;
import ge.tastyerp.common.util.TinValidator;
import ge.tastyerp.common.util.UnitClassifier;
import ge.tastyerp.payment.repository.PaymentRepository;
import ge.tastyerp.payment.service.DebtService;
import ge.tastyerp.payment.service.audit.WriteOffCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The statement at the top of the audit page (BOR-92 v2): seven rows in
 * income-statement order, each a period total and the same figure restricted
 * to the chosen counterparties, plus the drill-down behind every figure.
 *
 * <p>Nothing here is computed twice: document lines come from the RS.ge feed
 * the workbench already uses, bank rows from the mirrored statement with the
 * live mappings, and both inflow rows from the very repositories the /payments
 * page lists ({@code payments} and {@code manualCashPayments}). The aggregation
 * itself is pure ({@link #build}) so it is unit-tested without I/O.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditStatementService {

    static final int MONEY = 2;
    static final int KG = 3;
    static final String CHOSEN_BY_SUPPLIERS = "SUPPLIERS";
    static final String CHOSEN_BY_CUSTOMERS = "CUSTOMERS";
    static final int MAX_TRANSACTIONS = 5000;

    private final AuditSourceRowService sourceRowService;
    private final AuditMappingService mappingService;
    private final AuditConfigClient configClient;
    private final AuditLayerRepository repository;
    private final PaymentRepository paymentRepository;
    private final DebtService debtService;

    // ==================== selection ====================

    public Selection getSelection(String operator) {
        return normalize(repository.findStatementSelection(operatorKey(operator)));
    }

    public Selection saveSelection(String operator, Selection selection) {
        Selection clean = normalize(selection);
        repository.saveStatementSelection(operatorKey(operator), clean);
        return clean;
    }

    static String operatorKey(String operator) {
        String o = operator == null ? "" : operator.trim();
        if (o.isEmpty()) {
            throw new ValidationException("operator", "An operator name is required to save a selection");
        }
        return o.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\p{IsGeorgian}._-]+", "_");
    }

    static Selection normalize(Selection s) {
        Set<String> sup = new LinkedHashSet<>();
        Set<String> cus = new LinkedHashSet<>();
        if (s != null) {
            for (String t : s.getSuppliers() == null ? List.<String>of() : s.getSuppliers()) {
                String c = selectionKey(t);
                if (c != null) sup.add(c);
            }
            for (String t : s.getCustomers() == null ? List.<String>of() : s.getCustomers()) {
                String c = selectionKey(t);
                if (c != null) cus.add(c);
            }
        }
        return Selection.builder().suppliers(new ArrayList<>(sup)).customers(new ArrayList<>(cus)).build();
    }

    /**
     * A selection entry is a canonical TIN, or {@code name:<label>} for a bank
     * counterparty the statement never identified (ATM lines, card fees) — those
     * are choosable too, by the exact label the statement printed.
     */
    static String selectionKey(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.startsWith("name:")) {
            String label = t.substring(5).trim();
            return label.isEmpty() ? null : "name:" + label;
        }
        return canonical(t);
    }

    // ==================== statement ====================

    public AuditStatementDto statement(LocalDate startDate, LocalDate endDate, String operator) {
        Selection selection = operator == null || operator.isBlank()
                ? Selection.builder().suppliers(List.of()).customers(List.of()).build()
                : getSelection(operator);
        Inputs in = loadInputs(startDate, endDate);
        return build(startDate, endDate, operator, selection, in);
    }

    /** Everything the statement and its drill-downs read, loaded once per request. */
    record Inputs(List<ProductMovementDto> movements,
                  List<AuditSourceRowDto> documentRows,
                  List<AuditSourceRowDto> bankRows,
                  List<PaymentDto> bankPayments,
                  List<PaymentDto> cashPayments,
                  Map<String, AuditCategoryDto> categories,
                  Map<String, AuditSubgroupDto> subgroups,
                  Map<String, String> categoryOverrides,
                  Map<String, BigDecimal> writeOffRates,
                  Set<String> unrealCustomers,
                  Map<String, String> names,
                  BigDecimal receivables) {}

    Inputs loadInputs(LocalDate startDate, LocalDate endDate) {
        Map<String, AuditMappingDto> mappings = mappingService.loadMappingIndex();
        List<ProductMovementDto> movements = sourceRowService.loadProductMovements(startDate, endDate);
        return new Inputs(
                movements,
                sourceRowService.toDocumentRows(movements, mappings),
                sourceRowService.loadBankRows(startDate, endDate, mappings),
                // Bank inflow reads the statement's own CREDIT rows (v3); the /payments import is
                // not needed for any figure, and reading it cost seconds over a multi-year period.
                List.of(),
                paymentRepository.findManualPayments(null, startDate, endDate, null),
                mappingService.categoriesByCode(),
                mappingService.subgroupsByCode(),
                configClient.categoryOverrides(),
                configClient.writeOffRates(),
                configClient.unrealCustomers(),
                configClient.customerNames(),
                receivables());
    }

    /** Total outstanding on /payments, as of now; null (and a note) if the payments module did not answer. */
    private BigDecimal receivables() {
        try {
            return debtService.getOverview().getTotalOutstanding();
        } catch (RuntimeException e) {
            log.warn("Receivables unavailable for the statement summary: {}", e.getMessage());
            return null;
        }
    }

    /** Pure aggregation — testable without I/O. */
    AuditStatementDto build(LocalDate startDate, LocalDate endDate, String operator, Selection selection, Inputs in) {
        Set<String> chosenSuppliers = new HashSet<>(selection.getSuppliers());
        Set<String> chosenCustomers = new HashSet<>(selection.getCustomers());
        List<String> notes = new ArrayList<>();
        Function<String, String> nameOf = nameResolver(in);

        // ---------------- purchases & sales from document lines ----------------
        Map<String, PartyAcc> sellers = new LinkedHashMap<>();
        Map<String, PartyAcc> buyers = new LinkedHashMap<>();
        Map<String, GroupAcc> purGroups = new LinkedHashMap<>();
        Map<String, GroupAcc> saleGroups = new LinkedHashMap<>();
        Map<String, InvAcc> inv = new LinkedHashMap<>();
        Money pur = new Money(), sale = new Money();
        BigDecimal unrealTotal = BigDecimal.ZERO;

        for (ProductMovementDto m : in.movements()) {
            String category = categoryOf(m, in.categoryOverrides());
            BigDecimal amount = nz(m.getAmount());
            boolean isKg = UnitClassifier.isKilogram(m.getUnit());
            BigDecimal kg = isKg ? nz(m.getQuantityKg()) : BigDecimal.ZERO;
            String tin = canonical(m.getCounterpartyId());
            InvAcc ia = inv.computeIfAbsent(category, InvAcc::new);
            if (m.getType() == WaybillType.PURCHASE) {
                boolean chosen = tin != null && chosenSuppliers.contains(tin);
                pur.add(amount, kg, chosen);
                purGroups.computeIfAbsent(category, GroupAcc::new).add(amount, kg, chosen, m.getProductName());
                if (tin != null) sellers.computeIfAbsent(tin, PartyAcc::new).add(amount, kg);
                ia.purchasedKg = ia.purchasedKg.add(kg);
                if (isKg) { ia.purchasedAmountKgLines = ia.purchasedAmountKgLines.add(amount); }
                if (kg.signum() > 0 && tin != null) ia.lots.add(new Lot(tin, m.getDate(), kg));
            } else if (m.getType() == WaybillType.SALE) {
                boolean chosen = tin != null && chosenCustomers.contains(tin);
                sale.add(amount, kg, chosen);
                saleGroups.computeIfAbsent(category, GroupAcc::new).add(amount, kg, chosen, m.getProductName());
                if (tin != null) {
                    PartyAcc b = buyers.computeIfAbsent(tin, PartyAcc::new);
                    b.add(amount, kg);
                    if (in.unrealCustomers().contains(tin)) { b.unreal = true; }
                }
                if (tin != null && in.unrealCustomers().contains(tin)) unrealTotal = unrealTotal.add(amount);
                ia.soldKg = ia.soldKg.add(kg);
                ia.soldAmount = ia.soldAmount.add(amount);
            }
        }
        // Lines mapped as paper-only sale are unreal too, whoever the buyer is.
        for (AuditSourceRowDto row : in.documentRows()) {
            if (!"SALE".equalsIgnoreCase(row.getDirection())) continue;
            String tin = canonical(row.getCounterpartyTin());
            if (tin != null && in.unrealCustomers().contains(tin)) continue; // already counted
            boolean paperOnly = AuditMappingService.effectiveSplits(row.getMapping()).stream().anyMatch(s -> {
                AuditCategoryDto c = in.categories().get(s.getCategoryCode());
                return AuditCategories.PAPER_ONLY_SALE.equals(s.getCategoryCode()) || (c != null && c.isPaperOnly());
            });
            if (paperOnly) unrealTotal = unrealTotal.add(nz(row.getAmount()).abs());
        }

        // ---------------- bank rows: debits (outflow, supplier payments, withdrawals) and credits (inflow) ----------------
        Money outflow = new Money(), toSuppliers = new Money(), bankIn = new Money();
        BigDecimal unmapped = BigDecimal.ZERO;
        BigDecimal withdrawals = BigDecimal.ZERO, withdrawalsToSuppliers = BigDecimal.ZERO, withdrawalsUnresolved = BigDecimal.ZERO, withdrawalsUndocumented = BigDecimal.ZERO;
        BigDecimal customerReceipts = BigDecimal.ZERO, unmappedIncome = BigDecimal.ZERO;
        Map<String, PartyAcc> outflowParties = new LinkedHashMap<>();
        Map<String, PartyAcc> supplierPayees = new LinkedHashMap<>();
        Map<String, PartyAcc> inflowParties = new LinkedHashMap<>();
        for (AuditSourceRowDto row : in.bankRows()) {
            boolean debit = "DEBIT".equalsIgnoreCase(row.getDirection());
            boolean credit = "CREDIT".equalsIgnoreCase(row.getDirection());
            if (!debit && !credit) continue;
            BigDecimal amount = nz(row.getAmount()).abs();
            String rowTin = canonical(firstNonBlank(row.getResolvedCounterpartyTin(), row.getCounterpartyTin()));
            String rowKey = rowTin != null ? rowTin : "name:" + firstNonBlank(row.getCounterpartyName(), "no counterparty");
            List<AuditMappingSplitDto> splits = AuditMappingService.effectiveSplits(row.getMapping());
            BigDecimal covered = AuditMappingService.splitTotal(splits).min(amount);
            BigDecimal unresolved = amount.subtract(covered).max(BigDecimal.ZERO);
            Set<String> chosenSet = debit ? chosenSuppliers : chosenCustomers;
            Money total = debit ? outflow : bankIn;
            Map<String, PartyAcc> parties = debit ? outflowParties : inflowParties;

            // The row as a whole belongs to the party list once, under its own counterparty.
            PartyAcc op = parties.computeIfAbsent(rowKey, k -> new PartyAcc(rowTin));
            op.name = firstNonBlank(op.name, row.getCounterpartyName());
            op.identityBasis = firstNonBlank(op.identityBasis, row.getCounterpartyIdentityBasis());
            op.rows++;
            op.directCount++;
            boolean rowChosen = chosenSet.contains(rowKey);   // TIN, or name:<label> for unidentified rows
            total.rows++;
            total.addAmount(unresolved, rowChosen);
            op.amount = op.amount.add(unresolved);
            op.direct = op.direct.add(unresolved);
            op.secondary = op.secondary.add(unresolved);
            if (debit) unmapped = unmapped.add(unresolved); else unmappedIncome = unmappedIncome.add(unresolved);

            for (AuditMappingSplitDto sp : splits) {
                BigDecimal v = nz(sp.getAmount());
                String cp = canonical(firstNonBlank(sp.getCounterpartyTin(), rowTin));
                boolean chosen = cp != null ? chosenSet.contains(cp) : chosenSet.contains(rowKey);
                total.addAmount(v, chosen);
                AuditCategoryDto c = in.categories().get(sp.getCategoryCode());
                if (cp != null && !cp.equals(rowTin)) {
                    // Attributed to someone other than the row's own counterparty: that party's "mapped" money.
                    PartyAcc other = parties.computeIfAbsent(cp, PartyAcc::new);
                    other.name = firstNonBlank(other.name, sp.getCounterpartyName());
                    other.amount = other.amount.add(v);
                    other.mapped = other.mapped.add(v);
                    other.mappedCount++;
                } else {
                    op.amount = op.amount.add(v);
                    op.direct = op.direct.add(v);
                }
                if (debit) {
                    if (c != null && c.isSupplierSettlement()) {
                        toSuppliers.add(v, BigDecimal.ZERO, chosen);
                        String key = cp != null ? cp : rowKey;
                        PartyAcc payee = supplierPayees.computeIfAbsent(key, k -> new PartyAcc(cp));
                        payee.name = firstNonBlank(payee.name, sp.getCounterpartyName(), row.getCounterpartyName());
                        payee.amount = payee.amount.add(v);
                        payee.rows++;
                        if (cp != null) {
                            // Purchases lists this too: a supplier paid by bank with no purchase document
                            // shows 0 purchases and a negative "unpaid after bank" — paid, undocumented.
                            PartyAcc seller = sellers.computeIfAbsent(cp, PartyAcc::new);
                            seller.bankPaid = seller.bankPaid.add(v);
                        }
                    }
                    if (c != null && c.isCashWithdrawal()) {
                        withdrawals = withdrawals.add(v);
                        if (c.isSupplierSettlement()) withdrawalsToSuppliers = withdrawalsToSuppliers.add(v);
                        else if (c.isUnresolved()) withdrawalsUnresolved = withdrawalsUnresolved.add(v);
                        else withdrawalsUndocumented = withdrawalsUndocumented.add(v);
                    }
                } else if (c != null && c.isCustomerReceipt()) {
                    customerReceipts = customerReceipts.add(v);
                } else {
                    unmappedIncome = unmappedIncome.add(v);   // mapped, but not as a customer receipt (other income, refunds…)
                }
            }
        }
        // ---------------- cash inflow from the payments module ----------------
        Money cashIn = new Money();
        Map<String, PartyAcc> cashPayers = new LinkedHashMap<>();
        for (PaymentDto pmt : in.cashPayments()) {
            String tin = canonical(pmt.getCustomerId());
            String key = tin != null ? tin : "name:" + firstNonBlank(pmt.getCustomerName(), "unknown");
            boolean chosen = tin != null && chosenCustomers.contains(tin);
            BigDecimal v = nz(pmt.getAmount());
            cashIn.add(v, BigDecimal.ZERO, chosen);
            PartyAcc acc = cashPayers.computeIfAbsent(key, k -> new PartyAcc(tin));
            acc.name = firstNonBlank(acc.name, pmt.getCustomerName());
            acc.amount = acc.amount.add(v); acc.rows++;
        }

        // ---------------- inventory ----------------
        List<Level> levels = new ArrayList<>();
        List<String> unpriced = new ArrayList<>();
        BigDecimal netKgTotal = BigDecimal.ZERO, valueTotal = BigDecimal.ZERO;
        for (Map.Entry<String, InvAcc> e : inv.entrySet()) {
            InvAcc a = e.getValue();
            BigDecimal percent = in.writeOffRates().getOrDefault(e.getKey(), WriteOffCalculator.DEFAULT_WRITE_OFF_PERCENT);
            BigDecimal writeOffKg = a.purchasedKg.multiply(percent).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
            BigDecimal net = a.purchasedKg.subtract(writeOffKg).subtract(a.soldKg);
            netKgTotal = netKgTotal.add(net);
            BigDecimal avg = a.purchasedKg.signum() > 0
                    ? a.purchasedAmountKgLines.divide(a.purchasedKg, 4, RoundingMode.HALF_UP) : null;
            BigDecimal value = avg == null ? null : net.multiply(avg);
            if (value == null) unpriced.add(e.getKey()); else valueTotal = valueTotal.add(value);
            levels.add(Level.builder()
                    .category(e.getKey())
                    .purchasedKg(kg(a.purchasedKg)).purchasedAmount(money(a.purchasedAmountKgLines))
                    .writeOffPercent(percent.setScale(2, RoundingMode.HALF_UP)).writeOffKg(kg(writeOffKg))
                    .soldKg(kg(a.soldKg)).soldAmount(money(a.soldAmount))
                    .netKg(kg(net)).avgPurchasePricePerKg(avg == null ? null : avg.setScale(2, RoundingMode.HALF_UP))
                    .value(value == null ? null : money(value))
                    .stockBySupplier(lifo(a.lots, net, percent, nameOf))
                    .build());
        }
        levels.sort(Comparator.comparing(Level::getCategory));

        // ---------------- assemble ----------------
        boolean anySupplier = !chosenSuppliers.isEmpty();
        boolean anyCustomer = !chosenCustomers.isEmpty();
        if (!anySupplier) notes.add("No supplier is chosen — supplier-side 'chosen' figures are empty, not zero. Tick suppliers inside Purchases, Bank payments or Cash outflow.");
        if (!anyCustomer) notes.add("No customer is chosen — customer-side 'chosen' figures are empty, not zero. Tick customers inside Sales or the inflow rows.");
        notes.add("Opening stock is not recorded: inventory is the period's net movement (purchased − write-off − sold), valued at the period's average purchase price per kg.");
        if (in.unrealCustomers().isEmpty()) notes.add("No customers are marked unreal on /audit-control (or config-service was unreachable) — 'real' sales here exclude only paper-only mapped lines.");
        if (!unpriced.isEmpty()) notes.add("Inventory value excludes " + String.join(", ", unpriced) + " (no purchases with a kg price this period).");
        if (in.receivables() == null) notes.add("Receivables (AR) could not be read from the payments module — the 'cash to be received from customers' line is empty, not zero.");
        else notes.add("Receivables (AR) is /payments' total outstanding as of now — a balance, while the other operands are period flows.");

        return AuditStatementDto.builder()
                .startDate(startDate).endDate(endDate).operator(operator)
                .selection(selection)
                .purchases(Row.builder().key("purchases").title("Purchases").chosenBy(CHOSEN_BY_SUPPLIERS)
                        .definition("₾ of every RS.ge purchase document line dated in the period; kg counted only where the unit is kg. Chosen = ticked suppliers.")
                        .total(money(pur.amount)).totalKg(kg(pur.kg))
                        .chosen(anySupplier ? money(pur.chosen) : null).chosenKg(anySupplier ? kg(pur.chosenKg) : null)
                        .rowCount(pur.rows)
                        .parties(parties(sellers, chosenSuppliers, nameOf, true, true))
                        .products(groups(purGroups, anySupplier)).build())
                .bankPaymentsToSuppliers(Row.builder().key("bankPaymentsToSuppliers").title("Bank payments to suppliers").chosenBy(CHOSEN_BY_SUPPLIERS)
                        .definition("Real bank money out on rows mapped to a supplier-settlement group, attributed to the counterparty of each slice (else the row's). Unmapped rows are not here — they are in Cash outflow.")
                        .total(money(toSuppliers.amount)).chosen(anySupplier ? money(toSuppliers.chosen) : null)
                        .rowCount(toSuppliers.rows)
                        .parties(parties(supplierPayees, chosenSuppliers, nameOf, false)).build())
                .cashOutflow(Row.builder().key("cashOutflow").title("Cash outflow").chosenBy(CHOSEN_BY_SUPPLIERS)
                        .definition("Every bank debit row in the period, whatever it was for. Chosen = the part attributed to ticked suppliers; unmapped = the part no mapping covers yet.")
                        .total(money(outflow.amount)).chosen(anySupplier ? money(outflow.chosen) : null)
                        .secondary(money(unmapped)).secondaryLabel("unmapped")
                        .extras(List.of(fig("unmapped", unmapped), fig("withdrawals", withdrawals)))
                        .rowCount(outflow.rows)
                        .parties(parties(outflowParties, chosenSuppliers, nameOf, false)).build())
                .inventory(InventoryRow.builder().key("inventory").title("Inventory (net, on paper)")
                        .definition("Purchased − write-off − sold per product group for the period, valued at the group's average purchase price per kg. Not a stock level: opening stock is not recorded.")
                        .totalKg(kg(netKgTotal)).totalValue(money(valueTotal)).unpricedCategories(unpriced).levels(levels).build())
                .sales(Row.builder().key("sales").title("Sales").chosenBy(CHOSEN_BY_CUSTOMERS)
                        .definition("₾ of every RS.ge sale document line dated in the period. Real = total minus sales to customers marked unreal on /audit-control and lines mapped paper-only. Chosen = ticked customers.")
                        .total(money(sale.amount)).totalKg(kg(sale.kg))
                        .chosen(anyCustomer ? money(sale.chosen) : null).chosenKg(anyCustomer ? kg(sale.chosenKg) : null)
                        .secondary(money(sale.amount.subtract(unrealTotal))).secondaryLabel("real")
                        .extras(List.of(fig("real", sale.amount.subtract(unrealTotal)), fig("unreal", unrealTotal)))
                        .rowCount(sale.rows)
                        .parties(parties(buyers, chosenCustomers, nameOf, true))
                        .products(groups(saleGroups, anyCustomer)).build())
                .bankInflow(Row.builder().key("bankInflow").title("Bank inflow (payments from customers)").chosenBy(CHOSEN_BY_CUSTOMERS)
                        .definition("Every bank credit row in the period. 'Mapped from customers' = slices in a customer-receipt group; 'unmapped income' = the rest (unmapped, other income, refunds). Chosen = receipts attributed to ticked customers.")
                        .total(money(bankIn.amount)).chosen(anyCustomer ? money(bankIn.chosen) : null)
                        .secondary(money(customerReceipts)).secondaryLabel("mapped from customers")
                        .extras(List.of(fig("mapped from customers", customerReceipts), fig("unmapped income", unmappedIncome)))
                        .rowCount(bankIn.rows)
                        .parties(parties(inflowParties, chosenCustomers, nameOf, false)).build())
                .cashInflow(Row.builder().key("cashInflow").title("Cash inflow (cash from customers)").chosenBy(CHOSEN_BY_CUSTOMERS)
                        .definition("Manual cash payments dated in the period, exactly as the /payments page lists them (manualCashPayments collection). Chosen = ticked customers.")
                        .total(money(cashIn.amount)).chosen(anyCustomer ? money(cashIn.chosen) : null)
                        .rowCount(cashIn.rows)
                        .parties(parties(cashPayers, chosenCustomers, nameOf, false)).build())
                .summary(summary(pur.amount, toSuppliers.amount, withdrawals, withdrawalsToSuppliers, withdrawalsUnresolved,
                        withdrawalsUndocumented, sale.amount, customerReceipts, in.receivables()))
                .notes(notes)
                .build();
    }

    // ==================== bulk map ====================

    /** Maps the listed bank rows of the period in one write. See {@link AuditMappingService#bulkMap}. */
    public AuditBulkMapRequestDto.Result bulkMap(AuditBulkMapRequestDto req, String operator) {
        if (req.getSourceRowIds() == null || req.getSourceRowIds().isEmpty()) {
            throw new ValidationException("sourceRowIds", "Choose at least one transaction");
        }
        if (req.getStartDate() == null || req.getEndDate() == null) {
            throw new ValidationException("startDate", "The period is required to find the rows");
        }
        Map<String, AuditMappingDto> mappings = mappingService.loadMappingIndex();
        List<AuditSourceRowDto> bankRows = sourceRowService.loadBankRows(req.getStartDate(), req.getEndDate(), mappings);
        Set<String> wanted = new HashSet<>(req.getSourceRowIds());
        List<AuditSourceRowDto> rows = new ArrayList<>();
        for (AuditSourceRowDto r : bankRows) {
            if (wanted.contains(r.getSourceRowId())) rows.add(r);
        }
        int notFound = wanted.size() - rows.size();
        AuditBulkMapRequestDto.Result result = mappingService.bulkMap(rows, req, operator);
        result.setSkipped(result.getSkipped() + notFound);
        return result;
    }

    // ==================== transactions ====================

    /**
     * The transactions behind one statement figure. {@code tin} narrows to one
     * counterparty (or {@code name:<label>} for bank rows without a TIN);
     * {@code category} narrows document rows to one product group.
     */
    public List<AuditStatementTransactionDto> transactions(String rowKey, LocalDate startDate, LocalDate endDate,
                                                           String tin, String category, String attribution,
                                                           boolean withdrawalsOnly, int limit) {
        Inputs in = loadInputs(startDate, endDate);
        return transactions(rowKey, tin, category, attribution, withdrawalsOnly, limit, in);
    }

    List<AuditStatementTransactionDto> transactions(String rowKey, String tin, String category, int limit, Inputs in) {
        return transactions(rowKey, tin, category, null, false, limit, in);
    }

    /**
     * @param attributionFilter DIRECT | MAPPED | null — for bank rows narrowed to a party.
     * @param withdrawalsOnly   bank debits with at least one slice in a cash-withdrawal group.
     */
    List<AuditStatementTransactionDto> transactions(String rowKey, String tin, String category, String attributionFilter,
                                                    boolean withdrawalsOnly, int limit, Inputs in) {
        String wantName = tin != null && tin.startsWith("name:") ? tin.substring(5) : null;
        String wantTin = wantName != null ? null : canonical(tin);
        int cap = limit <= 0 ? MAX_TRANSACTIONS : Math.min(limit, MAX_TRANSACTIONS);
        List<AuditStatementTransactionDto> out = new ArrayList<>();
        Function<String, String> nameOf = nameResolver(in);
        switch (rowKey == null ? "" : rowKey) {
            case "purchases", "sales" -> {
                WaybillType want = "purchases".equals(rowKey) ? WaybillType.PURCHASE : WaybillType.SALE;
                List<ProductMovementDto> ms = in.movements();
                List<AuditSourceRowDto> docs = in.documentRows();
                for (int i = 0; i < ms.size(); i++) {
                    ProductMovementDto m = ms.get(i);
                    if (m.getType() != want) continue;
                    String cp = canonical(m.getCounterpartyId());
                    if (wantTin != null && !wantTin.equals(cp)) continue;
                    String cat = categoryOf(m, in.categoryOverrides());
                    if (category != null && !category.isBlank() && !category.equalsIgnoreCase(cat)) continue;
                    AuditSourceRowDto doc = i < docs.size() ? docs.get(i) : null;
                    out.add(AuditStatementTransactionDto.builder()
                            .id(doc != null ? doc.getSourceRowId() : m.getWaybillId() + "#" + i)
                            .kind("DOCUMENT_LINE").date(m.getDate()).direction(want.name())
                            .amount(m.getAmount())
                            .counterpartyTin(cp).counterpartyName(firstNonBlank(m.getCounterpartyName(), cp == null ? null : nameOf.apply(cp)))
                            .productName(m.getProductName()).category(cat)
                            .quantityKg(m.getQuantityKg()).unit(m.getUnit()).waybillId(m.getWaybillId())
                            .sourceType(AuditSourceType.RS_GE).sourceRowId(doc != null ? doc.getSourceRowId() : null)
                            .mappingStatus(doc != null ? doc.getStatus() : null)
                            .mappingSummary(doc != null ? summary(doc.getMapping(), in) : null)
                            .unresolvedAmount(doc != null ? doc.getUnresolvedAmount() : null)
                            .build());
                    if (out.size() >= cap) break;
                }
            }
            case "bankPaymentsToSuppliers", "cashOutflow", "bankInflow" -> {
                boolean supplierOnly = "bankPaymentsToSuppliers".equals(rowKey);
                String wantDirection = "bankInflow".equals(rowKey) ? "CREDIT" : "DEBIT";
                for (AuditSourceRowDto row : in.bankRows()) {
                    if (!wantDirection.equalsIgnoreCase(row.getDirection())) continue;
                    String rowTin = canonical(firstNonBlank(row.getResolvedCounterpartyTin(), row.getCounterpartyTin()));
                    List<AuditMappingSplitDto> splits = AuditMappingService.effectiveSplits(row.getMapping());
                    boolean sliceHits = splits.stream().anyMatch(sp -> {
                        String cp = canonical(firstNonBlank(sp.getCounterpartyTin(), rowTin));
                        boolean cpMatch = wantTin == null || wantTin.equals(cp);
                        AuditCategoryDto c = in.categories().get(sp.getCategoryCode());
                        return cpMatch && (!supplierOnly || (c != null && c.isSupplierSettlement()));
                    });
                    boolean direct = wantTin != null ? wantTin.equals(rowTin)
                            : wantName != null && rowTin == null && wantName.equals(firstNonBlank(row.getCounterpartyName(), "no counterparty"));
                    boolean rowHits;
                    if (wantName != null) {
                        rowHits = direct && (!supplierOnly || sliceHits);
                    } else if (supplierOnly) {
                        rowHits = sliceHits;
                    } else {
                        rowHits = wantTin == null || direct || sliceHits;
                    }
                    if (!rowHits) continue;
                    boolean withdrawal = splits.stream().anyMatch(sp -> {
                        AuditCategoryDto c = in.categories().get(sp.getCategoryCode());
                        return c != null && c.isCashWithdrawal();
                    });
                    if (withdrawalsOnly && !withdrawal) continue;
                    String attribution = wantTin == null && wantName == null ? null : direct ? "DIRECT" : "MAPPED";
                    if (attributionFilter != null && !attributionFilter.equalsIgnoreCase(attribution)) continue;
                    List<String> mappedTo = new ArrayList<>();
                    for (AuditMappingSplitDto sp : splits) {
                        String label = firstNonBlank(sp.getCounterpartyName(), sp.getCounterpartyTin());
                        if (label != null && !mappedTo.contains(label)) mappedTo.add(label);
                    }
                    out.add(AuditStatementTransactionDto.builder()
                            .id(row.getSourceRowId()).kind("BANK_ROW").date(row.getDate()).direction(wantDirection)
                            .amount(row.getAmount() == null ? null : row.getAmount().abs())
                            .counterpartyTin(rowTin).counterpartyName(firstNonBlank(row.getCounterpartyName(), rowTin == null ? null : nameOf.apply(rowTin)))
                            .description(row.getDescription()).reference(row.getReference()).source(row.getTransactionType())
                            .sourceType(row.getSourceType()).sourceRowId(row.getSourceRowId())
                            .mappingStatus(row.getStatus()).mappingSummary(summary(row.getMapping(), in))
                            .unresolvedAmount(row.getUnresolvedAmount())
                            .mappedCounterparties(mappedTo).withdrawal(withdrawal).attribution(attribution)
                            .sourceRow(row)
                            .build());
                    if (out.size() >= cap) break;
                }
            }
            case "cashInflow" -> {
                for (PaymentDto pmt : in.cashPayments()) {
                    String cp = canonical(pmt.getCustomerId());
                    if (wantTin != null && !wantTin.equals(cp)) continue;
                    if (wantName != null && !(cp == null && wantName.equals(firstNonBlank(pmt.getCustomerName(), "unknown")))) continue;
                    out.add(AuditStatementTransactionDto.builder()
                            .id(pmt.getId()).kind("CASH_PAYMENT").date(pmt.getPaymentDate())
                            .amount(pmt.getAmount())
                            .counterpartyTin(cp).counterpartyName(firstNonBlank(pmt.getCustomerName(), cp == null ? null : nameOf.apply(cp)))
                            .description(pmt.getDescription()).reference(pmt.getUniqueCode()).source(pmt.getSource())
                            .build());
                    if (out.size() >= cap) break;
                }
            }
            default -> throw new ValidationException("row", "Unknown statement row '" + rowKey + "'");
        }
        out.sort(Comparator.comparing((AuditStatementTransactionDto t) -> t.getDate() == null ? LocalDate.MIN : t.getDate()).reversed());
        return out;
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

    /** Running total with its chosen part. */
    private static final class Money {
        BigDecimal amount = BigDecimal.ZERO, kg = BigDecimal.ZERO, chosen = BigDecimal.ZERO, chosenKg = BigDecimal.ZERO; int rows;
        void add(BigDecimal a, BigDecimal k, boolean isChosen) {
            rows++;
            amount = amount.add(a); kg = kg.add(k);
            if (isChosen) { chosen = chosen.add(a); chosenKg = chosenKg.add(k); }
        }
        /** Amount only — for rows that contribute several slices but count once. */
        void addAmount(BigDecimal a, boolean isChosen) {
            amount = amount.add(a);
            if (isChosen) chosen = chosen.add(a);
        }
    }

    static Summary summary(BigDecimal purchases, BigDecimal bankToSuppliers, BigDecimal withdrawals, BigDecimal withdrawalsToSuppliers,
                           BigDecimal withdrawalsUnresolved, BigDecimal withdrawalsUndocumented,
                           BigDecimal sales, BigDecimal customerReceipts, BigDecimal receivables) {
        BigDecimal cashToReceive = receivables == null ? null : sales.subtract(customerReceipts).subtract(receivables);
        return Summary.builder()
                .purchases(money(purchases)).bankPaymentsToSuppliers(money(bankToSuppliers))
                .possibleChecksNeeded(money(purchases.subtract(bankToSuppliers)))
                .withdrawals(money(withdrawals)).withdrawalsToSuppliers(money(withdrawalsToSuppliers))
                .withdrawalsUnresolved(money(withdrawalsUnresolved)).withdrawalsUndocumented(money(withdrawalsUndocumented))
                .sales(money(sales)).bankReceiptsFromCustomers(money(customerReceipts))
                .receivables(money(receivables))
                .cashToReceiveFromCustomers(money(cashToReceive))
                // Cash that went elsewhere, documented as such, cannot reach a supplier.
                .cashToPaySuppliers(cashToReceive == null ? null : money(withdrawals.subtract(withdrawalsUndocumented).add(cashToReceive)))
                .build();
    }

    private static Figure fig(String label, BigDecimal v) {
        return Figure.builder().label(label).amount(money(v)).build();
    }

    private static final class PartyAcc {
        final String tin; String name; String identityBasis; boolean unreal;
        BigDecimal amount = BigDecimal.ZERO, kg = BigDecimal.ZERO, secondary = BigDecimal.ZERO; int rows;
        BigDecimal direct = BigDecimal.ZERO, mapped = BigDecimal.ZERO, bankPaid = BigDecimal.ZERO; int directCount, mappedCount;
        PartyAcc(String tin) { this.tin = tin; }
        void add(BigDecimal a, BigDecimal k) { amount = amount.add(a); kg = kg.add(k); rows++; }
    }

    private static final class GroupAcc {
        final String category; final Set<String> products = new HashSet<>();
        BigDecimal amount = BigDecimal.ZERO, kg = BigDecimal.ZERO, chosenAmount = BigDecimal.ZERO, chosenKg = BigDecimal.ZERO; int rows;
        GroupAcc(String c) { this.category = c; }
        void add(BigDecimal a, BigDecimal k, boolean chosen, String product) {
            amount = amount.add(a); kg = kg.add(k); rows++;
            if (chosen) { chosenAmount = chosenAmount.add(a); chosenKg = chosenKg.add(k); }
            if (product != null) products.add(product.trim().toLowerCase(Locale.ROOT));
        }
    }

    private static final class InvAcc {
        final String category;
        BigDecimal purchasedKg = BigDecimal.ZERO, purchasedAmountKgLines = BigDecimal.ZERO, soldKg = BigDecimal.ZERO, soldAmount = BigDecimal.ZERO;
        final List<Lot> lots = new ArrayList<>();
        InvAcc(String c) { this.category = c; }
    }

    private static List<Party> parties(Map<String, PartyAcc> accs, Set<String> chosen, Function<String, String> nameOf, boolean withKg) {
        return parties(accs, chosen, nameOf, withKg, false);
    }

    private static List<Party> parties(Map<String, PartyAcc> accs, Set<String> chosen, Function<String, String> nameOf, boolean withKg, boolean purchases) {
        List<Party> out = new ArrayList<>();
        for (Map.Entry<String, PartyAcc> e : accs.entrySet()) {
            PartyAcc a = e.getValue();
            String tin = a.tin;
            String name = firstNonBlank(tin == null ? null : nameOf.apply(tin), a.name, tin, "no counterparty");
            if (tin != null && name.equals(tin) && a.name != null && !a.name.isBlank()) name = a.name;
            out.add(Party.builder()
                    .tin(tin != null ? tin : e.getKey())
                    .name(name)
                    .amount(money(a.amount)).quantityKg(withKg ? kg(a.kg) : null)
                    .secondary(a.secondary.signum() != 0 ? money(a.secondary) : null)
                    .directAmount(a.directCount > 0 || a.mappedCount > 0 ? money(a.direct) : null).directCount(a.directCount)
                    .mappedAmount(a.directCount > 0 || a.mappedCount > 0 ? money(a.mapped) : null).mappedCount(a.mappedCount)
                    .bankPaid(purchases ? money(a.bankPaid) : null)
                    .unpaidAfterBank(purchases ? money(a.amount.subtract(a.bankPaid)) : null)
                    .rowCount(a.rows).chosen(chosen.contains(tin != null ? tin : e.getKey())).unreal(a.unreal)
                    .identityBasis(a.identityBasis)
                    .build());
        }
        out.sort(Comparator.comparing(Party::getAmount, Comparator.reverseOrder()));
        return out;
    }

    private static List<ProductGroup> groups(Map<String, GroupAcc> accs, boolean withChosen) {
        List<ProductGroup> out = new ArrayList<>();
        for (GroupAcc g : accs.values()) {
            out.add(ProductGroup.builder().category(g.category).amount(money(g.amount)).quantityKg(kg(g.kg))
                    .chosenAmount(withChosen ? money(g.chosenAmount) : null).chosenKg(withChosen ? kg(g.chosenKg) : null)
                    .rowCount(g.rows).productCount(g.products.size()).build());
        }
        out.sort(Comparator.comparing(ProductGroup::getAmount, Comparator.reverseOrder()));
        return out;
    }

    static String categoryOf(ProductMovementDto m, Map<String, String> overrides) {
        String c = ProductCategoryResolver.resolve(m.getProductName(), m.getParentCategory(), overrides);
        return c == null ? "OTHER" : c;
    }

    /** RS.ge document name wins, then the customer register, then the bare TIN. */
    private static Function<String, String> nameResolver(Inputs in) {
        Map<String, String> known = new LinkedHashMap<>();
        for (ProductMovementDto m : in.movements()) {
            String tin = canonical(m.getCounterpartyId());
            if (tin != null && m.getCounterpartyName() != null && !m.getCounterpartyName().isBlank()) {
                known.putIfAbsent(tin, m.getCounterpartyName().trim());
            }
        }
        for (PaymentDto p : in.bankPayments()) {
            String tin = canonical(p.getCustomerId());
            if (tin != null && p.getCustomerName() != null && !p.getCustomerName().isBlank()) known.putIfAbsent(tin, p.getCustomerName().trim());
        }
        for (AuditSourceRowDto row : in.bankRows()) {
            String tin = canonical(firstNonBlank(row.getResolvedCounterpartyTin(), row.getCounterpartyTin()));
            if (tin != null && row.getCounterpartyName() != null) known.putIfAbsent(tin, row.getCounterpartyName());
        }
        in.names().forEach(known::putIfAbsent);
        return tin -> {
            String n = known.get(tin);
            return n != null ? n : tin;
        };
    }

    private static String summary(AuditMappingDto mapping, Inputs in) {
        List<AuditMappingSplitDto> splits = AuditMappingService.effectiveSplits(mapping);
        if (splits.isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        for (AuditMappingSplitDto s : splits) {
            AuditCategoryDto c = in.categories().get(s.getCategoryCode());
            String label = c != null && c.getLabel() != null ? c.getLabel() : s.getCategoryCode();
            StringBuilder b = new StringBuilder(label == null ? "?" : label);
            if (s.getSubgroupCode() != null && !s.getSubgroupCode().isBlank()) {
                AuditSubgroupDto sg = in.subgroups().get(s.getSubgroupCode());
                b.append(" · ").append(sg != null && sg.getLabel() != null ? sg.getLabel() : s.getSubgroupCode());
            }
            String cp = firstNonBlank(s.getCounterpartyName(), s.getCounterpartyTin());
            if (cp != null) b.append(" → ").append(cp);
            if (s.getAmount() != null) b.append(" (").append(s.getAmount().setScale(2, RoundingMode.HALF_UP)).append(")");
            parts.add(b.toString());
        }
        return String.join("; ", parts);
    }

    static String canonical(String tin) {
        if (tin == null || tin.isBlank()) return null;
        String c = TinValidator.canonicalId(tin);
        return c == null || c.isBlank() ? null : c;
    }

    static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    static BigDecimal money(BigDecimal v) {
        return v == null ? null : v.setScale(MONEY, RoundingMode.HALF_UP);
    }

    static BigDecimal kg(BigDecimal v) {
        return v == null ? null : v.setScale(KG, RoundingMode.HALF_UP);
    }

    static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
