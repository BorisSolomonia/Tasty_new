package ge.tastyerp.waybill.service;

import ge.tastyerp.common.dto.audit.ProductHierarchy;
import ge.tastyerp.common.dto.audit.ProductMovementDto;
import ge.tastyerp.common.dto.waybill.WaybillDto;
import ge.tastyerp.common.dto.waybill.WaybillGoodDto;
import ge.tastyerp.common.dto.waybill.WaybillType;
import ge.tastyerp.common.util.SimpleTtlCache;
import ge.tastyerp.waybill.service.rsge.RsGeSoapClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Produces per-line product movements (stock in/out) from RS.ge waybills for
 * the Audit Control inventory engine (BOR-74 Phase 2).
 *
 * PURCHASE waybills are treated as stock IN, SALE waybills as stock OUT. Each
 * goods line is classified into a parent product category via
 * {@link ProductHierarchy} so the consumer can aggregate child products into
 * parent nodes.
 *
 * <h3>Performance (BOR-75)</h3>
 * This is the single most expensive call in the audit pipeline: two chunked
 * RS.ge list fetches plus one get_waybill per waybill (network-bound, seconds
 * for a month range). Two optimizations, both parity-safe:
 * <ul>
 *   <li>SALE and PURCHASE list fetches run in parallel (they are independent
 *       RS.ge operations; each is already internally chunk-parallel).</li>
 *   <li>Results are cached per exact date range for a short TTL. RS.ge waybill
 *       history is immutable-in-practice within minutes, and the dashboard +
 *       product-catalog pages request identical ranges back-to-back — the
 *       second call is served from memory. User-editable data (category
 *       overrides etc.) is NOT cached anywhere; it is applied downstream on
 *       every request.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryMovementService {

    private final WaybillService waybillService;
    private final RsGeSoapClient rsGeSoapClient;
    private final WaybillProcessingService waybillProcessingService;

    /** TTL for the per-range movements cache (ms). Default 3 minutes. */
    @Value("${audit.movements-cache-ttl-ms:180000}")
    private long cacheTtlMs;

    private volatile SimpleTtlCache<String, List<ProductMovementDto>> cache;

    private SimpleTtlCache<String, List<ProductMovementDto>> cache() {
        SimpleTtlCache<String, List<ProductMovementDto>> local = cache;
        if (local == null) {
            synchronized (this) {
                if (cache == null) {
                    cache = new SimpleTtlCache<>(cacheTtlMs, 16);
                }
                local = cache;
            }
        }
        return local;
    }

    public List<ProductMovementDto> getProductMovements(String startDate, String endDate) {
        String key = startDate + "|" + endDate;
        return cache().getOrCompute(key, () -> fetchProductMovements(startDate, endDate));
    }

    private List<ProductMovementDto> fetchProductMovements(String startDate, String endDate) {
        log.info("Building product movements for {} to {} (cache miss)", startDate, endDate);
        long t0 = System.currentTimeMillis();

        // SALE and PURCHASE lists are independent RS.ge calls — fetch in parallel.
        CompletableFuture<List<WaybillDto>> salesF = CompletableFuture.supplyAsync(
                () -> waybillService.getWaybills(null, startDate, endDate, false, WaybillType.SALE));
        CompletableFuture<List<WaybillDto>> purchasesF = CompletableFuture.supplyAsync(
                () -> waybillService.getWaybills(null, startDate, endDate, false, WaybillType.PURCHASE));
        List<WaybillDto> sales = salesF.join();
        List<WaybillDto> purchases = purchasesF.join();
        long tLists = System.currentTimeMillis();
        log.info("Fetched {} sale and {} purchase waybills in {} ms",
                sales.size(), purchases.size(), tLists - t0);

        // One goods lookup for both lists (keyed by waybillId).
        List<String> waybillIds = new ArrayList<>();
        waybillIds.addAll(idsOf(sales));
        waybillIds.addAll(idsOf(purchases));
        List<String> distinctIds = waybillIds.stream().distinct().collect(Collectors.toList());

        Map<String, Map<String, Object>> rawGoodsMap = rsGeSoapClient.getWaybillGoodsMap(distinctIds);

        Map<String, List<WaybillGoodDto>> goodsByWaybillId = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : rawGoodsMap.entrySet()) {
            List<WaybillGoodDto> goods = waybillProcessingService.extractGoods(entry.getValue());
            if (!goods.isEmpty()) {
                goodsByWaybillId.put(entry.getKey(), goods);
            }
        }
        long tGoods = System.currentTimeMillis();

        List<ProductMovementDto> movements = new ArrayList<>();
        movements.addAll(toMovements(sales, WaybillType.SALE, goodsByWaybillId));
        movements.addAll(toMovements(purchases, WaybillType.PURCHASE, goodsByWaybillId));

        log.info("Produced {} product movements (lists {} ms, goods {} ms, total {} ms)",
                movements.size(), tLists - t0, tGoods - tLists, System.currentTimeMillis() - t0);
        return movements;
    }

    private List<String> idsOf(List<WaybillDto> waybills) {
        return waybills.stream()
                .map(WaybillDto::getWaybillId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private List<ProductMovementDto> toMovements(
            List<WaybillDto> waybills,
            WaybillType type,
            Map<String, List<WaybillGoodDto>> goodsByWaybillId) {

        List<ProductMovementDto> result = new ArrayList<>();
        for (WaybillDto waybill : waybills) {
            List<WaybillGoodDto> goods = goodsByWaybillId.get(waybill.getWaybillId());
            if (goods == null) continue;

            String counterpartyId = type == WaybillType.PURCHASE
                    ? waybill.getSellerTin()
                    : waybill.getBuyerTin();

            for (WaybillGoodDto good : goods) {
                BigDecimal qty = good.getQuantity();
                if (good.getName() == null || qty == null) continue;

                result.add(ProductMovementDto.builder()
                        .date(waybill.getDate())
                        .type(type)
                        .productName(good.getName())
                        .parentCategory(ProductHierarchy.classify(good.getName()))
                        .quantityKg(qty)
                        .unit(good.getUnit())
                        .amount(good.getTotalPrice() != null ? good.getTotalPrice() : BigDecimal.ZERO)
                        .waybillId(waybill.getWaybillId())
                        .counterpartyId(counterpartyId)
                        .build());
            }
        }
        return result;
    }
}
