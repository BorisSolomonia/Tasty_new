package ge.tastyerp.payment.service.audit;

import ge.tastyerp.common.dto.audit.*;
import ge.tastyerp.common.dto.payment.CustomerDebtDto;
import ge.tastyerp.common.dto.payment.PaymentDto;
import ge.tastyerp.common.dto.waybill.WaybillType;
import ge.tastyerp.common.exception.ExternalServiceException;
import ge.tastyerp.common.util.TinValidator;
import ge.tastyerp.payment.repository.AuditExceptionRepository;
import ge.tastyerp.payment.repository.PaymentOverrideRepository;
import ge.tastyerp.payment.repository.PaymentRepository;
import ge.tastyerp.payment.service.DebtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Orchestrates the Audit Control dashboard (BOR-74).
 *
 * Pulls product movements from waybill-service, customer debt analysis from
 * {@link CustomerAnalysisService}, payments from Firestore and customer entity
 * classification from config-service, then assembles:
 *  - the date-range inventory ledger (with the daily write-off algorithm),
 *  - "Real Total Sales/Purchases" filtered by is_real_entity,
 *  - real-vs-exception debt reconciliation (with manual paid overrides),
 *  - targeted-ID expense extraction, and
 *  - tracked reconciliation exceptions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditControlService {

    private final DebtService debtService;
    private final PaymentRepository paymentRepository;
    private final AuditExceptionRepository exceptionRepository;
    private final PaymentOverrideRepository overrideRepository;
    private final WriteOffCalculator writeOffCalculator;

    /** Field name matches the bean name "internalRestTemplate" for by-name resolution. */
    private final RestTemplate internalRestTemplate;

    @Value("${waybill.service.url:http://waybill-service:8081}")
    private String waybillServiceUrl;

    @Value("${config.service.url:http://config-service:8888}")
    private String configServiceUrl;

    @Value("${audit.targeted-expense-id:01008026584}")
    private String targetedExpenseId;

    // ==================== DASHBOARD ====================

    public AuditDashboardDto getDashboard(LocalDate startDate, LocalDate endDate, String productFilter) {
        log.info("Building audit dashboard {} to {} (filter={})", startDate, endDate, productFilter);
        long t0 = System.currentTimeMillis();

        List<ProductMovementDto> movements = fetchProductMovements(startDate, endDate).stream()
                .filter(m -> m.getDate() != null
                        && !m.getDate().isBefore(startDate) && !m.getDate().isAfter(endDate))
                .collect(Collectors.toList());
        long tMovements = System.currentTimeMillis();

        // Apply user category overrides (one category per product name); fall back
        // to the auto-classification already set by waybill-service.
        // Deliberately fetched fresh on every request (BOR-75 integrity contract):
        // user-editable state is never cached.
        Map<String, String> overrides = fetchCategoryOverrides();
        movements.forEach(m -> m.setParentCategory(resolveCategory(m.getProductName(), m.getParentCategory(), overrides)));

        Map<String, Boolean> realEntityMap = fetchRealEntityMap();
        long tConfig = System.currentTimeMillis();

        List<InventoryLedgerDto> ledgers = buildLedgers(movements, productFilter);
        RealTotalsDto realTotals = computeRealTotals(movements, realEntityMap);
        long tCompute = System.currentTimeMillis();

        List<ReconciliationRowDto> reconciliation = buildReconciliation(realEntityMap);
        BigDecimal realDebtTotal = reconciliation.stream()
                .map(ReconciliationRowDto::getRealDebt).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal exceptionDebtTotal = reconciliation.stream()
                .map(ReconciliationRowDto::getExceptionDebt).reduce(BigDecimal.ZERO, BigDecimal::add);
        long tRecon = System.currentTimeMillis();

        TargetedExpenseDto targetedExpense = computeTargetedExpense(startDate, endDate);

        // Micro-level pipeline profile (BOR-75): stage durations in ms.
        log.info("Dashboard pipeline: movements={}ms config={}ms ledgers+totals={}ms reconciliation={}ms targeted={}ms total={}ms ({} movements)",
                tMovements - t0, tConfig - tMovements, tCompute - tConfig,
                tRecon - tCompute, System.currentTimeMillis() - tRecon,
                System.currentTimeMillis() - t0, movements.size());

        return AuditDashboardDto.builder()
                .startDate(startDate)
                .endDate(endDate)
                .productFilter(productFilter)
                .realTotals(realTotals)
                .inventoryLedgers(ledgers)
                .reconciliation(reconciliation)
                .realDebtTotal(realDebtTotal)
                .exceptionDebtTotal(exceptionDebtTotal)
                .targetedExpense(targetedExpense)
                .exceptions(exceptionRepository.findAll())
                .build();
    }

    // ==================== PRODUCT CATALOG ====================

    /**
     * Distinct product names seen on waybills in the range, split into purchased
     * and sold lists, each with its resolved category (override else auto). Powers
     * the Product Categories management page.
     */
    public ProductCatalogDto getProductCatalog(LocalDate startDate, LocalDate endDate) {
        List<ProductMovementDto> movements = fetchProductMovements(startDate, endDate).stream()
                .filter(m -> m.getDate() != null
                        && !m.getDate().isBefore(startDate) && !m.getDate().isAfter(endDate))
                .collect(Collectors.toList());

        Map<String, String> overrides = fetchCategoryOverrides();

        return ProductCatalogDto.builder()
                .purchased(distinctRows(movements, WaybillType.PURCHASE, overrides))
                .sold(distinctRows(movements, WaybillType.SALE, overrides))
                .build();
    }

    private List<ProductCatalogDto.Row> distinctRows(List<ProductMovementDto> movements,
                                                     WaybillType type,
                                                     Map<String, String> overrides) {
        Map<String, ProductCatalogDto.Row> byName = new LinkedHashMap<>();
        for (ProductMovementDto m : movements) {
            if (m.getType() != type || m.getProductName() == null) continue;
            String name = m.getProductName().trim();
            if (name.isEmpty() || byName.containsKey(name)) continue;
            String key = overrideKey(name);
            boolean overridden = overrides.containsKey(key);
            String category = overridden ? overrides.get(key) : m.getParentCategory();
            byName.put(name, ProductCatalogDto.Row.builder()
                    .name(name)
                    .category(category)
                    .overridden(overridden)
                    .build());
        }
        List<ProductCatalogDto.Row> rows = new ArrayList<>(byName.values());
        rows.sort(Comparator.comparing(ProductCatalogDto.Row::getName));
        return rows;
    }

    private String resolveCategory(String productName, String autoCategory, Map<String, String> overrides) {
        if (productName == null) return autoCategory;
        return overrides.getOrDefault(overrideKey(productName), autoCategory);
    }

    /** Case-insensitive, trimmed key used to match a product name to its override. */
    private String overrideKey(String name) {
        return name == null ? "" : name.trim().toLowerCase();
    }

    // ==================== INVENTORY LEDGER ====================

    /**
     * Build one ledger per parent product category. Child products are
     * aggregated into the parent node. Opening stock defaults to zero because
     * no historical physical-stock source exists yet (documented limitation);
     * inventory is then carried forward day-by-day across the range.
     */
    public List<InventoryLedgerDto> buildLedgers(List<ProductMovementDto> movements, String productFilter) {
        Map<String, List<ProductMovementDto>> byCategory = movements.stream()
                .filter(m -> m.getParentCategory() != null)
                .filter(m -> productFilter == null || productFilter.isBlank()
                        || productFilter.equalsIgnoreCase(m.getParentCategory()))
                .collect(Collectors.groupingBy(ProductMovementDto::getParentCategory));

        List<InventoryLedgerDto> ledgers = new ArrayList<>();
        for (Map.Entry<String, List<ProductMovementDto>> entry : byCategory.entrySet()) {
            ledgers.add(buildLedgerForCategory(entry.getKey(), entry.getValue()));
        }
        ledgers.sort(Comparator.comparing(InventoryLedgerDto::getParentCategory));
        return ledgers;
    }

    private InventoryLedgerDto buildLedgerForCategory(String category, List<ProductMovementDto> movements) {
        // OTHER / Unclassified carries inventory forward without a write-off ceiling.
        boolean applyWriteOff = ProductHierarchy.appliesWriteOff(category);

        // Sum purchased/sold kg per active day.
        Map<LocalDate, BigDecimal> purchasedByDay = new HashMap<>();
        Map<LocalDate, BigDecimal> soldByDay = new HashMap<>();
        Set<String> childProducts = new LinkedHashSet<>();
        int excludedNonKgLines = 0;

        for (ProductMovementDto m : movements) {
            if (m.getProductName() != null) {
                childProducts.add(m.getProductName());
            }
            // Inventory conservation is kg-based: skip lines measured in pieces /
            // other units so they don't corrupt the running balance.
            if (!isKilogram(m.getUnit())) {
                excludedNonKgLines++;
                continue;
            }
            BigDecimal qty = m.getQuantityKg() != null ? m.getQuantityKg() : BigDecimal.ZERO;
            if (m.getType() == WaybillType.PURCHASE) {
                purchasedByDay.merge(m.getDate(), qty, BigDecimal::add);
            } else {
                soldByDay.merge(m.getDate(), qty, BigDecimal::add);
            }
        }

        if (excludedNonKgLines > 0) {
            log.info("Category {}: excluded {} non-kg line(s) from inventory conservation",
                    category, excludedNonKgLines);
        }

        List<LocalDate> activeDays = new ArrayList<>(
                new TreeSet<>(unionKeys(purchasedByDay, soldByDay)));

        BigDecimal running = BigDecimal.ZERO; // opening stock (default 0)
        BigDecimal totalPurchased = BigDecimal.ZERO;
        BigDecimal totalSold = BigDecimal.ZERO;
        BigDecimal totalWriteOff = BigDecimal.ZERO;
        int overageDays = 0;
        List<DailyLedgerRowDto> rows = new ArrayList<>();

        for (LocalDate day : activeDays) {
            BigDecimal purchased = purchasedByDay.getOrDefault(day, BigDecimal.ZERO);
            BigDecimal sold = soldByDay.getOrDefault(day, BigDecimal.ZERO);

            DailyLedgerRowDto row = applyWriteOff
                    ? writeOffCalculator.computeDay(day, running, purchased, sold)
                    : writeOffCalculator.passthroughDay(day, running, purchased, sold);
            rows.add(row);

            running = row.getEndingInventoryKg();
            totalPurchased = totalPurchased.add(row.getPurchasedKg());
            totalSold = totalSold.add(row.getSoldKg());
            totalWriteOff = totalWriteOff.add(row.getWriteOffKg());
            if (row.isOverage()) {
                overageDays++;
            }
        }

        return InventoryLedgerDto.builder()
                .parentCategory(category)
                .childProducts(new ArrayList<>(childProducts))
                .openingStockKg(BigDecimal.ZERO)
                .totalPurchasedKg(totalPurchased)
                .totalSoldKg(totalSold)
                .totalWriteOffKg(totalWriteOff)
                .endingInventoryKg(running)
                .overageDays(overageDays)
                .dailyRows(rows)
                .build();
    }

    private Set<LocalDate> unionKeys(Map<LocalDate, ?> a, Map<LocalDate, ?> b) {
        Set<LocalDate> keys = new HashSet<>(a.keySet());
        keys.addAll(b.keySet());
        keys.remove(null);
        return keys;
    }

    // ==================== REAL TOTALS ====================

    private RealTotalsDto computeRealTotals(List<ProductMovementDto> movements, Map<String, Boolean> realEntityMap) {
        BigDecimal realSales = BigDecimal.ZERO, excludedSales = BigDecimal.ZERO;
        BigDecimal realPurchases = BigDecimal.ZERO, excludedPurchases = BigDecimal.ZERO;
        Set<String> realEntities = new HashSet<>();
        Set<String> excludedEntities = new HashSet<>();

        for (ProductMovementDto m : movements) {
            BigDecimal amount = m.getAmount() != null ? m.getAmount() : BigDecimal.ZERO;
            boolean real = isReal(m.getCounterpartyId(), realEntityMap);
            String entity = m.getCounterpartyId() != null ? m.getCounterpartyId() : "UNKNOWN";
            (real ? realEntities : excludedEntities).add(entity);

            if (m.getType() == WaybillType.SALE) {
                if (real) realSales = realSales.add(amount); else excludedSales = excludedSales.add(amount);
            } else {
                if (real) realPurchases = realPurchases.add(amount); else excludedPurchases = excludedPurchases.add(amount);
            }
        }

        return RealTotalsDto.builder()
                .realTotalSales(realSales)
                .realTotalPurchases(realPurchases)
                .excludedSales(excludedSales)
                .excludedPurchases(excludedPurchases)
                .realEntityCount(realEntities.size())
                .excludedEntityCount(excludedEntities.size())
                .build();
    }

    // ==================== RECONCILIATION ====================

    private List<ReconciliationRowDto> buildReconciliation(Map<String, Boolean> realEntityMap) {
        // Read the SAME authoritative debt the payments page reads, so audit and
        // payments can never disagree (single source of truth).
        List<CustomerDebtDto> analysis = debtService.getOverview().getCustomers();
        Set<String> paidKeys = overrideRepository.findMarkedPaidKeys();

        List<ReconciliationRowDto> rows = new ArrayList<>();
        for (CustomerDebtDto a : analysis) {
            boolean real = isReal(a.getCustomerId(), realEntityMap);
            boolean paid = paidKeys.contains(a.getCustomerId());
            BigDecimal debt = a.getCurrentDebt() != null ? a.getCurrentDebt() : BigDecimal.ZERO;

            BigDecimal realDebt = (real && !paid) ? debt : BigDecimal.ZERO;
            BigDecimal exceptionDebt = (!real && !paid) ? debt : BigDecimal.ZERO;

            rows.add(ReconciliationRowDto.builder()
                    .customerId(a.getCustomerId())
                    .customerName(a.getCustomerName())
                    .realEntity(real)
                    .totalSales(a.getTotalSales())
                    .totalPayments(a.getTotalPayments())
                    .currentDebt(debt)
                    .realDebt(realDebt)
                    .exceptionDebt(exceptionDebt)
                    .manuallyMarkedPaid(paid)
                    .build());
        }
        rows.sort(Comparator.comparing(r -> r.getCustomerName() != null ? r.getCustomerName() : ""));
        return rows;
    }

    /** Manually mark (or unmark) a customer/transaction balance as paid (BOR-74 override). */
    public void setManualPaid(String key, boolean markedPaid, String note) {
        overrideRepository.setMarkedPaid(key, markedPaid, note);
    }

    // ==================== TARGETED ID EXPENSE ====================

    public TargetedExpenseDto computeTargetedExpense(LocalDate startDate, LocalDate endDate) {
        String targetNorm = TinValidator.canonicalId(targetedExpenseId);

        List<PaymentDto> candidates = new ArrayList<>();
        candidates.addAll(paymentRepository.findBankPayments(null, startDate, endDate, null));
        candidates.addAll(paymentRepository.findManualPayments(null, startDate, endDate, null));

        List<TargetedExpenseDto.Match> matches = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;

        for (PaymentDto p : candidates) {
            String payerNorm = p.getCustomerId() != null ? TinValidator.canonicalId(p.getCustomerId()) : null;
            boolean matchById = targetNorm != null && targetNorm.equals(payerNorm);
            boolean matchByDesc = p.getDescription() != null
                    && (p.getDescription().contains(targetedExpenseId)
                        || (targetNorm != null && p.getDescription().contains(targetNorm)));

            if (matchById || matchByDesc) {
                BigDecimal amount = p.getAmount() != null ? p.getAmount() : BigDecimal.ZERO;
                total = total.add(amount);
                matches.add(TargetedExpenseDto.Match.builder()
                        .paymentId(p.getId())
                        .source(p.getSource())
                        .amount(amount)
                        .date(p.getPaymentDate())
                        .description(p.getDescription())
                        .matchedOnDescription(!matchById && matchByDesc)
                        .build());
            }
        }

        return TargetedExpenseDto.builder()
                .targetId(targetedExpenseId)
                .totalExpense(total)
                .matchCount(matches.size())
                .matches(matches)
                .build();
    }

    // ==================== EXCEPTIONS ====================

    public List<AuditExceptionDto> getExceptions() {
        return exceptionRepository.findAll();
    }

    public AuditExceptionDto saveException(AuditExceptionDto exception) {
        if (exception.getType() == null || exception.getType().isBlank()) {
            exception.setType("MANUAL");
        }
        if (exception.getId() == null) {
            exception.setManual(true);
        }
        return exceptionRepository.save(exception);
    }

    public void deleteException(String id) {
        exceptionRepository.delete(id);
    }

    // ==================== HELPERS ====================

    @SuppressWarnings("unchecked")
    private List<ProductMovementDto> fetchProductMovements(LocalDate startDate, LocalDate endDate) {
        try {
            String url = String.format("%s/api/waybills/product-movements?startDate=%s&endDate=%s",
                    waybillServiceUrl, startDate, endDate);
            Map<String, Object> response = internalRestTemplate.getForObject(url, Map.class);
            if (response == null || !(response.get("data") instanceof List)) {
                return Collections.emptyList();
            }
            List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
            return data.stream().map(this::toMovement).collect(Collectors.toList());
        } catch (Exception e) {
            throw new ExternalServiceException("waybill-service", "fetch product movements", e);
        }
    }

    private ProductMovementDto toMovement(Map<String, Object> m) {
        return ProductMovementDto.builder()
                .date(m.get("date") != null ? LocalDate.parse(String.valueOf(m.get("date"))) : null)
                .type(m.get("type") != null ? WaybillType.valueOf(String.valueOf(m.get("type"))) : null)
                .productName((String) m.get("productName"))
                .parentCategory((String) m.get("parentCategory"))
                .quantityKg(toBigDecimal(m.get("quantityKg")))
                .unit((String) m.get("unit"))
                .amount(toBigDecimal(m.get("amount")))
                .waybillId((String) m.get("waybillId"))
                .counterpartyId((String) m.get("counterpartyId"))
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Boolean> fetchRealEntityMap() {
        Map<String, Boolean> map = new HashMap<>();
        try {
            String url = configServiceUrl + "/api/config/customers";
            Map<String, Object> response = internalRestTemplate.getForObject(url, Map.class);
            if (response != null && response.get("data") instanceof List) {
                List<Map<String, Object>> customers = (List<Map<String, Object>>) response.get("data");
                for (Map<String, Object> c : customers) {
                    String id = (String) c.get("identification");
                    if (id == null) continue;
                    Object real = c.get("isRealEntity");
                    // null => treated as real by default. Key by canonical id so it
                    // matches the canonical debt rows (leading-zero variants merged).
                    map.put(TinValidator.canonicalId(id), real == null || Boolean.TRUE.equals(real));
                }
            }
        } catch (Exception e) {
            throw new ExternalServiceException("config-service", "fetch customer entity classification", e);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> fetchCategoryOverrides() {
        Map<String, String> map = new HashMap<>();
        try {
            String url = configServiceUrl + "/api/config/product-categories";
            Map<String, Object> response = internalRestTemplate.getForObject(url, Map.class);
            if (response != null && response.get("data") instanceof List) {
                List<Map<String, Object>> items = (List<Map<String, Object>>) response.get("data");
                for (Map<String, Object> c : items) {
                    Object name = c.get("name");
                    Object category = c.get("category");
                    if (name != null && category != null) {
                        map.put(overrideKey(String.valueOf(name)), String.valueOf(category));
                    }
                }
            }
        } catch (Exception e) {
            // Overrides are optional refinement; if config is unreachable, fall back
            // to auto-classification rather than failing the whole dashboard.
            log.warn("Could not fetch product category overrides, using auto-classification: {}", e.getMessage());
        }
        return map;
    }

    private boolean isReal(String id, Map<String, Boolean> realEntityMap) {
        if (id == null) return true; // unknown counterparties default to real
        return realEntityMap.getOrDefault(TinValidator.canonicalId(id), true);
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        return new BigDecimal(String.valueOf(value));
    }

    /** Unit substrings (lowercased) that mark a line as NOT measured in kilograms. */
    private static final List<String> NON_KG_UNITS = List.of(
            "ცალ",      // ცალი – pieces
            "piece", "pcs",
            "ლიტრ", "liter", "litre",  // volume
            "შეკვრ",    // bundle / pack
            "კომპლ", "pack", "set",
            "წყვილ",    // pair
            "გრამ", "gram"             // grams – mass but not kg; excluded to avoid unit mismatch
    );

    /**
     * Whether a goods line's unit is kilograms (the basis for inventory
     * conservation). Blank/unknown units default to kg because meat lines are
     * overwhelmingly kg and RS.ge's kg encoding is not guaranteed; only units
     * explicitly recognised as pieces/volume/etc. are excluded.
     */
    private boolean isKilogram(String unit) {
        if (unit == null || unit.isBlank()) return true;
        String u = unit.trim().toLowerCase();
        if (u.contains("კგ") || u.contains("kg") || u.contains("კილ") || u.contains("kilo")) {
            return true;
        }
        for (String nonKg : NON_KG_UNITS) {
            if (u.contains(nonKg)) return false;
        }
        return true;
    }
}
