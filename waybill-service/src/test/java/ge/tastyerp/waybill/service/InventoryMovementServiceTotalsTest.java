package ge.tastyerp.waybill.service;

import ge.tastyerp.common.dto.audit.DocumentTotalsDto;
import ge.tastyerp.common.dto.waybill.WaybillDto;
import ge.tastyerp.common.dto.waybill.WaybillGoodDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BOR-92 v6: the RS.ge document totals the audit statement checks its lines
 * against. Missing goods and lines that do not add up to the waybill total are
 * counted and priced, returns are negated, sellers are counted by tax code.
 */
class InventoryMovementServiceTotalsTest {

    private static WaybillDto wb(String id, String seller, String amount) {
        return WaybillDto.builder().waybillId(id).sellerTin(seller).amount(new BigDecimal(amount)).build();
    }

    private static WaybillGoodDto good(String name, String qty, String total) {
        return WaybillGoodDto.builder().name(name).quantity(new BigDecimal(qty)).totalPrice(new BigDecimal(total)).build();
    }

    @Test
    void totalsCountDocumentsLinesGapsAndReturns() {
        List<WaybillDto> purchases = List.of(
                wb("w1", "S1", "1000"),      // lines add up
                wb("w2", "S2", "500"),       // no goods fetched
                wb("w3", "S1", "300"),       // lines fall short by 20
                wb("w4", "S3", "100"));      // return waybill, negated
        Map<String, List<WaybillGoodDto>> goods = Map.of(
                "w1", List.of(good("beef", "10", "600"), good("pork", "5", "400")),
                "w3", List.of(good("beef", "3", "280")),
                "w4", List.of(good("beef", "1", "100")));
        InventoryMovementService.Documents d = new InventoryMovementService.Documents(List.of(), purchases, goods, Set.of("w4"));

        DocumentTotalsDto t = InventoryMovementService.totals(d, "2026-08-01", "2026-08-31");
        DocumentTotalsDto.Side p = t.getPurchase();

        assertEquals(4, p.getWaybills());
        assertEquals(new BigDecimal("1700.00"), p.getDocumentAmount(), "1000 + 500 + 300 − 100");
        assertEquals(new BigDecimal("1180.00"), p.getLinesAmount(), "1000 + 280 − 100 (w2 has no lines)");
        assertEquals(1, p.getWaybillsWithoutGoods());
        assertEquals(new BigDecimal("500.00"), p.getAmountWithoutGoods());
        assertEquals(1, p.getWaybillsWithMismatch());
        assertEquals(new BigDecimal("20.00"), p.getMismatchAmount());
        assertEquals(3, p.getCounterparties());
        assertEquals(0, t.getSale().getWaybills());
    }
}
