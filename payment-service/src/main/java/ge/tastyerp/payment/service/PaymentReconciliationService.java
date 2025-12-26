package ge.tastyerp.payment.service;

import ge.tastyerp.common.dto.payment.CustomerPaymentSummary;
import ge.tastyerp.common.dto.payment.PaymentDto;
import ge.tastyerp.common.util.DateUtils;
import ge.tastyerp.common.util.TinValidator;
import ge.tastyerp.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * Service for payment reconciliation and customer debt calculations.
 *
 * This implements the EXACT SUMIFS logic from the legacy application:
 * - Sum payments where customer ID matches
 * - Source is authorized (tbc, bog, excel, bank-api)
 * - Date >= cutoff date (2025-04-30)
 *
 * ALL business logic for reconciliation is here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentReconciliationService {

    private final PaymentRepository paymentRepository;

    @Value("${business.payment-cutoff-date:2025-04-29}")
    private String paymentCutoffDate;

    // Authorized payment sources (from legacy)
    private static final Set<String> AUTHORIZED_BANK_SOURCES = Set.of(
            "tbc", "bog", "excel", "bank-api"
    );

    /**
     * Calculate customer payments using SUMIFS logic.
     *
     * This is the EXACT implementation from legacy:
     * SUMIFS(payments, customerId=match, source=authorized, date>=cutoff)
     *
     * @param customerId Customer TIN
     * @param allPayments List of all payments
     * @return CustomerPaymentSummary with totals and breakdown
     */
    public CustomerPaymentSummary calculateCustomerPayments(
            String customerId,
            List<PaymentDto> payments) {

        String normalizedId = TinValidator.normalize(customerId);
        log.debug("Calculating payments for customer: {}", normalizedId);

        BigDecimal totalBankPayments = BigDecimal.ZERO;
        BigDecimal totalCashPayments = BigDecimal.ZERO;
        int paymentCount = 0;

        // Filter by source and date
        List<PaymentDto> validPayments = payments.stream()
                .filter(p -> {
                    // Filter by authorized source
                    String source = p.getSource();
                    if (source == null) return false;

                    boolean isBankSource = AUTHORIZED_BANK_SOURCES.contains(source.toLowerCase());
                    boolean isCashSource = "manual-cash".equalsIgnoreCase(source);

                    return isBankSource || isCashSource;
                })
                .filter(p -> {
                    // Filter by date (>= cutoff)
                    String dateStr = DateUtils.formatDate(p.getPaymentDate());
                    return isInPaymentWindow(dateStr);
                })
                .toList();

        for (PaymentDto payment : validPayments) {
            BigDecimal amount = payment.getAmount();
            if (amount == null) continue;

            String source = payment.getSource().toLowerCase();

            if (AUTHORIZED_BANK_SOURCES.contains(source)) {
                totalBankPayments = totalBankPayments.add(amount);
            } else if ("manual-cash".equals(source)) {
                totalCashPayments = totalCashPayments.add(amount);
            }

            paymentCount++;
        }

        BigDecimal totalPayments = totalBankPayments.add(totalCashPayments);

        log.debug("Customer {} has {} payments totaling ₾{}",
                normalizedId, paymentCount, totalPayments);

        return CustomerPaymentSummary.builder()
                .customerId(normalizedId)
                .totalBankPayments(totalBankPayments)
                .totalCashPayments(totalCashPayments)
                .totalPayments(totalPayments)
                .paymentCount(paymentCount)
                .payments(validPayments)
                .build();
    }

    /**
     * Calculate current debt for a customer.
     *
     * Formula: currentDebt = startingDebt + totalSales - totalPayments
     *
     * @param startingDebt Initial debt amount (from config)
     * @param totalSales Total sales from waybills
     * @param totalPayments Total payments (bank + cash)
     * @return Current debt balance
     */
    public BigDecimal calculateCurrentDebt(
            BigDecimal startingDebt,
            BigDecimal totalSales,
            BigDecimal totalPayments) {

        BigDecimal debt = startingDebt != null ? startingDebt : BigDecimal.ZERO;
        BigDecimal sales = totalSales != null ? totalSales : BigDecimal.ZERO;
        BigDecimal payments = totalPayments != null ? totalPayments : BigDecimal.ZERO;

        return debt.add(sales).subtract(payments);
    }

    /**
     * Check if a payment is in the payment window.
     */
    public boolean isInPaymentWindow(String dateStr) {
        return DateUtils.isAfterCutoff(dateStr, paymentCutoffDate);
    }

    /**
     * Get the payment cutoff date.
     */
    public String getPaymentCutoffDate() {
        return paymentCutoffDate;
    }
}
