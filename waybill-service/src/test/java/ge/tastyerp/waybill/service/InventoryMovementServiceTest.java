package ge.tastyerp.waybill.service;

import ge.tastyerp.common.dto.audit.ProductMovementDto;
import ge.tastyerp.common.dto.waybill.WaybillDto;
import ge.tastyerp.common.dto.waybill.WaybillType;
import ge.tastyerp.waybill.repository.WaybillGoodsRepository;
import ge.tastyerp.waybill.service.rsge.RsGeSoapClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InventoryMovementServiceTest {

    private WaybillService waybillService;
    private RsGeSoapClient rsGeSoapClient;
    private WaybillGoodsRepository goodsRepository;
    private InventoryMovementService service;

    @BeforeEach
    void setUp() {
        waybillService = mock(WaybillService.class);
        rsGeSoapClient = mock(RsGeSoapClient.class);
        goodsRepository = mock(WaybillGoodsRepository.class);
        // Nothing stored, so every waybill still goes to RS.ge — the behaviour
        // these tests assert is unchanged by the persistence layer.
        when(goodsRepository.findByWaybillIds(any())).thenReturn(java.util.Map.of());
        service = new InventoryMovementService(waybillService, rsGeSoapClient,
                new WaybillProcessingService(), goodsRepository);
        ReflectionTestUtils.setField(service, "cacheTtlMs", 180_000L);
        when(waybillService.getWaybills(any(), any(), any(), any(Boolean.class), any(WaybillType.class)))
                .thenReturn(List.of());
    }

    @Test
    void usesLineQuantityInsteadOfRepeatedWaybillTotal() {
        when(waybillService.getWaybills(null, "2026-08-03", "2026-08-03", false, WaybillType.PURCHASE))
                .thenReturn(List.of(purchase("1038100357")));
        when(rsGeSoapClient.getWaybillGoodsMap(List.of("1038100357")))
                .thenReturn(Map.of("1038100357", rawWaybill("3", List.of(
                        rawGood("ღორის ტანხორცი", "484", "553", "6015.35"),
                        rawGood("ღორის ტანხორცი", "69", "553", "754.65")))));

        List<ProductMovementDto> movements = service.getProductMovements("2026-08-03", "2026-08-03");

        assertEquals(List.of(new BigDecimal("484"), new BigDecimal("69")),
                movements.stream().map(movement -> movement.getQuantityKg().stripTrailingZeros()).toList());
    }

    @Test
    void negatesReturnWaybillQuantityAndAmount() {
        when(waybillService.getWaybills(null, "2026-08-03", "2026-08-03", false, WaybillType.PURCHASE))
                .thenReturn(List.of(purchase("1037894450")));
        when(rsGeSoapClient.getWaybillGoodsMap(List.of("1037894450")))
                .thenReturn(Map.of("1037894450", rawWaybill("5", List.of(
                        rawGood("საქონლის ხორცი ნეკნები", "135", "135", "2726.2")))));

        ProductMovementDto movement = service.getProductMovements("2026-08-03", "2026-08-03").get(0);

        assertEquals(0, new BigDecimal("-135").compareTo(movement.getQuantityKg()));
        assertEquals(0, new BigDecimal("-2726.2").compareTo(movement.getAmount()));
    }

    private WaybillDto purchase(String id) {
        return WaybillDto.builder().waybillId(id).date(LocalDate.of(2026, 8, 3))
                .type(WaybillType.PURCHASE).build();
    }

    private Map<String, Object> rawWaybill(String type, List<Map<String, Object>> goods) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("TYPE", type);
        raw.put("GOODS_LIST", Map.of("GOODS", goods));
        return raw;
    }

    private Map<String, Object> rawGood(String name, String quantity, String quantityFull, String amount) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("W_NAME", name);
        raw.put("QUANTITY", quantity);
        raw.put("QUANTITY_F", quantityFull);
        raw.put("UNIT", "კგ");
        raw.put("AMOUNT", amount);
        return raw;
    }
}
