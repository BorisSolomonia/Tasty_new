package ge.tastyerp.common.dto.payment;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Data Transfer Object for Payment entities.
 * Represents a bank statement payment or manual cash payment.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentDto {

    private String id;
    private String uniqueCode;          // date|amountCents|customerId|balanceCents
    private String customerId;          // supplierName in legacy
    private String customerName;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate paymentDate;

    private BigDecimal amount;
    private BigDecimal balance;
    private String description;
    private String source;              // tbc, bog, excel, manual-cash, bank-api
    private boolean isAfterCutoff;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime uploadedAt;

    private Integer excelRowIndex;      // For audit/debugging
}
