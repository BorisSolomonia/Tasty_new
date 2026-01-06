package ge.tastyerp.common.dto.payment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Payment status for visual indicators on frontend.
 *
 * Color indicators based on days since last payment:
 * - "none": Last payment within 14 days (no color)
 * - "yellow": Last payment 14-30 days ago (warning)
 * - "red": Last payment 30+ days ago (danger)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentStatusDto {

    private String customerId;

    /**
     * Date of the most recent payment (bank or manual cash).
     */
    private LocalDate lastPaymentDate;

    /**
     * Number of days since the last payment.
     */
    private Integer daysSinceLastPayment;

    /**
     * Visual indicator color: "none", "yellow", or "red".
     */
    private String statusColor;
}
