package ge.tastyerp.common.dto.waybill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Product line item within a waybill (from RS.ge goods list).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WaybillGoodDto {
    private String name;
    private BigDecimal quantity;
    private String unit;
    private BigDecimal unitPrice;
    private BigDecimal totalPrice;
}
