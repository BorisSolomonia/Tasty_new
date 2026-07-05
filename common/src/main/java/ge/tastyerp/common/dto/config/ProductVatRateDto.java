package ge.tastyerp.common.dto.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * A user-defined VAT rate override for one product name.
 *
 * Every product defaults to {@code 18}% (Georgian standard, VAT-inclusive). The
 * user may set a different rate per product on the Product Categories page — e.g.
 * {@code 0} for VAT-exempt sheep/chicken. Stored in Firestore
 * {@code config/product_vat_rates}. Keyed (case-insensitively) by product name,
 * mirroring the product-category overrides.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductVatRateDto {
    private String name;         // exact product name
    private BigDecimal percent;  // VAT percentage, 0..100 (default 18)
}
