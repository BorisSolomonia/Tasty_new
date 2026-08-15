package ge.tastyerp.common.util;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BOR-81 finding B-4 regression. The spelled-out forms are the ones that
 * diverged between the three former copies of this rule.
 */
class UnitClassifierTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "კგ", "KG", "kg", " kg ", "კილოგრამი", "კილო", "kilogram", "kilograms", "Kilo",
            "", "   ", "unknown-unit"
    })
    void kilogramForms(String unit) {
        assertTrue(UnitClassifier.isKilogram(unit), "'" + unit + "' must count as kilograms");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ცალი", "ცალ", "piece", "pcs", "ლიტრი", "liter", "litre", "შეკვრა", "კომპლექტი",
            "pack", "set", "წყვილი", "გრამი", "gram", "grams"
    })
    void nonKilogramForms(String unit) {
        assertFalse(UnitClassifier.isKilogram(unit), "'" + unit + "' must NOT count as kilograms");
    }

    @org.junit.jupiter.api.Test
    void nullDefaultsToKilogram() {
        assertTrue(UnitClassifier.isKilogram(null));
    }
}
