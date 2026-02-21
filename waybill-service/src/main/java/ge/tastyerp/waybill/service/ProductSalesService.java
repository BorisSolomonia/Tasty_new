package ge.tastyerp.waybill.service;

import ge.tastyerp.common.dto.waybill.ProductSalesDto;
import ge.tastyerp.common.dto.waybill.WaybillDto;
import ge.tastyerp.common.dto.waybill.WaybillGoodDto;
import ge.tastyerp.common.dto.waybill.WaybillType;
import ge.tastyerp.waybill.service.rsge.RsGeSoapClient;
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
    private final RsGeSoapClient rsGeSoapClient;
    private final WaybillProcessingService waybillProcessingService;

    public List<ProductSalesDto> getProductSales(String startDate, String endDate) {
        log.info("Fetching product sales for date range: {} to {}", startDate, endDate);

        // Step 1: Fetch waybill list (gives us customer IDs and waybill IDs)
        List<WaybillDto> waybills = waybillService.getWaybills(null, startDate, endDate, false, WaybillType.SALE);
        log.info("Fetched {} waybills for product sales analysis", waybills.size());

        // Step 2: Fetch per-waybill goods via get_waybill in parallel
        List<String> waybillIds = waybills.stream()
                .map(WaybillDto::getWaybillId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        Map<String, Map<String, Object>> rawGoodsMap = rsGeSoapClient.getWaybillGoodsMap(waybillIds);

        // Step 3: Extract goods DTOs for each waybill using confirmed RS.ge field names
        Map<String, List<WaybillGoodDto>> goodsByWaybillId = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : rawGoodsMap.entrySet()) {
            List<WaybillGoodDto> goods = waybillProcessingService.extractGoods(entry.getValue());
            if (!goods.isEmpty()) {
                goodsByWaybillId.put(entry.getKey(), goods);
            }
        }

        long totalGoodsItems = goodsByWaybillId.values().stream().mapToLong(List::size).sum();
        log.info("Extracted {} goods items across {} waybills", totalGoodsItems, goodsByWaybillId.size());

        if (goodsByWaybillId.isEmpty() && !waybills.isEmpty()) {
            log.warn("No goods data extracted from any waybill. " +
                    "Check RS.ge field names — confirmed names are W_NAME and QUANTITY_F under GOODS_LIST → GOODS.");
        }

        // Step 4: Group waybills by customer and aggregate beef/pork kg
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
                List<WaybillGoodDto> goods = goodsByWaybillId.get(waybill.getWaybillId());
                if (goods == null) continue;

                for (WaybillGoodDto good : goods) {
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
