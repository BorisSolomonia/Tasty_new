package ge.tastyerp.waybill.service;

import ge.tastyerp.common.dto.audit.ProductHierarchy;
import ge.tastyerp.common.dto.audit.ProductMovementDto;
import ge.tastyerp.common.dto.waybill.WaybillDto;
import ge.tastyerp.common.dto.waybill.WaybillGoodDto;
import ge.tastyerp.common.dto.waybill.WaybillType;
import ge.tastyerp.common.util.SimpleTtlCache;
import ge.tastyerp.waybill.repository.WaybillGoodsRepository;
import ge.tastyerp.waybill.service.rsge.RsGeSoapClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 *   <li>Results are cached per exact date range for a short TTL. User-editable
 *       data (category overrides etc.) is NOT cached anywhere; it is applied
 *       downstream on every request.</li>
 *   <li>Goods lines are <b>persisted</b> (see {@link WaybillGoodsRepository}) and
 *       only ever fetched from RS.ge once per waybill. Before this, a cold cache
 *       meant one get_waybill call per waybill on every request: 147s for eight
 *       months on the deployed VM, and a proxy timeout over three years.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryMovementService {

    private final WaybillService waybillService;
    private final RsGeSoapClient rsGeSoapClient;
    private final WaybillProcessingService waybillProcessingService;
    private final WaybillGoodsRepository waybillGoodsRepository;

    /** TTL for the per-range movements cache (ms). Default 3 minutes. */
    @Value("${audit.movements-cache-ttl-ms:180000}")
    private long cacheTtlMs;

    /** Distinct date ranges kept at once; see the note at the construction site. */
    static final int MAX_CACHED_RANGES = 4;

    private volatile SimpleTtlCache<String, List<ProductMovementDto>> cache;

    /**
     * The SALE/PURCHASE list fetches run on their own two threads, not
     * {@code ForkJoinPool.commonPool()}. On the production container
     * ({@code cpus: "0.5"}) the JVM sees one CPU, so the common pool has
     * parallelism 0 and {@code supplyAsync} without an executor ran both
     * multi-minute RS.ge calls serially on the caller thread — the "parallel"
     * fetch was never parallel in production (BOR-82 finding M-10).
     */
    private final ExecutorService listFetchExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "movement-lists");
        t.setDaemon(true);
        return t;
    });

    private SimpleTtlCache<String, List<ProductMovementDto>> cache() {
        SimpleTtlCache<String, List<ProductMovementDto>> local = cache;
        if (local == null) {
            synchronized (this) {
                if (cache == null) {
                    // 4, not 16: each value is a full multi-year document feed and
                    // the working set is one range (BOR-90 finding M-3).
                    cache = SimpleTtlCache.named("waybill.movements", cacheTtlMs, MAX_CACHED_RANGES);
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
                () -> waybillService.getWaybills(null, startDate, endDate, false, WaybillType.SALE),
                listFetchExecutor);
        CompletableFuture<List<WaybillDto>> purchasesF = CompletableFuture.supplyAsync(
                () -> waybillService.getWaybills(null, startDate, endDate, false, WaybillType.PURCHASE),
                listFetchExecutor);
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

        // Goods lines of a historical waybill never change, so they are read once
        // and kept. Only ids we have never seen go to RS.ge — which is the
        // difference between a 147s cold request and a sub-second one.
        Map<String, WaybillGoodsRepository.StoredGoods> stored =
                waybillGoodsRepository.findByWaybillIds(distinctIds);
        List<String> missingIds = distinctIds.stream()
                .filter(id -> !stored.containsKey(id))
                .collect(Collectors.toList());
        log.info("Goods for {} waybills: {} already stored, {} to fetch from RS.ge",
                distinctIds.size(), stored.size(), missingIds.size());

        Map<String, List<WaybillGoodDto>> goodsByWaybillId = new HashMap<>();
        Set<String> returnWaybillIds = new HashSet<>();
        stored.forEach((waybillId, s) -> {
            if (s.goods() != null && !s.goods().isEmpty()) {
                goodsByWaybillId.put(waybillId, s.goods());
            }
            if (s.returnWaybill()) {
                returnWaybillIds.add(waybillId);
            }
        });

        if (!missingIds.isEmpty()) {
            Map<String, Map<String, Object>> rawGoodsMap = rsGeSoapClient.getWaybillGoodsMap(missingIds);
            Map<String, WaybillGoodsRepository.StoredGoods> toStore = new HashMap<>();
            for (Map.Entry<String, Map<String, Object>> entry : rawGoodsMap.entrySet()) {
                List<WaybillGoodDto> goods = waybillProcessingService.extractGoods(entry.getValue());
                boolean isReturn = isReturnWaybill(entry.getValue());
                if (!goods.isEmpty()) {
                    goodsByWaybillId.put(entry.getKey(), goods);
                }
                if (isReturn) {
                    returnWaybillIds.add(entry.getKey());
                }
                // Stored even when empty, so a waybill with no goods is not
                // re-fetched on every future request. (RsGeSoapClient now returns
                // fetched-but-empty waybills as empty entries and omits only the
                // ones that failed, so this branch is reachable — BOR-81 B-12.)
                toStore.put(entry.getKey(), new WaybillGoodsRepository.StoredGoods(goods, isReturn));
            }
            waybillGoodsRepository.saveAll(toStore);
        }
        long tGoods = System.currentTimeMillis();

        List<ProductMovementDto> movements = new ArrayList<>();
        movements.addAll(toMovements(sales, WaybillType.SALE, goodsByWaybillId, returnWaybillIds));
        movements.addAll(toMovements(purchases, WaybillType.PURCHASE, goodsByWaybillId, returnWaybillIds));

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
            Map<String, List<WaybillGoodDto>> goodsByWaybillId,
            Set<String> returnWaybillIds) {

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
                boolean returned = returnWaybillIds.contains(waybill.getWaybillId());
                BigDecimal signedQuantity = returned ? qty.abs().negate() : qty;
                BigDecimal totalPrice = good.getTotalPrice() != null ? good.getTotalPrice() : BigDecimal.ZERO;
                BigDecimal signedAmount = returned ? totalPrice.abs().negate() : totalPrice;

                result.add(ProductMovementDto.builder()
                        .date(waybill.getDate())
                        .type(type)
                        .productName(good.getName())
                        .parentCategory(ProductHierarchy.classify(good.getName()))
                        .quantityKg(signedQuantity)
                        .unit(good.getUnit())
                        .amount(signedAmount)
                        .waybillId(waybill.getWaybillId())
                        .counterpartyId(counterpartyId)
                        .build());
            }
        }
        return result;
    }

    private boolean isReturnWaybill(Map<String, Object> rawWaybill) {
        if (rawWaybill == null) return false;
        Object type = rawWaybill.get("TYPE");
        if (type == null) type = rawWaybill.get("type");
        return type != null && "5".equals(type.toString().trim());
    }
}
