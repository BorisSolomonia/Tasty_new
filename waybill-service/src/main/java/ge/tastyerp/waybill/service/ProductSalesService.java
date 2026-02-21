package ge.tastyerp.waybill.service;

import ge.tastyerp.common.dto.waybill.ProductSalesDto;
import ge.tastyerp.common.dto.waybill.WaybillDto;
import ge.tastyerp.common.dto.waybill.WaybillGoodDto;
import ge.tastyerp.common.dto.waybill.WaybillType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Aggregates waybill goods data into beef/pork kg totals per customer.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductSalesService {

    private static final Set<String> BEEF_PRODUCTS = Set.of(
            "საქონლის ხორცი (ძვლიანი)", "საქონლის ხორცი (რბილი)",
            "საქონლის ხორცი (სუკი)", "ხბოს ხორცი"
    );

    private static final Set<String> PORK_PRODUCTS = Set.of(
            "ღორის ხორცი (რბილი)", "ღორის ხორცი",
            "ღორის ხორცი (კისერი)", "ღორის ხორცი (ფერდი)"
    );

    private final WaybillService waybillService;

    public List<ProductSalesDto> getProductSales(String startDate, String endDate) {
        log.info("Fetching product sales for date range: {} to {}", startDate, endDate);

        List<WaybillDto> waybills = waybillService.getWaybills(null, startDate, endDate, false, WaybillType.SALE);
        log.info("Fetched {} waybills for product sales analysis", waybills.size());

        // Warn if no goods data was extracted (helps diagnose RS.ge field name issues)
        long totalGoodsCount = waybills.stream()
                .mapToLong(w -> w.getGoods() == null ? 0 : w.getGoods().size())
                .sum();
        if (totalGoodsCount == 0 && !waybills.isEmpty()) {
            log.warn("No goods data found in any of {} waybills. " +
                    "RS.ge may not return goods in this date range, or field names need updating in GOODS_CONTAINER_KEYS.",
                    waybills.size());
        }

        // Group by customerId
        Map<String, List<WaybillDto>> byCustomer = waybills.stream()
                .filter(w -> w.getCustomerId() != null)
                .collect(Collectors.groupingBy(WaybillDto::getCustomerId));

        List<ProductSalesDto> result = new ArrayList<>();

        for (Map.Entry<String, List<WaybillDto>> entry : byCustomer.entrySet()) {
            String customerId = entry.getKey();
            List<WaybillDto> customerWaybills = entry.getValue();

            String customerName = customerWaybills.stream()
                    .map(WaybillDto::getCustomerName)
                    .filter(n -> n != null && !n.isBlank())
                    .findFirst()
                    .orElse(customerId);

            BigDecimal beefKg = BigDecimal.ZERO;
            BigDecimal porkKg = BigDecimal.ZERO;
            Set<String> beefProductsFound = new LinkedHashSet<>();
            Set<String> porkProductsFound = new LinkedHashSet<>();

            for (WaybillDto waybill : customerWaybills) {
                if (waybill.getGoods() == null) continue;
                for (WaybillGoodDto good : waybill.getGoods()) {
                    String name = good.getName();
                    BigDecimal qty = good.getQuantity();
                    if (name == null || qty == null) continue;

                    if (isBeef(name)) {
                        beefKg = beefKg.add(qty);
                        beefProductsFound.add(name);
                    } else if (isPork(name)) {
                        porkKg = porkKg.add(qty);
                        porkProductsFound.add(name);
                    }
                }
            }

            result.add(ProductSalesDto.builder()
                    .customerId(customerId)
                    .customerName(customerName)
                    .beefKg(beefKg)
                    .porkKg(porkKg)
                    .totalKg(beefKg.add(porkKg))
                    .beefProductsFound(new ArrayList<>(beefProductsFound))
                    .porkProductsFound(new ArrayList<>(porkProductsFound))
                    .build());
        }

        result.sort(Comparator.comparing(ProductSalesDto::getCustomerName));
        log.info("Product sales analysis complete: {} customers", result.size());
        return result;
    }

    private boolean isBeef(String name) {
        if (BEEF_PRODUCTS.contains(name)) return true;
        for (String beefProduct : BEEF_PRODUCTS) {
            if (name.contains(beefProduct)) return true;
        }
        return false;
    }

    private boolean isPork(String name) {
        if (PORK_PRODUCTS.contains(name)) return true;
        for (String porkProduct : PORK_PRODUCTS) {
            if (name.contains(porkProduct)) return true;
        }
        return false;
    }
}
