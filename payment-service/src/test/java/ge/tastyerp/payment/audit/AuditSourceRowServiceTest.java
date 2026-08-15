package ge.tastyerp.payment.audit;

import ge.tastyerp.common.dto.audit.ProductMovementDto;
import ge.tastyerp.common.dto.auditlayer.AuditSourceRowDto;
import ge.tastyerp.common.dto.waybill.WaybillType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;

/**
 * Document-line identity (BOR-89 §6).
 *
 * <p>RS.ge line items have no id of their own, so the audit layer composes one.
 * A collision is not cosmetic: two distinct lines sharing an id would share a
 * mapping, so classifying one would silently classify the other and the totals
 * would attribute value to the wrong row. Found in the browser as a duplicate
 * React key on a real waybill that lists the same product twice.</p>
 */
class AuditSourceRowServiceTest {

    private final org.springframework.web.client.RestTemplate restTemplate =
            mock(org.springframework.web.client.RestTemplate.class);

    private final AuditSourceRowService service = new AuditSourceRowService(
            mock(com.google.cloud.firestore.Firestore.class),
            mock(AuditMappingService.class),
            mock(AuditLayerRepository.class),
            restTemplate);

    @Test
    void movementFeedCopyKeepsEveryFieldTheWaybillServiceSent() {
        // BOR-92 regression: the feed is copied field by field; a field added
        // to ProductMovementDto but not to this copy silently vanishes (the
        // supplier picker showed bare TINs in production for that reason).
        java.util.Map<String, Object> line = new java.util.LinkedHashMap<>();
        line.put("date", "2026-08-03");
        line.put("type", "PURCHASE");
        line.put("productName", "საქონლის ხორცი");
        line.put("parentCategory", "BEEF");
        line.put("quantityKg", 12.5);
        line.put("unit", "კგ");
        line.put("amount", 300.0);
        line.put("waybillId", "w-1");
        line.put("counterpartyId", "404737344");
        line.put("counterpartyName", "შპს ერთგული ვაჟა პაპა");
        org.mockito.Mockito.when(restTemplate.getForObject(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.eq(java.util.Map.class)))
                .thenReturn(java.util.Map.of("data", List.of(line)));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "waybillServiceUrl", "http://waybill");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "movementsCacheTtlMs", 1000L);

        List<ProductMovementDto> movements = service.loadProductMovements(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        assertEquals(1, movements.size());
        ProductMovementDto m = movements.get(0);
        ProductMovementDto expected = ProductMovementDto.builder()
                .date(LocalDate.of(2026, 8, 3)).type(WaybillType.PURCHASE)
                .productName("საქონლის ხორცი").parentCategory("BEEF")
                .quantityKg(new BigDecimal("12.5")).unit("კგ").amount(new BigDecimal("300.0"))
                .waybillId("w-1").counterpartyId("404737344").counterpartyName("შპს ერთგული ვაჟა პაპა")
                .build();
        assertEquals(expected, m, "every field of the feed must survive the copy");
    }

    private static ProductMovementDto line(String waybillId, String product,
                                           String qty, String amount) {
        return ProductMovementDto.builder()
                .waybillId(waybillId)
                .type(WaybillType.PURCHASE)
                .productName(product)
                .date(LocalDate.of(2026, 8, 3))
                .quantityKg(new BigDecimal(qty))
                .amount(new BigDecimal(amount))
                .unit("კგ")
                .build();
    }

    @Test
    void sameProductTwiceOnOneWaybillGetsDistinctIds() {
        // A real case: one waybill listing the same cut at two weights/prices.
        List<ProductMovementDto> movements = List.of(
                line("1038100357", "ღორის ტანხორცი", "53.0", "1272.00"),
                line("1038100357", "ღორის ტანხორცი", "19.7", "591.00"));

        List<AuditSourceRowDto> rows = service.toDocumentRows(movements, null);

        assertEquals(2, rows.size());
        assertNotEquals(rows.get(0).getSourceRowId(), rows.get(1).getSourceRowId(),
                "two different lines must never share a mapping id");
    }

    @Test
    void trulyIdenticalLinesStillGetDistinctIds() {
        List<ProductMovementDto> movements = List.of(
                line("1038100357", "ღორის ტანხორცი", "53.0", "1272.00"),
                line("1038100357", "ღორის ტანხორცი", "53.0", "1272.00"));

        Set<String> ids = new HashSet<>();
        for (AuditSourceRowDto row : service.toDocumentRows(movements, null)) {
            ids.add(row.getSourceRowId());
        }

        assertEquals(2, ids.size(),
                "a repeated identical line is still two rows and needs two ids");
    }

    @Test
    void idsAreStableAcrossReSyncSoMappingsSurvive() {
        List<ProductMovementDto> movements = List.of(
                line("1038100357", "ღორის ტანხორცი", "53.0", "1272.00"),
                line("1038100357", "ღორის ტანხორცი", "19.7", "591.00"));

        List<String> first = service.toDocumentRows(movements, null).stream()
                .map(AuditSourceRowDto::getSourceRowId).toList();
        List<String> second = service.toDocumentRows(movements, null).stream()
                .map(AuditSourceRowDto::getSourceRowId).toList();

        assertEquals(first, second,
                "re-fetching the same waybill must reproduce the same ids, or every "
                        + "existing mapping would be orphaned");
    }

    @Test
    void differingScaleDoesNotChangeTheId() {
        // 53.0 and 53.00 are the same weight and must not produce different ids.
        String a = service.toDocumentRows(
                List.of(line("W1", "P", "53.0", "1272.0")), null).get(0).getSourceRowId();
        String b = service.toDocumentRows(
                List.of(line("W1", "P", "53.00", "1272.00")), null).get(0).getSourceRowId();

        assertEquals(a, b);
    }
}
