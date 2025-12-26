package ge.tastyerp.payment.service;

import ge.tastyerp.common.dto.payment.CustomerPaymentSummary;
import ge.tastyerp.common.dto.payment.PaymentDto;
import ge.tastyerp.common.exception.ResourceNotFoundException;
import ge.tastyerp.common.exception.ValidationException;
import ge.tastyerp.common.util.AmountUtils;
import ge.tastyerp.common.util.DateUtils;
import ge.tastyerp.common.util.TinValidator;
import ge.tastyerp.common.util.UniqueCodeGenerator;
import ge.tastyerp.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Service for payment management.
 *
 * ALL business logic for payment operations is here.
 * Controllers only delegate to this service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentReconciliationService reconciliationService;

    /**
     * Get all payments with optional filters.
     */
    public List<PaymentDto> getPayments(String customerId, String startDate, String endDate, String source) {
        log.debug("Fetching payments with filters: customerId={}, startDate={}, endDate={}, source={}",
                customerId, startDate, endDate, source);

        String normalizedFilterCustomerId = (customerId == null || customerId.isBlank())
                ? null
                : TinValidator.normalize(customerId);

        LocalDate start = (startDate == null || startDate.isBlank()) ? null : DateUtils.parseDate(startDate);
        LocalDate end = (endDate == null || endDate.isBlank()) ? null : DateUtils.parseDate(endDate);

        String normalizedSource = (source == null || source.isBlank())
                ? null
                : source.trim().toLowerCase(Locale.ROOT);

        boolean canQuery = normalizedFilterCustomerId != null || start != null || end != null;

        List<PaymentDto> payments = new ArrayList<>();
        if (canQuery) {
            // Query by customer/date to avoid full collection scans; apply source filter in-memory for case tolerance.
            payments.addAll(paymentRepository.findBankPayments(normalizedFilterCustomerId, start, end, null));
            payments.addAll(paymentRepository.findManualPayments(normalizedFilterCustomerId, start, end, null));
        } else {
            payments.addAll(paymentRepository.findAll());
            payments.addAll(paymentRepository.findAllManualPayments());
        }

        return payments.stream()
                .filter(p -> normalizedSource == null ||
                        (p.getSource() != null && normalizedSource.equals(p.getSource().toLowerCase(Locale.ROOT))))
                .toList();
    }

    /**
     * Get payment by ID.
     */
    public PaymentDto getPaymentById(String id) {
        return paymentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", id));
    }

    /**
     * Get all payments for a customer.
     */
    public List<PaymentDto> getPaymentsByCustomer(String customerId) {
        String normalizedId = TinValidator.normalize(customerId);
        List<PaymentDto> payments = new ArrayList<>();
        payments.addAll(paymentRepository.findByCustomerId(normalizedId));
        payments.addAll(paymentRepository.findManualPaymentsByCustomerId(normalizedId));
        return payments;
    }

    /**
     * Get payment summary for a customer (SUMIFS logic).
     */
    public CustomerPaymentSummary getCustomerPaymentSummary(String customerId) {
        String normalizedId = TinValidator.normalize(customerId);
        List<PaymentDto> customerPayments = new ArrayList<>();
        customerPayments.addAll(paymentRepository.findByCustomerId(normalizedId));
        customerPayments.addAll(paymentRepository.findManualPaymentsByCustomerId(normalizedId));

        return reconciliationService.calculateCustomerPayments(normalizedId, customerPayments);
    }

    /**
     * Add a manual cash payment.
     */
    public PaymentDto addManualPayment(PaymentDto paymentDto) {
        validateManualPayment(paymentDto);

        String normalizedId = TinValidator.normalize(paymentDto.getCustomerId());
        LocalDate date = paymentDto.getPaymentDate();
        BigDecimal amount = AmountUtils.round(paymentDto.getAmount());

        // Build unique code for manual payments (balance = 0 since no bank statement)
        String uniqueCode = UniqueCodeGenerator.buildUniqueCode(
                date,
                amount,
                normalizedId,
                BigDecimal.ZERO
        );

        PaymentDto payment = PaymentDto.builder()
                .uniqueCode(uniqueCode)
                .customerId(normalizedId)
                .paymentDate(date)
                .amount(amount)
                .balance(BigDecimal.ZERO)
                .description(paymentDto.getDescription() != null ?
                        paymentDto.getDescription() : "Manual Cash Payment")
                .source("manual-cash")
                .isAfterCutoff(reconciliationService.isInPaymentWindow(DateUtils.formatDate(date)))
                .uploadedAt(LocalDateTime.now())
                .build();

        log.info("Adding manual payment for customer: {}, amount: ₾{}", normalizedId, amount);

        return paymentRepository.saveManualPayment(payment);
    }

    /**
     * Update a manual cash payment.
     */
    public PaymentDto updateManualPayment(String id, PaymentDto paymentDto) {
        PaymentDto existing = paymentRepository.findManualPaymentById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Manual payment", id));

        validateManualPayment(paymentDto);

        String normalizedId = TinValidator.normalize(paymentDto.getCustomerId());
        LocalDate date = paymentDto.getPaymentDate();
        BigDecimal amount = AmountUtils.round(paymentDto.getAmount());

        // Rebuild unique code
        String uniqueCode = UniqueCodeGenerator.buildUniqueCode(
                date,
                amount,
                normalizedId,
                BigDecimal.ZERO
        );

        PaymentDto updated = PaymentDto.builder()
                .id(id)
                .uniqueCode(uniqueCode)
                .customerId(normalizedId)
                .paymentDate(date)
                .amount(amount)
                .balance(BigDecimal.ZERO)
                .description(paymentDto.getDescription())
                .source("manual-cash")
                .isAfterCutoff(reconciliationService.isInPaymentWindow(DateUtils.formatDate(date)))
                .uploadedAt(existing.getUploadedAt())
                .build();

        log.info("Updating manual payment {} for customer: {}", id, normalizedId);

        return paymentRepository.updateManualPayment(updated);
    }

    /**
     * Delete a payment.
     */
    public void deletePayment(String id) {
        // First try manual payments collection
        if (paymentRepository.findManualPaymentById(id).isPresent()) {
            log.info("Deleting manual payment: {}", id);
            paymentRepository.deleteManualPayment(id);
            return;
        }

        // Then try bank payments collection
        if (paymentRepository.findById(id).isPresent()) {
            log.info("Deleting bank payment: {}", id);
            paymentRepository.delete(id);
            return;
        }

        throw new ResourceNotFoundException("Payment", id);
    }

    public int purgeBankPayments(List<String> sources) {
        return paymentRepository.deleteBankPaymentsBySources(sources);
    }

    /**
     * Get payment statistics.
     */
    public Object getPaymentStatistics() {
        List<PaymentDto> allPayments = paymentRepository.findAll();
        List<PaymentDto> manualPayments = paymentRepository.findAllManualPayments();

        BigDecimal totalBankPayments = allPayments.stream()
                .map(PaymentDto::getAmount)
                .filter(a -> a != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalManualPayments = manualPayments.stream()
                .map(PaymentDto::getAmount)
                .filter(a -> a != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Group by source
        Map<String, BigDecimal> bySource = new HashMap<>();
        for (PaymentDto p : allPayments) {
            String source = p.getSource() != null ? p.getSource() : "unknown";
            bySource.merge(source, p.getAmount() != null ? p.getAmount() : BigDecimal.ZERO, BigDecimal::add);
        }

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalBankPayments", totalBankPayments);
        stats.put("totalManualPayments", totalManualPayments);
        stats.put("totalPayments", totalBankPayments.add(totalManualPayments));
        stats.put("bankPaymentCount", allPayments.size());
        stats.put("manualPaymentCount", manualPayments.size());
        stats.put("bySource", bySource);
        stats.put("cutoffDate", reconciliationService.getPaymentCutoffDate());

        return stats;
    }

    // Private helper methods

    private void validateManualPayment(PaymentDto payment) {
        if (payment.getCustomerId() == null || payment.getCustomerId().isBlank()) {
            throw new ValidationException("customerId", "Customer ID is required");
        }

        if (!TinValidator.isValid(payment.getCustomerId())) {
            throw new ValidationException("customerId", "Invalid TIN format");
        }

        if (payment.getAmount() == null || !AmountUtils.isPositive(payment.getAmount())) {
            throw new ValidationException("amount", "Amount must be greater than zero");
        }

        if (payment.getPaymentDate() == null) {
            throw new ValidationException("paymentDate", "Payment date is required");
        }
    }
}
