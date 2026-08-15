package ge.tastyerp.payment.service;

import ge.tastyerp.common.dto.payment.PaymentDto;
import ge.tastyerp.common.dto.payment.PaymentStatusDto;
import ge.tastyerp.payment.repository.PaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BOR-82 finding F-4: the status badge must not read every payment ever, on every page view. */
class PaymentStatusServiceTest {

    private final PaymentRepository repo = mock(PaymentRepository.class);
    private final PaymentStatusService service = new PaymentStatusService(repo);

    PaymentStatusServiceTest() {
        ReflectionTestUtils.setField(service, "paymentCutoffDate", "2025-04-29");
        when(repo.findByDateAfter(any())).thenReturn(List.of(
                PaymentDto.builder().customerId("1").paymentDate(LocalDate.now().minusDays(3)).amount(BigDecimal.TEN).source("tbc").build()));
        when(repo.findManualPayments(isNull(), any(), isNull(), isNull())).thenReturn(List.of(
                PaymentDto.builder().customerId("2").paymentDate(LocalDate.now().minusDays(40)).amount(BigDecimal.TEN).source("manual-cash").build()));
    }

    @Test
    @DisplayName("Reads only the after-cutoff window of both collections — never findAll")
    void readsTheWindowNotEverything() {
        Map<String, PaymentStatusDto> status = service.calculatePaymentStatus();
        assertTrue(status.containsKey("1") && status.containsKey("2"));
        verify(repo, never()).findAll();
        verify(repo, never()).findAllManualPayments();
        verify(repo, times(1)).findByDateAfter(LocalDate.parse("2025-04-29"));
    }

    @Test
    @DisplayName("Second call within the TTL is served from cache; invalidate() forces a recompute")
    void cachesAndInvalidates() {
        service.calculatePaymentStatus();
        service.calculatePaymentStatus();
        verify(repo, times(1)).findByDateAfter(any());
        service.invalidate();
        service.calculatePaymentStatus();
        verify(repo, times(2)).findByDateAfter(any());
        assertEquals(2, service.calculatePaymentStatus().size());
    }
}
