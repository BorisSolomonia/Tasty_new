package ge.tastyerp.common.util;

import java.util.List;
import java.util.Locale;

/**
 * The one place that decides whether an RS.ge goods line is measured in
 * kilograms — the basis for every inventory-conservation figure in the system.
 *
 * <p>Extracted in BOR-81 (finding B-4). Three services each carried their own
 * copy of this rule and one of them lacked the positive-kg short-circuit, so
 * {@code "კილოგრამი"} (Georgian for <i>kilogram</i>, which contains the
 * substring {@code "გრამ"} = gram) and {@code "kilogram"} were counted as
 * kilograms on the {@code /audit-control} page and silently dropped from the
 * {@code /audit} page for the same period. Two dashboards, one data set,
 * different kilograms. This class mirrors the precedent set by
 * {@code ProductCategoryResolver}: a shared rule cannot drift.</p>
 *
 * <p>Blank or unknown units default to kg because meat lines are overwhelmingly
 * kg and RS.ge's kg encoding is not guaranteed; only units explicitly
 * recognised as pieces/volume/etc. are excluded.</p>
 */
public final class UnitClassifier {

    /** Substrings that positively identify kilograms; checked FIRST. */
    static final List<String> KG_MARKERS = List.of("კგ", "kg", "კილ", "kilo");

    /** Unit substrings (lowercased) that mark a line as NOT measured in kilograms. */
    static final List<String> NON_KG_UNITS = List.of(
            "ცალ",      // ცალი – pieces
            "piece", "pcs",
            "ლიტრ", "liter", "litre",  // volume
            "შეკვრ",    // bundle / pack
            "კომპლ", "pack", "set",
            "წყვილ",    // pair
            "გრამ", "gram"             // grams – mass but not kg; excluded to avoid unit mismatch
    );

    private UnitClassifier() {
    }

    public static boolean isKilogram(String unit) {
        if (unit == null || unit.isBlank()) {
            return true;
        }
        String u = unit.trim().toLowerCase(Locale.ROOT);
        for (String marker : KG_MARKERS) {
            if (u.contains(marker)) {
                return true;
            }
        }
        for (String nonKg : NON_KG_UNITS) {
            if (u.contains(nonKg)) {
                return false;
            }
        }
        return true;
    }
}
