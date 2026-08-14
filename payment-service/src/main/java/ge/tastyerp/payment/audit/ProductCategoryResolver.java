package ge.tastyerp.payment.audit;

import java.util.Map;

/**
 * The one place a product's category is decided.
 *
 * <p>A product belongs to a category — BEEF, PORK, FAT, … — either because the
 * operator said so or, failing that, because {@code ProductHierarchy}
 * auto-classified it. Both the BOR-74 audit-control dashboard and the BOR-89
 * audit layer must reach the same answer: write-off rates are keyed by category,
 * so two implementations of this rule means two different inventory positions
 * for the same period, and no way to tell which is right.</p>
 *
 * <p>They previously did diverge. The audit layer read only the auto category
 * and ignored operator overrides entirely, so re-categorising a product on
 * {@code /audit-control} changed one page's numbers and not the other's. This
 * class exists so that cannot happen again — both call it.</p>
 *
 * <p>Overrides live in config-service ({@code /api/config/product-categories}),
 * keyed by product name. There is one category per product name, globally: a
 * product is not BEEF on one document line and FAT on another.</p>
 */
public final class ProductCategoryResolver {

    private ProductCategoryResolver() {
    }

    /**
     * Case-insensitive, trimmed key matching a product name to its override.
     * Product names arrive from RS.ge with inconsistent spacing and case.
     */
    public static String overrideKey(String name) {
        return name == null ? "" : name.trim().toLowerCase();
    }

    /**
     * @param productName  the raw RS.ge goods name
     * @param autoCategory what {@code ProductHierarchy} classified it as
     * @param overrides    operator overrides, keyed by {@link #overrideKey}
     * @return the operator's category when they set one, else the automatic one
     */
    public static String resolve(String productName, String autoCategory,
                                 Map<String, String> overrides) {
        if (productName == null || overrides == null) {
            return autoCategory;
        }
        return overrides.getOrDefault(overrideKey(productName), autoCategory);
    }
}
