package ge.tastyerp.payment.service;

import ge.tastyerp.common.dto.payment.ManualCashPaymentDto;
import ge.tastyerp.common.dto.payment.PaymentDto;
import ge.tastyerp.common.exception.ResourceNotFoundException;
import ge.tastyerp.common.exception.ValidationException;
import ge.tastyerp.payment.repository.ManualCashPaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for manual cash payment management.
 *
 * Business Logic:
 * - Manual cash payments are separate from bank statements
 * - Used when customer pays cash directly
 * - Included in customer debt analysis (after cutoff date)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManualCashPaymentService {

    private final ManualCashPaymentRepository repository;

    /**
     * Get all manual cash payments.
     */
    public List<ManualCashPaymentDto> getAllManualPayments() {
        log.debug("Fetching all manual cash payments");
        return repository.findByDateAfter(LocalDate.MIN)
                .stream()
                .map(this::toManualCashPaymentDto)
                .collect(Collectors.toList());
    }

    /**
     * Get manual cash payments for a specific customer.
     */
    public List<ManualCashPaymentDto> getManualPaymentsByCustomer(String customerId) {
        log.debug("Fetching manual cash payments for customer: {}", customerId);
        return repository.findByCustomerIdAndDateAfter(customerId, LocalDate.MIN)
                .stream()
                .map(this::toManualCashPaymentDto)
                .collect(Collectors.toList());
    }

    /**
     * Add a manual cash payment.
     */
    public ManualCashPaymentDto addManualPayment(ManualCashPaymentDto paymentDto) {
        log.info("Adding manual cash payment for customer: {} amount: {}",
                paymentDto.getCustomerId(), paymentDto.getAmount());

        validateManualPayment(paymentDto);

        PaymentDto payment = PaymentDto.builder()
                .customerId(paymentDto.getCustomerId())
                .customerName(paymentDto.getCustomerName())
                .amount(paymentDto.getAmount())
                .paymentDate(paymentDto.getPaymentDate())
                .description(paymentDto.getDescription())
                .source("manual-cash")
                .build();

        PaymentDto saved = repository.save(payment);
        return toManualCashPaymentDto(saved);
    }

    /**
     * Delete a manual cash payment.
     */
    public void deleteManualPayment(String id) {
        log.info("Deleting manual cash payment: {}", id);
        repository.delete(id);
    }

    private void validateManualPayment(ManualCashPaymentDto payment) {
        if (payment.getCustomerId() == null || payment.getCustomerId().isBlank()) {
            throw new ValidationException("customerId", "Customer ID is required");
        }

        if (payment.getAmount() == null || payment.getAmount().signum() <= 0) {
            throw new ValidationException("amount", "Amount must be positive");
        }

        if (payment.getPaymentDate() == null) {
            throw new ValidationException("paymentDate", "Payment date is required");
        }

        if (payment.getPaymentDate().isAfter(LocalDate.now())) {
            throw new ValidationException("paymentDate", "Payment date cannot be in the future");
        }
    }

    private ManualCashPaymentDto toManualCashPaymentDto(PaymentDto payment) {
        return ManualCashPaymentDto.builder()
                .id(payment.getId())
                .customerId(payment.getCustomerId())
                .customerName(payment.getCustomerName())
                .amount(payment.getAmount())
                .paymentDate(payment.getPaymentDate())
                .description(payment.getDescription())
                .createdAt(payment.getUploadedAt() != null ?
                        payment.getUploadedAt().toString() : null)
                .build();
    }
}
