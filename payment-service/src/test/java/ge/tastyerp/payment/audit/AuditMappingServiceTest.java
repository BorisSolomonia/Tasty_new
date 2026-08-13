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
}
