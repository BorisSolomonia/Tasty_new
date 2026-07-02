package ge.tastyerp.common.dto.audit;

import com.fasterxml.jackson.annotation.JsonFormat;
import ge.tastyerp.common.dto.waybill.WaybillType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A single product movement (one waybill goods line) resolved for inventory.
 *
 * Produced by waybill-service from RS.ge data and consumed by the Audit Control
 * inventory engine in payment-service. PURCHASE = stock in, SALE = stock out.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductMovementDto {

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate date;

    private WaybillType type;        // PURCHASE (in) or SALE (out)

    private String productName;      // raw RS.ge goods name
    private String parentCategory;   // BEEF / PORK / OTHER (see ProductHierarchy)

    private BigDecimal quantityKg;
    private String unit;              // raw RS.ge unit of measure (e.g. კგ, ცალი); used to guard kg-only inventory math
    private BigDecimal amount;        // line total price (GEL)

    private String waybillId;
    private String counterpartyId;    // buyer TIN (sale) or seller TIN (purchase)
}
