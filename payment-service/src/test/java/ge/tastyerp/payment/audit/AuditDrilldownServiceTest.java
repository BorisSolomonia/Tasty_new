package ge.tastyerp.payment.audit;

import ge.tastyerp.common.dto.auditlayer.AuditCategoryDto;
import ge.tastyerp.common.dto.auditlayer.AuditDrilldownDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingSplitDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingStatus;
import ge.tastyerp.common.dto.auditlayer.AuditSourceRowDto;
import ge.tastyerp.common.dto.auditlayer.AuditSourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BOR-82 finding F-1 regression: the supplier-settlement drill-down must load
 * the category index once per request, not once per bank row. On the real
 * 9,333-row statement the per-row version was ~93,000 Firestore document reads
 * for a single click.
 */
class AuditDrilldownServiceTest {

    private final AuditSourceRowService sourceRows = mock(AuditSourceRowService.class);
    private final AuditMappingService mappings = mock(AuditMappingService.class);
    private final AuditLayerRepository repository = mock(AuditLayerRepository.class);
    private final AuditDrilldownService service = new AuditDrilldownService(sourceRows, mappings, repository);

    private static AuditSourceRowDto bankRow(int i, String categoryCode) {
        AuditMappingDto mapping = AuditMappingDto.builder()
                .sourceType(AuditSourceType.BANK)
                .sourceRowId("row-" + i)
                .status(AuditMappingStatus.MANUALLY_MAPPED)
                .splits(List.of(AuditMappingSplitDto.builder()
                        .categoryCode(categoryCode).amount(new BigDecimal("100")).build()))
                .linkedSourceRows(List.of())
                .build();
        return AuditSourceRowDto.builder()
                .sourceType(AuditSourceType.BANK)
                .sourceRowId("row-" + i)
                .direction("DEBIT")
                .amount(new BigDecimal("100"))
                .status(AuditMappingStatus.MANUALLY_MAPPED)
                .mapping(mapping)
                .build();
    }

    @Test
    @DisplayName("cash.supplierSettlement loads categories once for N rows")
    void categoriesLoadedOncePerRequest() {
        int n = 500;
        List<AuditSourceRowDto> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rows.add(bankRow(i, i % 2 == 0 ? "SUPPLIER_SETTLEMENT" : "SALARY"));
        }
        when(mappings.loadMappingIndex()).thenReturn(Map.of());
        when(sourceRows.loadBankRows(any(), any(), any())).thenReturn(rows);
        when(mappings.categoriesByCode()).thenReturn(Map.of(
                "SUPPLIER_SETTLEMENT", AuditCategoryDto.builder()
                        .code("SUPPLIER_SETTLEMENT").supplierSettlement(true).build(),
                "SALARY", AuditCategoryDto.builder().code("SALARY").build()));

        AuditDrilldownDto out = service.expand("cash.supplierSettlement",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), null);

        assertEquals(n / 2, out.getRowCount(), "only supplier-settlement rows are in the set");
        assertEquals(0, new BigDecimal("25000").compareTo(out.getTotal()));
        verify(mappings, times(1)).categoriesByCode();
    }
}
