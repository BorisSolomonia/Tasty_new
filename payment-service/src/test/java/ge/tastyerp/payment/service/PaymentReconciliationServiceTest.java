package ge.tastyerp.payment.service;

import ge.tastyerp.common.dto.payment.CustomerPaymentSummary;
import ge.tastyerp.common.dto.payment.PaymentDto;
import ge.tastyerp.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class PaymentReconciliationServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    private PaymentReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new PaymentReconciliationService(paymentRepository);
        // Set the cutoff date to a known value for testing
        ReflectionTestUtils.setField(service, "paymentCutoffDate", "2025-01-01");
    }

    @Test
    void calculateCustomerPayments_ShouldSumCorrectly() {
        // Arrange
        String customerId = "123456789";
        
        PaymentDto payment1 = PaymentDto.builder()
                .customerId(customerId)
                .amount(new BigDecimal("100.00"))
                .source("tbc")
                .paymentDate(LocalDate.of(2025, 1, 15)) // After cutoff
                .build();

        PaymentDto payment2 = PaymentDto.builder()
                .customerId(customerId)
                .amount(new BigDecimal("50.00"))
                .source("manual-cash")
                .paymentDate(LocalDate.of(2025, 2, 1)) // After cutoff
                .build();

        PaymentDto paymentOld = PaymentDto.builder()
                .customerId(customerId)
                .amount(new BigDecimal("1000.00"))
                .source("tbc")
                .paymentDate(LocalDate.of(2024, 12, 31)) // Before cutoff
                .build();

        PaymentDto paymentInvalidSource = PaymentDto.builder()
                .customerId(customerId)
                .amount(new BigDecimal("200.00"))
                .source("unknown")
                .paymentDate(LocalDate.of(2025, 3, 1))
                .build();

        List<PaymentDto> payments = Arrays.asList(payment1, payment2, paymentOld, paymentInvalidSource);

        // Act
        CustomerPaymentSummary summary = service.calculateCustomerPayments(customerId, payments);

        // Assert
        assertEquals(customerId, summary.getCustomerId());
        assertEquals(2, summary.getPaymentCount());
        assertEquals(new BigDecimal("100.00"), summary.getTotalBankPayments());
        assertEquals(new BigDecimal("50.00"), summary.getTotalCashPayments());
        assertEquals(new BigDecimal("150.00"), summary.getTotalPayments());
    }

    @Test
    void calculateCurrentDebt_ShouldCalculateCorrectly() {
        BigDecimal startingDebt = new BigDecimal("500.00");
        BigDecimal totalSales = new BigDecimal("1000.00");
        BigDecimal totalPayments = new BigDecimal("600.00");

        // 500 + 1000 - 600 = 900
        BigDecimal currentDebt = service.calculateCurrentDebt(startingDebt, totalSales, totalPayments);

        assertEquals(new BigDecimal("900.00"), currentDebt);
    }
}
