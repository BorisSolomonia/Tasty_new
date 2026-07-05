package ge.tastyerp.common.dto.audit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Unique product names seen on waybills in a date range, split by side, each
 * with its resolved category (user override if present, else auto-classified).
 *
 * Powers the Product Categories management page (one category per product name).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductCatalogDto {

    private List<Row> purchased;
    private List<Row> sold;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Row {
        private String name;         // exact RS.ge product name (unique within its list)
        private String category;     // resolved category (user override if present, else auto)
        private boolean overridden;  // true if an explicit user override drives the category

        private BigDecimal vatPercent;  // resolved VAT % for this product (override else default 18)
        private boolean vatOverridden;  // true if the VAT % is a user override (not the 18 default)
    }
}
