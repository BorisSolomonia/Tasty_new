package ge.tastyerp.common.dto.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * A customer whose RS.ge sales are FORMAL only — they receive documentation but
 * no physical goods (BOR-76 Part 3). Their documented AR is ignored for real
 * cash flow; instead the business earns a real commission of
 * {@code commissionPerKg} GEL per documented kg.
 *
 * Stored in Firestore {@code config/formal_sales_customers}; the id is the
 * canonical (leading-zero-stripped) TIN so it matches the movement counterparties.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormalSalesCustomerDto {
    private String customerId;         // canonical TIN
    private String customerName;       // optional label
    private BigDecimal commissionPerKg; // GEL/kg, editable (default 0.50)
}
