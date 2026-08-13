package ge.tastyerp.payment.audit;

import ge.tastyerp.common.dto.auditlayer.RealInventoryOverrideDto;
import ge.tastyerp.common.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The manual reality inputs (BOR-89 §12), and one distinction that is easy to
 * lose: <b>withdrawing</b> a confirmation is not the same as confirming zero.
 *
 * <p>"Somebody checked and found nothing" and "nobody has checked" are different
 * audit statements. Only the first should suppress a gap, so a confirmation
 * entered by mistake has to be retractable rather than merely overwritable.</p>
 */
class AuditOverrideServiceTest {

    private AuditLayerRepository repository;
    private AuditMappingService mappingService;
    private AuditOverrideService service;

    private static final String OPERATOR = "boris";
    private static final String ID = "საქონლის ხორცი (რბილი)|2026-08-13";

    @BeforeEach
    void setUp() {
        repository = mock(AuditLayerRepository.class);
        mappingService = mock(AuditMappingService.class);
        service = new AuditOverrideService(repository, mappingService);
    }

    private static RealInventoryOverrideDto existing(String kg) {
        return RealInventoryOverrideDto.builder()
                .id(ID)
                .productName("საქონლის ხორცი (რბილი)")
                .asOfDate(LocalDate.of(2026, 8, 13))
                .realKg(new BigDecimal(kg))
                .build();
    }

    @Test
    void confirmingZeroIsStoredAndLogged() {
        when(repository.findRealInventory()).thenReturn(List.of());

        service.saveRealInventory(RealInventoryOverrideDto.builder()
                .productName("საქონლის ხორცი (რბილი)")
                .asOfDate(LocalDate.of(2026, 8, 13))
                .realKg(BigDecimal.ZERO)
                .build(), OPERATOR);

        verify(repository).saveRealInventory(any());
        verify(mappingService).log(eq(OPERATOR), eq("REAL_INVENTORY"), eq(ID),
                eq("realKg"), any(), eq("0"), any());
    }

    @Test
    void withdrawingAConfirmationDeletesItAndLogsTheOldValue() {
        when(repository.findRealInventory()).thenReturn(List.of(existing("12.5")));

        service.deleteRealInventory(ID, OPERATOR, "entered in error");

        verify(repository).deleteRealInventory(ID);
        verify(mappingService).log(eq(OPERATOR), eq("REAL_INVENTORY"), eq(ID),
                eq("withdrawn"), eq("12.5"), eq(null), eq("entered in error"));
    }

    @Test
    void withdrawingSomethingThatWasNeverConfirmedIsRejected() {
        when(repository.findRealInventory()).thenReturn(List.of());

        assertThrows(ValidationException.class,
                () -> service.deleteRealInventory(ID, OPERATOR, "oops"));
        verify(repository, never()).deleteRealInventory(anyString());
    }

    @Test
    void withdrawingWithoutAnOperatorIsRejected() {
        assertThrows(ValidationException.class, () -> service.deleteRealInventory(ID, "  ", null));
        verify(repository, never()).deleteRealInventory(anyString());
    }

    @Test
    void negativeRealStockIsRejected() {
        ValidationException e = assertThrows(ValidationException.class,
                () -> service.saveRealInventory(RealInventoryOverrideDto.builder()
                        .productName("x").asOfDate(LocalDate.of(2026, 8, 13))
                        .realKg(new BigDecimal("-1")).build(), OPERATOR));

        assertEquals(true, e.getMessage().contains("negative"));
    }

    @Test
    void evidenceCannotDeclareItselfSupportedByMoney() {
        when(repository.findCheckEvidence()).thenReturn(List.of());

        var saved = service.saveCheckEvidence(
                ge.tastyerp.common.dto.auditlayer.CheckEvidenceDto.builder()
                        .counterpartyName("Supplier A")
                        .date(LocalDate.of(2026, 8, 13))
                        .amount(new BigDecimal("500"))
                        .supportedAmount(new BigDecimal("500")) // caller tries to assert support
                        .build(), OPERATOR);

        assertEquals(null, saved.getSupportedAmount(),
                "support is derived from linked bank rows, never accepted from the caller");
    }
}
