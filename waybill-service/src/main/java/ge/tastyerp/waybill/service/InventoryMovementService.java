package ge.tastyerp.waybill.service;

import ge.tastyerp.common.dto.audit.ProductHierarchy;
import ge.tastyerp.common.dto.audit.DocumentTotalsDto;
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
    private volatile SimpleTtlCache<String, DocumentTotalsDto> totalsCache;

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

    /**
     * RS.ge document totals next to the goods-line totals for the period — the
     * independent figure the audit statement checks its line-based totals
     * against (BOR-92 v6). Same fetch path as the movements, so both are read
     * from the same waybills and the same stored goods.
     */
    public DocumentTotalsDto getDocumentTotals(String startDate, String endDate) {
        String key = startDate + "|" + endDate;
        SimpleTtlCache<String, DocumentTotalsDto> local = totalsCache;
        if (local == null) {
            synchronized (this) {
                if (totalsCache == null) {
                    totalsCache = SimpleTtlCache.named("waybill.documentTotals", cacheTtlMs, 8);
                }
                local = totalsCache;
            }
        }
        return local.getOrCompute(key, () -> totals(loadDocuments(startDate, endDate), startDate, endDate));
    }

    static DocumentTotalsDto totals(Documents d, String startDate, String endDate) {
        return DocumentTotalsDto.builder()
                .startDate(startDate).endDate(endDate)
                .purchase(side(d.purchases(), d.goodsByWaybillId(), d.returnWaybillIds(), true))
                .sale(side(d.sales(), d.goodsByWaybillId(), d.returnWaybillIds(), false))
                .build();
    }

    private static DocumentTotalsDto.Side side(List<WaybillDto> waybills, Map<String, List<WaybillGoodDto>> goodsById,
                                               Set<String> returnIds, boolean purchase) {
        BigDecimal docAmount = BigDecimal.ZERO, lines = BigDecimal.ZERO, noGoodsAmount = BigDecimal.ZERO, mismatch = BigDecimal.ZERO;
        int noGoods = 0, mismatched = 0;
        Set<String> counterparties = new HashSet<>();
        for (WaybillDto w : waybills) {
            boolean returned = returnIds.contains(w.getWaybillId());
            BigDecimal amount = w.getAmount() == null ? BigDecimal.ZERO : w.getAmount().abs();
            if (returned) amount = amount.negate();
            docAmount = docAmount.add(amount);
            String cp = purchase ? w.getSellerTin() : w.getBuyerTin();
            if (cp != null && !cp.isBlank()) counterparties.add(cp.trim());
            List<WaybillGoodDto> goods = goodsById.get(w.getWaybillId());
            if (goods == null || goods.isEmpty()) {
                noGoods++;
                noGoodsAmount = noGoodsAmount.add(amount);
                continue;
            }
            BigDecimal lineSum = BigDecimal.ZERO;
            for (WaybillGoodDto g : goods) {
                if (g.getName() == null || g.getQuantity() == null) continue;   // exactly what toMovements skips
                BigDecimal tp = g.getTotalPrice() == null ? BigDecimal.ZERO : g.getTotalPrice().abs();
                lineSum = lineSum.add(returned ? tp.negate() : tp);
            }
            lines = lines.add(lineSum);
            BigDecimal diff = amount.subtract(lineSum);
            if (diff.abs().compareTo(new BigDecimal("0.011")) > 0) {
                mismatched++;
                mismatch = mismatch.add(diff);
            }
        }
        return DocumentTotalsDto.Side.builder()
                .waybills(waybills.size())
                .documentAmount(docAmount.setScale(2, java.math.RoundingMode.HALF_UP))
                .linesAmount(lines.setScale(2, java.math.RoundingMode.HALF_UP))
                .waybillsWithoutGoods(noGoods)
                .amountWithoutGoods(noGoodsAmount.setScale(2, java.math.RoundingMode.HALF_UP))
                .waybillsWithMismatch(mismatched)
                .mismatchAmount(mismatch.setScale(2, java.math.RoundingMode.HALF_UP))
                .counterparties(counterparties.size())
                .build();
    }

    /** The waybills of a period with their goods and return flags — the input to both movements and totals. */
    record Documents(List<WaybillDto> sales, List<WaybillDto> purchases,
                     Map<String, List<WaybillGoodDto>> goodsByWaybillId, Set<String> returnWaybillIds) {}

    private List<ProductMovementDto> fetchProductMovements(String startDate, String endDate) {
        Documents d = loadDocuments(startDate, endDate);
        List<ProductMovementDto> movements = new ArrayList<>();
        movements.addAll(toMovements(d.sales(), WaybillType.SALE, d.goodsByWaybillId(), d.returnWaybillIds()));
        movements.addAll(toMovements(d.purchases(), WaybillType.PURCHASE, d.goodsByWaybillId(), d.returnWaybillIds()));
        log.info("Produced {} product movements for {} to {}", movements.size(), startDate, endDate);
        return movements;
    }

    private Documents loadDocuments(String startDate, String endDate) {
        log.info("Loading RS.ge documents for {} to {} (cache miss)", startDate, endDate);
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
        log.info("Loaded documents (lists {} ms, goods {} ms, total {} ms)",
                tLists - t0, tGoods - tLists, System.currentTimeMillis() - t0);
        return new Documents(sales, purchases, goodsByWaybillId, returnWaybillIds);
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
            String counterpartyName = type == WaybillType.PURCHASE
                    ? waybill.getSellerName()
                    : waybill.getBuyerName();

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
                        .counterpartyName(counterpartyName)
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
