package ge.tastyerp.common.dto.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A user-defined product-name -> category override (BOR-74 follow-up).
 * One category per product name, stored in Firestore config/product_categories.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductCategoryDto {
    private String name;      // exact product name
    private String category;  // BEEF / PORK / FAT / OTHER
}
