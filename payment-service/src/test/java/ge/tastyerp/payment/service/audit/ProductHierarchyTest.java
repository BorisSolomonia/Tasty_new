package ge.tastyerp.payment.service.audit;

import ge.tastyerp.common.dto.audit.ProductHierarchy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the shared product categorization (BOR-74 follow-up).
 *
 * The root-substring approach must catch the plain / carcass beef names that
 * appear on PURCHASE waybills — the case the old per-cut list silently dropped
 * into OTHER, corrupting inventory conservation.
 */
class ProductHierarchyTest {

    @Test
    @DisplayName("Beef cuts classify as BEEF")
    void beefCuts() {
        assertEquals(ProductHierarchy.BEEF, ProductHierarchy.classify("საქონლის ხორცი (ძვლიანი)"));
        assertEquals(ProductHierarchy.BEEF, ProductHierarchy.classify("საქონლის ხორცი (რბილი)"));
        assertEquals(ProductHierarchy.BEEF, ProductHierarchy.classify("საქონლის ხორცი (სუკი)"));
    }

    @Test
    @DisplayName("Plain / carcass beef (purchase-side) now classifies as BEEF, not OTHER")
    void plainBeefPurchaseNames() {
        assertEquals(ProductHierarchy.BEEF, ProductHierarchy.classify("საქონლის ხორცი"));
        assertEquals(ProductHierarchy.BEEF, ProductHierarchy.classify("საქონლის ხორცი ნახევარტანი"));
        assertEquals(ProductHierarchy.BEEF, ProductHierarchy.classify("  საქონლის ხორცი  "));
    }

    @Test
    @DisplayName("Veal classifies as BEEF")
    void veal() {
        assertEquals(ProductHierarchy.BEEF, ProductHierarchy.classify("ხბოს ხორცი"));
    }

    @Test
    @DisplayName("Pork plain and cuts classify as PORK")
    void pork() {
        assertEquals(ProductHierarchy.PORK, ProductHierarchy.classify("ღორის ხორცი"));
        assertEquals(ProductHierarchy.PORK, ProductHierarchy.classify("ღორის ხორცი (კისერი)"));
        assertEquals(ProductHierarchy.PORK, ProductHierarchy.classify("ღორის ხორცი (ფერდი)"));
    }

    @Test
    @DisplayName("Fat (ქონი/ცხიმი) classifies as FAT, incl. beef/pork fat")
    void fat() {
        assertEquals(ProductHierarchy.FAT, ProductHierarchy.classify("ქონი"));
        assertEquals(ProductHierarchy.FAT, ProductHierarchy.classify("ღორის ქონი"));
        assertEquals(ProductHierarchy.FAT, ProductHierarchy.classify("საქონლის ქონი"));
        assertEquals(ProductHierarchy.FAT, ProductHierarchy.classify("ცხიმი"));
        // "საქონლის ხორცი" must NOT be caught by the fat root despite containing "ქონ"
        assertEquals(ProductHierarchy.BEEF, ProductHierarchy.classify("საქონლის ხორცი"));
    }

    @Test
    @DisplayName("Non-meat and null classify as OTHER")
    void other() {
        assertEquals(ProductHierarchy.OTHER, ProductHierarchy.classify("შესაფუთი მასალა"));
        assertEquals(ProductHierarchy.OTHER, ProductHierarchy.classify(null));
        assertEquals(ProductHierarchy.OTHER, ProductHierarchy.classify("   "));
    }

    @Test
    @DisplayName("Only BEEF and PORK participate in write-off; FAT and OTHER are passthrough")
    void writeOffApplicability() {
        assertTrue(ProductHierarchy.appliesWriteOff(ProductHierarchy.BEEF));
        assertTrue(ProductHierarchy.appliesWriteOff(ProductHierarchy.PORK));
        assertFalse(ProductHierarchy.appliesWriteOff(ProductHierarchy.FAT));
        assertFalse(ProductHierarchy.appliesWriteOff(ProductHierarchy.OTHER));
    }
}
