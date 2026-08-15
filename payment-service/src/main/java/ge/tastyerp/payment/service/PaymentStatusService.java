package ge.tastyerp.payment.service;

import ge.tastyerp.common.dto.payment.PaymentDto;
import ge.tastyerp.common.dto.payment.PaymentStatusDto;
import ge.tastyerp.common.util.SimpleTtlCache;
import ge.tastyerp.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Service for calculating payment status indicators.
 *
 * Determines visual warning colors based on days since last payment:
 * - < 14 days: "none" (no color)
 * - 14-30 days: "yellow" (warning)
 * - 30+ days: "red" (danger)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentStatusService {

    private final PaymentRepository paymentRepository;

    @Value("${business.payment-cutoff-date:2025-04-29}")
    private String paymentCutoffDate;

    private static final int WARNING_THRESHOLD_DAYS = 14;
    private static final int DANGER_THRESHOLD_DAYS = 30;

    /**
     * The status map is a "days since last payment" badge per customer. It used
     * to be recomputed from a full read of BOTH payment collections (every payment
     * ever, ~20k documents) on every /payments page mount — ~3 s and 40 % of the
     * daily free-tier read quota per view (BOR-82 finding F-4). It now reads only
     * payments after the cutoff (the only ones it uses) and keeps the result for
     * 60 s; the Excel/manual/DBI writers call {@link #invalidate()} so a fresh
     * upload is reflected at once.
     */
    private final SimpleTtlCache<String, Map<String, PaymentStatusDto>> cache =
            SimpleTtlCache.named("payment.status", TimeUnit.SECONDS.toMillis(60), 1);

    /** Drop the cached status map (call after any payment write). */
    public void invalidate() {
        cache.invalidateAll();
    }

    /**
     * Calculate payment status for all customers.
     * Returns a map of customerId -> PaymentStatusDto.
     */
    public Map<String, PaymentStatusDto> calculatePaymentStatus() {
        return cache.getOrCompute("all", this::computePaymentStatus);
    }

    private Map<String, PaymentStatusDto> computePaymentStatus() {
        log.info("Calculating payment status for all customers");

        // Fetch payments after cutoff (bank + manual cash) — the only window this
        // badge uses. Manual cash lives in a separate collection; a cash-only
        // customer must not be flagged red just because they have no bank payments.
        LocalDate cutoffDate = LocalDate.parse(paymentCutoffDate);
        List<PaymentDto> allPayments = new ArrayList<>(paymentRepository.findByDateAfter(cutoffDate));
        allPayments.addAll(paymentRepository.findManualPayments(null, cutoffDate, null, null));

        // Filter payments after cutoff
        List<PaymentDto> relevantPayments = allPayments.stream()
                .filter(p -> p.getPaymentDate() != null)
                .filter(p -> p.getPaymentDate().isAfter(cutoffDate))
                .filter(p -> isAuthorizedPayment(p))
                .toList();

        log.info("Found {} relevant payments for status calculation", relevantPayments.size());

        // Group by customer and find last payment date
        Map<String, LocalDate> lastPaymentByCustomer = new HashMap<>();
        for (PaymentDto payment : relevantPayments) {
            String customerId = payment.getCustomerId();
            if (customerId == null || customerId.isBlank()) continue;

            LocalDate currentLast = lastPaymentByCustomer.get(customerId);
            if (currentLast == null || payment.getPaymentDate().isAfter(currentLast)) {
                lastPaymentByCustomer.put(customerId, payment.getPaymentDate());
            }
        }

        // Calculate status for each customer
        Map<String, PaymentStatusDto> statusMap = new HashMap<>();
        LocalDate today = LocalDate.now();

        for (Map.Entry<String, LocalDate> entry : lastPaymentByCustomer.entrySet()) {
            String customerId = entry.getKey();
            LocalDate lastPaymentDate = entry.getValue();

            int daysSinceLastPayment = (int) ChronoUnit.DAYS.between(lastPaymentDate, today);
            String statusColor = determineStatusColor(daysSinceLastPayment);

            PaymentStatusDto status = PaymentStatusDto.builder()
                    .customerId(customerId)
                    .lastPaymentDate(lastPaymentDate)
                    .daysSinceLastPayment(daysSinceLastPayment)
                    .statusColor(statusColor)
                    .build();

            statusMap.put(customerId, status);
        }

        log.info("Calculated payment status for {} customers", statusMap.size());
        return statusMap;
    }

    /**
     * Determine status color based on days since last payment.
     */
    private String determineStatusColor(int daysSinceLastPayment) {
        if (daysSinceLastPayment < WARNING_THRESHOLD_DAYS) {
            return "none";
        } else if (daysSinceLastPayment < DANGER_THRESHOLD_DAYS) {
            return "yellow";
        } else {
            return "red";
        }
    }

    /**
     * Check if payment source is authorized (bank or manual cash).
     */
    private boolean isAuthorizedPayment(PaymentDto payment) {
        String source = payment.getSource();
        if (source == null) return false;

        return source.equalsIgnoreCase("tbc")
                || source.equalsIgnoreCase("bog")
                || source.equalsIgnoreCase("manual-cash")
                || source.equalsIgnoreCase("cash");
    }
}
