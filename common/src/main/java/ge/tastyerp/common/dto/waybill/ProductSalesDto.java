package ge.tastyerp.common.dto.waybill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Per-customer beef/pork kg aggregation from waybill goods lines.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductSalesDto {
    private String customerId;
    private String customerName;
    private BigDecimal beefKg;
    private BigDecimal porkKg;
    private BigDecimal totalKg;
    private List<String> beefProductsFound;
    private List<String> porkProductsFound;
}
