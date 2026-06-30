package ge.tastyerp.waybill.service;

import ge.tastyerp.common.dto.audit.ProductHierarchy;
import ge.tastyerp.common.dto.audit.ProductMovementDto;
import ge.tastyerp.common.dto.waybill.WaybillDto;
import ge.tastyerp.common.dto.waybill.WaybillGoodDto;
import ge.tastyerp.common.dto.waybill.WaybillType;
import ge.tastyerp.waybill.service.rsge.RsGeSoapClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Produces per-line product movements (stock in/out) from RS.ge waybills for
 * the Audit Control inventory engine (BOR-74 Phase 2).
 *
 * PURCHASE waybills are treated as stock IN, SALE waybills as stock OUT. Each
 * goods line is classified into a parent product category via
 * {@link ProductHierarchy} so the consumer can aggregate child products into
 * parent nodes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryMovementService {

    private final WaybillService waybillService;
    private final RsGeSoapClient rsGeSoapClient;
    private final WaybillProcessingService waybillProcessingService;

    public List<ProductMovementDto> getProductMovements(String startDate, String endDate) {
        log.info("Building product movements for {} to {}", startDate, endDate);

        List<WaybillDto> sales = waybillService.getWaybills(null, startDate, endDate, false, WaybillType.SALE);
        List<WaybillDto> purchases = waybillService.getWaybills(null, startDate, endDate, false, WaybillType.PURCHASE);
        log.info("Fetched {} sale and {} purchase waybills for inventory", sales.size(), purchases.size());

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

        List<ProductMovementDto> movements = new ArrayList<>();
        movements.addAll(toMovements(sales, WaybillType.SALE, goodsByWaybillId));
        movements.addAll(toMovements(purchases, WaybillType.PURCHASE, goodsByWaybillId));

        log.info("Produced {} product movements", movements.size());
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
                        .amount(good.getTotalPrice() != null ? good.getTotalPrice() : BigDecimal.ZERO)
                        .waybillId(waybill.getWaybillId())
                        .counterpartyId(counterpartyId)
                        .build());
            }
        }
        return result;
    }
}
