package ge.tastyerp.payment.audit;

import ge.tastyerp.common.dto.auditlayer.AuditMappingDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingSplitDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingStatus;
import ge.tastyerp.common.dto.auditlayer.AuditSourceType;
import ge.tastyerp.common.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rules the mapping engine must not break (BOR-89 §6, §13).
 *
 * <p>The load-bearing one is over-allocation: if splits could exceed the source
 * amount, the audit could manufacture money, which is precisely the behaviour
 * the module exists to detect.</p>
 */
class AuditMappingServiceTest {

    private AuditLayerRepository repository;
    private AuditMappingService service;

    private static final String OPERATOR = "boris";

    @BeforeEach
    void setUp() {
        repository = mock(AuditLayerRepository.class);
        when(repository.findCustomCategories()).thenReturn(List.of());
        when(repository.findMapping(any(), anyString())).thenReturn(null);
        service = new AuditMappingService(repository);
    }

    private static AuditMappingSplitDto split(String category, String amount) {
        return AuditMappingSplitDto.builder()
                .categoryCode(category)
                .amount(new BigDecimal(amount))
                .build();
    }

    private static AuditMappingDto mapping(String sourceAmount, AuditMappingSplitDto... splits) {
        return AuditMappingDto.builder()
                .sourceType(AuditSourceType.BANK)
                .sourceRowId("row-1")
                .sourceAmount(new BigDecimal(sourceAmount))
                .splits(List.of(splits))
                .build();
    }

    @Test
    void splitsExceedingTheSourceAmountAreRejected() {
        AuditMappingDto over = mapping("12850",
                split(AuditCategories.SUPPLIER_CASH_PAYMENT, "8000"),
                split(AuditCategories.NON_SUPPLIER_EXPENSE, "6000"));

        ValidationException error = assertThrows(ValidationException.class,
                () -> service.saveMapping(over, OPERATOR));

        assertTrue(error.getMessage().contains("exceeds"),
                "the operator must be told why the mapping was refused: " + error.getMessage());
    }

    @Test
    void splitsCoveringTheSourceExactlyAreAccepted() {
        AuditMappingDto exact = mapping("10000",
                split(AuditCategories.SUPPLIER_CASH_PAYMENT, "8000"),
                split(AuditCategories.NON_SUPPLIER_EXPENSE, "2000"));

        AuditMappingDto saved = service.saveMapping(exact, OPERATOR);

        assertEquals(AuditMappingStatus.MANUALLY_MAPPED, saved.getStatus());
        assertEquals(0, saved.getUnresolvedAmount().compareTo(BigDecimal.ZERO),
                "a fully allocated row has nothing left unresolved");
    }

    @Test
    void partialAllocationLeavesAVisibleRemainder() {
        AuditMappingDto partial = mapping("12850",
                split(AuditCategories.SUPPLIER_CASH_PAYMENT, "8000"),
                split(AuditCategories.NON_SUPPLIER_EXPENSE, "1350"),
                split(AuditCategories.CASH_REDEPOSIT, "1000"));

        AuditMappingDto saved = service.saveMapping(partial, OPERATOR);

        assertEquals(AuditMappingStatus.PARTIALLY_MAPPED, saved.getStatus());
        assertEquals(0, saved.getUnresolvedAmount().compareTo(new BigDecimal("2500.00")),
                "12,850 − 10,350 = 2,500 must stay visible rather than be absorbed");
    }

    @Test
    void unknownCategoriesAreRejected() {
        AuditMappingDto bogus = mapping("100", split("NOT_A_CATEGORY", "100"));

        assertThrows(ValidationException.class, () -> service.saveMapping(bogus, OPERATOR));
    }

    @Test
    void zeroOrNegativeSplitAmountsAreRejected() {
        AuditMappingDto zero = mapping("100", split(AuditCategories.OTHER_INCOME, "0"));

        assertThrows(ValidationException.class, () -> service.saveMapping(zero, OPERATOR));
    }

    @Test
    void savingWithoutAnOperatorIsRejectedSoTheLogAlwaysNamesSomeone() {
        AuditMappingDto valid = mapping("100", split(AuditCategories.OTHER_INCOME, "100"));

        assertThrows(ValidationException.class, () -> service.saveMapping(valid, "  "));
    }

    @Test
    void everySavedMappingAppendsAChangeLogEntry() {
        service.saveMapping(mapping("100", split(AuditCategories.OTHER_INCOME, "100")), OPERATOR);

        verify(repository).appendChangeLog(any());
    }

    @Test
    void aSuggestionContributesNothingUntilSomeoneAcceptsIt() {
        AuditMappingDto suggested = AuditMappingDto.builder()
                .sourceType(AuditSourceType.BANK)
                .sourceRowId("row-1")
                .sourceAmount(new BigDecimal("500"))
                .status(AuditMappingStatus.SUGGESTED)
                .splits(List.of(split(AuditCategories.CUSTOMER_RECEIPT, "500")))
                .build();

        AuditMappingDto saved = service.saveMapping(suggested, OPERATOR);

        assertEquals(AuditMappingStatus.SUGGESTED, saved.getStatus());
        assertTrue(AuditMappingService.effectiveSplits(saved).isEmpty(),
                "an unaccepted suggestion must not feed any total");
        assertEquals(0, saved.getUnresolvedAmount().compareTo(new BigDecimal("500.00")),
                "the full amount is still unresolved while the suggestion is unaccepted");
    }

    @Test
    void voidingLogsWhatTheMappingUsedToSayNotWhatItBecame() {
        AuditMappingDto live = AuditMappingDto.builder()
                .sourceType(AuditSourceType.BANK)
                .sourceRowId("row-1")
                .sourceAmount(new BigDecimal("500"))
                .status(AuditMappingStatus.MANUALLY_MAPPED)
                .splits(List.of(split(AuditCategories.CUSTOMER_RECEIPT, "500")))
                .build();
        when(repository.findMapping(AuditSourceType.BANK, "row-1")).thenReturn(live);

        service.voidMapping("BANK__row-1", OPERATOR, "entered against the wrong row");

        org.mockito.ArgumentCaptor<ge.tastyerp.common.dto.auditlayer.AuditChangeLogDto> captor =
                org.mockito.ArgumentCaptor.forClass(
                        ge.tastyerp.common.dto.auditlayer.AuditChangeLogDto.class);
        verify(repository).appendChangeLog(captor.capture());

        assertTrue(captor.getValue().getOldValue().contains("CUSTOMER_RECEIPT=500"),
                "a void that forgets what it voided is useless as history, got: "
                        + captor.getValue().getOldValue());
        assertEquals("VOIDED", captor.getValue().getNewValue());
    }

    @Test
    void builtInCategoriesCannotBeDeleted() {
        assertThrows(ValidationException.class,
                () -> service.deleteCategory(AuditCategories.CUSTOMER_RECEIPT, OPERATOR));
    }

    // ==================== BOR-92 v4: bulk map ====================

    private static ge.tastyerp.common.dto.auditlayer.AuditSourceRowDto bankRow(String id, String amount, AuditMappingDto mapping) {
        return ge.tastyerp.common.dto.auditlayer.AuditSourceRowDto.builder()
                .sourceType(AuditSourceType.BANK).sourceRowId(id).direction("DEBIT")
                .amount(new BigDecimal(amount)).mapping(mapping).build();
    }

    @Test
    void bulkMapFillsOnlyTheUnmappedRemainderAndSkipsFullyMappedRows() {
        when(repository.findCustomSubgroups()).thenReturn(List.of());
        AuditMappingDto partly = AuditMappingDto.builder().sourceType(AuditSourceType.BANK).sourceRowId("r2")
                .status(AuditMappingStatus.MANUALLY_MAPPED).createdBy("earlier")
                .splits(List.of(split(AuditCategories.NON_SUPPLIER_EXPENSE, "40"))).build();
        AuditMappingDto full = AuditMappingDto.builder().sourceType(AuditSourceType.BANK).sourceRowId("r3")
                .status(AuditMappingStatus.MANUALLY_MAPPED)
                .splits(List.of(split(AuditCategories.NON_SUPPLIER_EXPENSE, "100"))).build();
        List<ge.tastyerp.common.dto.auditlayer.AuditSourceRowDto> rows = List.of(
                bankRow("r1", "100", null), bankRow("r2", "100", partly), bankRow("r3", "100", full));
        when(repository.saveMappingsBatch(any(), any())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

        ge.tastyerp.common.dto.auditlayer.AuditBulkMapRequestDto req = ge.tastyerp.common.dto.auditlayer.AuditBulkMapRequestDto.builder()
                .categoryCode(AuditCategories.UNDOCUMENTED_WITHDRAWAL).replace(false).note("ATM lines").build();
        ge.tastyerp.common.dto.auditlayer.AuditBulkMapRequestDto.Result result = service.bulkMap(rows, req, OPERATOR);

        assertEquals(2, result.getMapped());
        assertEquals(1, result.getSkipped(), "the fully mapped row is left alone");
        assertEquals(new BigDecimal("160.00"), result.getAmount(), "100 (whole r1) + 60 (r2's remainder)");

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<AuditMappingDto>> written = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(repository).saveMappingsBatch(written.capture(), any());
        AuditMappingDto r2 = written.getValue().stream().filter(m -> m.getSourceRowId().equals("r2")).findFirst().orElseThrow();
        assertEquals(2, r2.getSplits().size(), "existing split kept, remainder added");
        assertEquals("earlier", r2.getCreatedBy(), "the first author is kept; the operator is the updater");
        assertEquals(OPERATOR, r2.getUpdatedBy());
        assertEquals(AuditMappingStatus.MANUALLY_MAPPED, r2.getStatus());
    }

    @Test
    void bulkMapReplaceDropsExistingSplitsAndCoversTheWholeRow() {
        when(repository.findCustomSubgroups()).thenReturn(List.of());
        AuditMappingDto full = AuditMappingDto.builder().sourceType(AuditSourceType.BANK).sourceRowId("r3")
                .status(AuditMappingStatus.MANUALLY_MAPPED)
                .splits(List.of(split(AuditCategories.NON_SUPPLIER_EXPENSE, "100"))).build();
        when(repository.saveMappingsBatch(any(), any())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());
        ge.tastyerp.common.dto.auditlayer.AuditBulkMapRequestDto req = ge.tastyerp.common.dto.auditlayer.AuditBulkMapRequestDto.builder()
                .categoryCode(AuditCategories.SUPPLIER_CASH_PAYMENT).subgroupCode(AuditSubgroups.CHECK_NEEDED)
                .counterpartyTin("200000002").counterpartyName("Supplier B").replace(true).build();

        ge.tastyerp.common.dto.auditlayer.AuditBulkMapRequestDto.Result result =
                service.bulkMap(List.of(bankRow("r3", "100", full)), req, OPERATOR);

        assertEquals(1, result.getMapped());
        assertEquals(new BigDecimal("100.00"), result.getAmount());
    }

    @Test
    void bulkMapRefusesUnknownGroupOrStatusAndBlankOperator() {
        when(repository.findCustomSubgroups()).thenReturn(List.of());
        List<ge.tastyerp.common.dto.auditlayer.AuditSourceRowDto> rows = List.of(bankRow("r1", "100", null));
        assertThrows(ValidationException.class, () -> service.bulkMap(rows,
                ge.tastyerp.common.dto.auditlayer.AuditBulkMapRequestDto.builder().categoryCode("NOPE").build(), OPERATOR));
        assertThrows(ValidationException.class, () -> service.bulkMap(rows,
                ge.tastyerp.common.dto.auditlayer.AuditBulkMapRequestDto.builder().categoryCode(AuditCategories.NON_SUPPLIER_EXPENSE).subgroupCode("NOPE").build(), OPERATOR));
        assertThrows(ValidationException.class, () -> service.bulkMap(rows,
                ge.tastyerp.common.dto.auditlayer.AuditBulkMapRequestDto.builder().categoryCode(AuditCategories.NON_SUPPLIER_EXPENSE).build(), " "));
    }

    // ==================== BOR-92: level-2 subgroups ====================

    @Test
    void splitWithUnknownSubgroupIsRejected() {
        when(repository.findCustomSubgroups()).thenReturn(List.of());
        AuditMappingSplitDto s = split(AuditCategories.SUPPLIER_CASH_PAYMENT, "100");
        s.setSubgroupCode("NOT_A_STATUS");

        ValidationException error = assertThrows(ValidationException.class,
                () -> service.saveMapping(mapping("100", s), OPERATOR));
        assertTrue(error.getMessage().contains("NOT_A_STATUS"), error.getMessage());
    }

    @Test
    void builtInAndCustomSubgroupsAreAcceptedOnSplits() {
        when(repository.findCustomSubgroups()).thenReturn(List.of(
                ge.tastyerp.common.dto.auditlayer.AuditSubgroupDto.builder().code("INVOICE_NEEDED").label("Invoice needed").build()));
        AuditMappingSplitDto a = split(AuditCategories.SUPPLIER_CASH_PAYMENT, "60");
        a.setSubgroupCode(AuditSubgroups.CHECK_NEEDED);
        AuditMappingSplitDto b = split(AuditCategories.SUPPLIER_CASH_PAYMENT, "40");
        b.setSubgroupCode("INVOICE_NEEDED");

        AuditMappingDto saved = service.saveMapping(mapping("100", a, b), OPERATOR);
        assertEquals(AuditSubgroups.CHECK_NEEDED, saved.getSplits().get(0).getSubgroupCode());
        assertEquals("INVOICE_NEEDED", saved.getSplits().get(1).getSubgroupCode());
    }

    @Test
    void subgroupStillUsedByAMappingCannotBeDeleted() {
        AuditMappingSplitDto s = split(AuditCategories.SUPPLIER_CASH_PAYMENT, "100");
        s.setSubgroupCode("INVOICE_NEEDED");
        when(repository.findAllMappings()).thenReturn(List.of(mapping("100", s)));

        ValidationException error = assertThrows(ValidationException.class,
                () -> service.deleteSubgroup("INVOICE_NEEDED", OPERATOR));
        assertTrue(error.getMessage().contains("1 mapping"), error.getMessage());
        verify(repository, never()).deleteSubgroup(anyString());
    }

    @Test
    void unusedCustomSubgroupIsDeleted_builtInNever() {
        when(repository.findAllMappings()).thenReturn(List.of());
        service.deleteSubgroup("INVOICE_NEEDED", OPERATOR);
        verify(repository).deleteSubgroup("INVOICE_NEEDED");

        assertThrows(ValidationException.class,
                () -> service.deleteSubgroup(AuditSubgroups.CHECK_NEEDED, OPERATOR));
    }
}
