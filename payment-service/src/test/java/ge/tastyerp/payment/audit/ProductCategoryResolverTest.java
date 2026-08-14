package ge.tastyerp.payment.audit;

import ge.tastyerp.common.dto.audit.ProductHierarchy;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One product, one category, one rule.
 *
 * <p>The audit-control dashboard and the audit layer both decide which category
 * a product belongs to. They used to do it differently — the layer read only the
 * automatic classification and ignored operator overrides — so re-categorising a
 * product changed one page's inventory and not the other's. Write-off rates are
 * keyed by category, so the divergence reached the numbers, not just the
 * labels.</p>
 */
class ProductCategoryResolverTest {

    private static final Map<String, String> OVERRIDES = Map.of(
            "საქონლის ქონი", ProductHierarchy.FAT,
            "ღორის ტანხორცი", ProductHierarchy.PORK);

    @Test
    void anOperatorOverrideWinsOverTheAutomaticClassification() {
        String resolved = ProductCategoryResolver.resolve(
                "საქონლის ქონი", ProductHierarchy.BEEF, OVERRIDES);

        assertEquals(ProductHierarchy.FAT, resolved,
                "the operator said this is fat; the auto-classifier said beef");
    }

    @Test
    void theAutomaticClassificationStandsWhenNobodyOverrodeIt() {
        assertEquals(ProductHierarchy.BEEF,
                ProductCategoryResolver.resolve("საქონლის ხორცი", ProductHierarchy.BEEF, OVERRIDES));
    }

    @Test
    void matchingIgnoresCaseAndSurroundingSpace() {
        // RS.ge product names arrive with inconsistent spacing and case; an
        // override that failed to match would silently revert a product to its
        // automatic category and move its write-off rate with it.
        assertEquals(ProductHierarchy.FAT,
                ProductCategoryResolver.resolve("  საქონლის ქონი  ", ProductHierarchy.BEEF, OVERRIDES));
        assertEquals("საქონლის ქონი", ProductCategoryResolver.overrideKey("  საქონლის ქონი  "));
    }

    @Test
    void aMissingOverrideMapNeverBreaksResolution() {
        assertEquals(ProductHierarchy.BEEF,
                ProductCategoryResolver.resolve("anything", ProductHierarchy.BEEF, null));
    }

    @Test
    void aNullProductNameFallsBackToTheAutomaticCategory() {
        assertEquals(ProductHierarchy.OTHER,
                ProductCategoryResolver.resolve(null, ProductHierarchy.OTHER, OVERRIDES));
        assertEquals("", ProductCategoryResolver.overrideKey(null));
    }

    @Test
    void theCategoriesOfferedAreTheOnesTheLedgerUnderstands() {
        for (String category : ProductHierarchy.allCategories()) {
            assertTrue(ProductHierarchy.isValidCategory(category),
                    category + " is offered to users, so the ledger must accept it");
        }
        assertTrue(ProductHierarchy.allCategories().contains(ProductHierarchy.FAT));
    }
}
