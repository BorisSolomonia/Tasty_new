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

    private final AuditSourceRowService service = new AuditSourceRowService(
            mock(com.google.cloud.firestore.Firestore.class),
            mock(AuditMappingService.class),
            mock(org.springframework.web.client.RestTemplate.class));

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
