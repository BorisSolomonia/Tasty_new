package ge.tastyerp.payment.service.audit;

import ge.tastyerp.common.dto.audit.*;
import ge.tastyerp.common.dto.config.CategoryLedgerInputDto;
import ge.tastyerp.common.dto.config.FormalSalesCustomerDto;
import ge.tastyerp.common.dto.waybill.WaybillType;
import ge.tastyerp.common.exception.ExternalServiceException;
import ge.tastyerp.common.util.TinValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Dual-ledger / shadow-cash-flow analytics (BOR-76).
 *
 * Assembles, READ-ONLY, four views from RS.ge documented movements + editable
 * config overrides — nothing in /payments or /waybills is modified:
 * <ol>
 *   <li>Purchase cash shortage (docTotal − realTotal) per category;</li>
 *   <li>Sales cash surplus (realTotal − docTotal) per category;</li>
 *   <li>Formal-sales commission AR (documentedKg × commissionPerKg);</li>
 *   <li>VAT (actual documented output − input, 18% inclusive) per category, with
 *       the write-off % shown as context and an optional what-if projection.</li>
 * </ol>
 *
 * The {@link #compute} method is pure (no I/O) so it can be parity-tested against
 * the issue's worked examples.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DualLedgerService {

    /** VAT is 18% inclusive: vat = gross × 18 / 118 (matches waybill-service). */
    private static final BigDecimal VAT_MUL = new BigDecimal("18");
    private static final BigDecimal VAT_DIV = new BigDecimal("118");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int MONEY = 2;
    private static final int KG = 3;
    private static final int PRICE = 6;

    /** Field name matches the bean name "internalRestTemplate" for by-name resolution. */
    private final RestTemplate internalRestTemplate;

    @Value("${waybill.service.url:http://waybill-service:8081}")
    private String waybillServiceUrl;

    @Value("${config.service.url:http://config-service:8888}")
    private String configServiceUrl;

    // ==================== ENTRYPOINT (I/O) ====================

    public DualLedgerDto getDualLedger(LocalDate startDate, LocalDate endDate, String productFilter) {
        log.info("Building dual-ledger {} to {} (filter={})", startDate, endDate, productFilter);

        List<ProductMovementDto> movements = fetchProductMovements(startDate, endDate).stream()
                .filter(m -> m.getDate() != null
                        && !m.getDate().isBefore(startDate) && !m.getDate().isAfter(endDate))
                .collect(Collectors.toList());

        // Apply user category overrides (fresh each request — user-editable state never cached).
        Map<String, String> overrides = fetchCategoryOverrides();
        movements.forEach(m -> m.setParentCategory(
                resolveCategory(m.getProductName(), m.getParentCategory(), overrides)));

        Map<String, CategoryLedgerInputDto> inputs = fetchDualLedgerInputs();
        Map<String, FormalSalesCustomerDto> formal = fetchFormalSalesCustomers();
        Map<String, BigDecimal> writeOffPercent = fetchWriteOffRates();

        return compute(movements, startDate, endDate, productFilter, inputs, formal, writeOffPercent);
    }

    // ==================== PURE COMPUTATION ====================

    /**
     * Pure assembly of the dual-ledger payload. All inputs are supplied so this
     * can be unit/parity-tested without any network calls.
     *
     * @param movements       category-resolved product movements in range
     * @param inputs          per-category editable overrides (may be empty)
     * @param formal          formal-sales customers keyed by canonical TIN
     * @param writeOffPercent per-category write-off % (context / projection)
     */
    DualLedgerDto compute(List<ProductMovementDto> movements,
                          LocalDate startDate, LocalDate endDate, String productFilter,
                          Map<String, CategoryLedgerInputDto> inputs,
                          Map<String, FormalSalesCustomerDto> formal,
                          Map<String, BigDecimal> writeOffPercent) {

        List<ProductMovementDto> filtered = movements.stream()
                .filter(m -> m.getParentCategory() != null)
                .filter(m -> productFilter == null || productFilter.isBlank()
                        || productFilter.equalsIgnoreCase(m.getParentCategory()))
                .collect(Collectors.toList());

        Set<String> categories = new TreeSet<>();
        filtered.forEach(m -> categories.add(m.getParentCategory()));

        List<CategoryCashGapDto> purchaseShortages = new ArrayList<>();
        List<CategoryCashGapDto> saleSurpluses = new ArrayList<>();
        List<CategoryVatDto> vatList = new ArrayList<>();

        for (String category : categories) {
            BigDecimal purKgDoc = BigDecimal.ZERO, purAmtKg = BigDecimal.ZERO, purAmtAll = BigDecimal.ZERO;
            BigDecimal saleKgDoc = BigDecimal.ZERO, saleAmtKg = BigDecimal.ZERO, saleAmtAll = BigDecimal.ZERO;

            for (ProductMovementDto m : filtered) {
                if (!category.equals(m.getParentCategory())) continue;
                BigDecimal amount = nz(m.getAmount());
                BigDecimal kg = nz(m.getQuantityKg());
                boolean isKg = isKilogram(m.getUnit());
                if (m.getType() == WaybillType.PURCHASE) {
                    purAmtAll = purAmtAll.add(amount);
                    if (isKg) { purKgDoc = purKgDoc.add(kg); purAmtKg = purAmtKg.add(amount); }
                } else if (m.getType() == WaybillType.SALE) {
                    saleAmtAll = saleAmtAll.add(amount);
                    if (isKg) { saleKgDoc = saleKgDoc.add(kg); saleAmtKg = saleAmtKg.add(amount); }
                }
            }

            CategoryLedgerInputDto in = inputs.get(category);

            // Part 1 — purchase cash shortage (gap = docTotal − realTotal)
            if (purKgDoc.signum() > 0 || hasPurchaseOverride(in)) {
                BigDecimal docPrice = firstNonNull(in == null ? null : in.getDocPurchasePrice(),
                        avg(purAmtKg, purKgDoc));
                BigDecimal docKg = purKgDoc;
                BigDecimal realKg = firstNonNull(in == null ? null : in.getRealPurchaseKg(), docKg);
                BigDecimal realPrice = firstNonNull(in == null ? null : in.getRealPurchasePrice(), docPrice);
                BigDecimal docTotal = money(docKg.multiply(docPrice));
                BigDecimal realTotal = money(realKg.multiply(realPrice));
                purchaseShortages.add(CategoryCashGapDto.builder()
                        .category(category)
                        .docKg(kg(docKg)).docPrice(price(docPrice)).docTotal(docTotal)
                        .realKg(kg(realKg)).realPrice(price(realPrice)).realTotal(realTotal)
                        .gap(money(docTotal.subtract(realTotal)))
                        .build());
            }

            // Part 2 — sales cash surplus (gap = realTotal − docTotal)
            if (saleKgDoc.signum() > 0 || hasSaleOverride(in)) {
                BigDecimal docPrice = firstNonNull(in == null ? null : in.getDocSalePrice(),
                        avg(saleAmtKg, saleKgDoc));
                BigDecimal docKg = saleKgDoc;
                BigDecimal realKg = firstNonNull(in == null ? null : in.getRealSaleKg(), docKg);
                BigDecimal realPrice = firstNonNull(in == null ? null : in.getRealSalePrice(), docPrice);
                BigDecimal docTotal = money(docKg.multiply(docPrice));
                BigDecimal realTotal = money(realKg.multiply(realPrice));
                saleSurpluses.add(CategoryCashGapDto.builder()
                        .category(category)
                        .docKg(kg(docKg)).docPrice(price(docPrice)).docTotal(docTotal)
                        .realKg(kg(realKg)).realPrice(price(realPrice)).realTotal(realTotal)
                        .gap(money(realTotal.subtract(docTotal)))
                        .build());
            }

            // Part 4 — VAT (actual output − input)
            BigDecimal salesVat = vatFromGross(saleAmtAll);
            BigDecimal purchaseVat = vatFromGross(purAmtAll);
            BigDecimal vatPayable = money(salesVat.subtract(purchaseVat));

            BigDecimal woPct = writeOffPercent.get(category);
            if (woPct == null) {
                woPct = ProductHierarchy.appliesWriteOff(category)
                        ? WriteOffCalculator.DEFAULT_WRITE_OFF_PERCENT : BigDecimal.ZERO;
            }
            BigDecimal projectedVatPayable = null;
            if (ProductHierarchy.appliesWriteOff(category)) {
                BigDecimal salePrice = firstNonNull(in == null ? null : in.getDocSalePrice(),
                        avg(saleAmtKg, saleKgDoc));
                BigDecimal projSoldKg = purKgDoc.multiply(
                        BigDecimal.ONE.subtract(woPct.divide(HUNDRED, PRICE, RoundingMode.HALF_UP)));
                BigDecimal projSalesVat = vatFromGross(projSoldKg.multiply(salePrice));
                projectedVatPayable = money(projSalesVat.subtract(purchaseVat));
            }

            vatList.add(CategoryVatDto.builder()
                    .category(category)
                    .salesGross(money(saleAmtAll)).salesVat(salesVat)
                    .purchaseGross(money(purAmtAll)).purchaseVat(purchaseVat)
                    .vatPayable(vatPayable)
                    .writeOffPercent(woPct.setScale(MONEY, RoundingMode.HALF_UP))
                    .documentedPurchaseKg(kg(purKgDoc)).documentedSoldKg(kg(saleKgDoc))
                    .projectedVatPayable(projectedVatPayable)
                    .build());
        }

        // Part 3 — formal-sales commission AR (all configured formal customers)
        List<FormalCommissionDto> formalCommissions = new ArrayList<>();
        for (Map.Entry<String, FormalSalesCustomerDto> e : formal.entrySet()) {
            String canon = e.getKey();
            FormalSalesCustomerDto dto = e.getValue();
            BigDecimal rate = nz(dto.getCommissionPerKg());
            BigDecimal kg = BigDecimal.ZERO, ar = BigDecimal.ZERO;
            for (ProductMovementDto m : filtered) {
                if (m.getType() != WaybillType.SALE || m.getCounterpartyId() == null) continue;
                if (!canon.equals(TinValidator.canonicalId(m.getCounterpartyId()))) continue;
                ar = ar.add(nz(m.getAmount()));
                if (isKilogram(m.getUnit())) kg = kg.add(nz(m.getQuantityKg()));
            }
            formalCommissions.add(FormalCommissionDto.builder()
                    .customerId(canon)
                    .customerName(dto.getCustomerName())
                    .documentedKg(kg(kg))
                    .documentedAr(money(ar))
                    .commissionPerKg(rate)
                    .commissionAr(money(kg.multiply(rate)))
                    .build());
        }
        formalCommissions.sort(Comparator.comparing(
                f -> f.getCustomerName() != null ? f.getCustomerName() : f.getCustomerId()));

        return DualLedgerDto.builder()
                .startDate(startDate).endDate(endDate).productFilter(productFilter)
                .purchaseShortages(purchaseShortages)
                .saleSurpluses(saleSurpluses)
                .formalCommissions(formalCommissions)
                .vat(vatList)
                .totalPurchaseShortage(sum(purchaseShortages, CategoryCashGapDto::getGap))
                .totalSaleSurplus(sum(saleSurpluses, CategoryCashGapDto::getGap))
                .totalFormalCommission(sum(formalCommissions, FormalCommissionDto::getCommissionAr))
                .totalVatPayable(sum(vatList, CategoryVatDto::getVatPayable))
                .build();
    }

    // ==================== HELPERS (math) ====================

    private static BigDecimal vatFromGross(BigDecimal gross) {
        if (gross == null || gross.signum() == 0) return BigDecimal.ZERO.setScale(MONEY);
        return gross.multiply(VAT_MUL).divide(VAT_DIV, MONEY, RoundingMode.HALF_UP);
    }

    private static BigDecimal avg(BigDecimal amount, BigDecimal kg) {
        if (kg == null || kg.signum() == 0) return BigDecimal.ZERO;
        return amount.divide(kg, PRICE, RoundingMode.HALF_UP);
    }

    private static <T> BigDecimal sum(List<T> items, java.util.function.Function<T, BigDecimal> f) {
        return money(items.stream().map(f).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static boolean hasPurchaseOverride(CategoryLedgerInputDto in) {
        return in != null && (in.getDocPurchasePrice() != null
                || in.getRealPurchasePrice() != null || in.getRealPurchaseKg() != null);
    }

    private static boolean hasSaleOverride(CategoryLedgerInputDto in) {
        return in != null && (in.getDocSalePrice() != null
                || in.getRealSalePrice() != null || in.getRealSaleKg() != null);
    }

    private static BigDecimal firstNonNull(BigDecimal a, BigDecimal b) {
        return a != null ? a : b;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(MONEY, RoundingMode.HALF_UP);
    }

    private static BigDecimal kg(BigDecimal v) {
        return v.setScale(KG, RoundingMode.HALF_UP);
    }

    private static BigDecimal price(BigDecimal v) {
        return v.setScale(MONEY, RoundingMode.HALF_UP);
    }

    /** Unit substrings (lowercased) that mark a line as NOT measured in kilograms. */
    private static final List<String> NON_KG_UNITS = List.of(
            "ცალ", "piece", "pcs", "ლიტრ", "liter", "litre",
            "შეკვრ", "კომპლ", "pack", "set", "წყვილ", "გრამ", "gram");

    private static boolean isKilogram(String unit) {
        if (unit == null || unit.isBlank()) return true;
        String u = unit.trim().toLowerCase();
        if (u.contains("კგ") || u.contains("kg") || u.contains("კილ") || u.contains("kilo")) return true;
        for (String nonKg : NON_KG_UNITS) {
            if (u.contains(nonKg)) return false;
        }
        return true;
    }

    // ==================== HELPERS (I/O) ====================

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
    private Map<String, String> fetchCategoryOverrides() {
        Map<String, String> map = new HashMap<>();
        try {
            String url = configServiceUrl + "/api/config/product-categories";
            Map<String, Object> response = internalRestTemplate.getForObject(url, Map.class);
            if (response != null && response.get("data") instanceof List) {
                for (Map<String, Object> c : (List<Map<String, Object>>) response.get("data")) {
                    Object name = c.get("name");
                    Object category = c.get("category");
                    if (name != null && category != null) {
                        map.put(overrideKey(String.valueOf(name)), String.valueOf(category));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not fetch product category overrides, using auto-classification: {}", e.getMessage());
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private Map<String, CategoryLedgerInputDto> fetchDualLedgerInputs() {
        Map<String, CategoryLedgerInputDto> map = new HashMap<>();
        try {
            String url = configServiceUrl + "/api/config/dual-ledger-inputs";
            Map<String, Object> response = internalRestTemplate.getForObject(url, Map.class);
            if (response != null && response.get("data") instanceof List) {
                for (Map<String, Object> c : (List<Map<String, Object>>) response.get("data")) {
                    Object category = c.get("category");
                    if (category == null) continue;
                    map.put(String.valueOf(category), CategoryLedgerInputDto.builder()
                            .category(String.valueOf(category))
                            .docPurchasePrice(bd(c.get("docPurchasePrice")))
                            .realPurchasePrice(bd(c.get("realPurchasePrice")))
                            .realPurchaseKg(bd(c.get("realPurchaseKg")))
                            .docSalePrice(bd(c.get("docSalePrice")))
                            .realSalePrice(bd(c.get("realSalePrice")))
                            .realSaleKg(bd(c.get("realSaleKg")))
                            .build());
                }
            }
        } catch (Exception e) {
            log.warn("Could not fetch dual-ledger inputs, using documented defaults: {}", e.getMessage());
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private Map<String, FormalSalesCustomerDto> fetchFormalSalesCustomers() {
        Map<String, FormalSalesCustomerDto> map = new LinkedHashMap<>();
        try {
            String url = configServiceUrl + "/api/config/formal-sales-customers";
            Map<String, Object> response = internalRestTemplate.getForObject(url, Map.class);
            if (response != null && response.get("data") instanceof List) {
                for (Map<String, Object> c : (List<Map<String, Object>>) response.get("data")) {
                    Object id = c.get("customerId");
                    if (id == null) continue;
                    String canon = TinValidator.canonicalId(String.valueOf(id));
                    map.put(canon, FormalSalesCustomerDto.builder()
                            .customerId(canon)
                            .customerName(c.get("customerName") != null ? String.valueOf(c.get("customerName")) : null)
                            .commissionPerKg(bd(c.get("commissionPerKg")))
                            .build());
                }
            }
        } catch (Exception e) {
            log.warn("Could not fetch formal-sales customers: {}", e.getMessage());
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private Map<String, BigDecimal> fetchWriteOffRates() {
        Map<String, BigDecimal> map = new HashMap<>();
        try {
            String url = configServiceUrl + "/api/config/write-off-rates";
            Map<String, Object> response = internalRestTemplate.getForObject(url, Map.class);
            if (response != null && response.get("data") instanceof List) {
                for (Map<String, Object> c : (List<Map<String, Object>>) response.get("data")) {
                    Object category = c.get("category");
                    Object percent = c.get("percent");
                    if (category != null && percent != null) {
                        map.put(String.valueOf(category), new BigDecimal(String.valueOf(percent)));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not fetch write-off rates, using default: {}", e.getMessage());
        }
        return map;
    }

    private String resolveCategory(String productName, String autoCategory, Map<String, String> overrides) {
        if (productName == null) return autoCategory;
        return overrides.getOrDefault(overrideKey(productName), autoCategory);
    }

    private String overrideKey(String name) {
        return name == null ? "" : name.trim().toLowerCase();
    }

    private static BigDecimal bd(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        return new BigDecimal(String.valueOf(value));
    }
}
