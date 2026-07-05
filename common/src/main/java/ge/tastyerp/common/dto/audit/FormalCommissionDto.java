package ge.tastyerp.common.dto.audit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Real commission AR for one formal-sales customer (BOR-76 Part 3).
 *
 * The documented AR ({@code documentedKg · documented sale price}) is shown for
 * reference only and is explicitly IGNORED for real cash flow; the real
 * receivable is {@code commissionAr = documentedKg · commissionPerKg}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormalCommissionDto {
    private String customerId;        // canonical TIN
    private String customerName;

    private BigDecimal documentedKg;  // Σ SALE-movement kg for this customer in range
    private BigDecimal documentedAr;  // RS.ge invoice AR — reference only, ignored

    private BigDecimal commissionPerKg;
    private BigDecimal commissionAr;  // documentedKg · commissionPerKg
}
