package ge.tastyerp.common.dto.audit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single source of truth for meat product categorization, shared by the
 * product-sales report (waybill-service {@code ProductSalesService}) and the
 * Audit Control inventory engine (BOR-74).
 *
 * <h3>Matching model (the "benchmark" borrowed from product-sales)</h3>
 * Classification is deterministic and auditable: a raw RS.ge goods name is
 * assigned to a parent category if it <b>contains</b> (case-insensitive) any of
 * that category's keyword roots. There is no heuristic / fuzzy matching.
 *
 * <h3>Root-substring keywords</h3>
 * Every beef cut RS.ge emits ("საქონლის ხორცი (ძვლიანი)", "(რბილი)", "(სუკი)" …)
 * is prefixed by the root <b>"საქონლის ხორცი"</b>. Matching on the root therefore
 * catches all cuts <i>and</i> the plain / carcass names that appear on PURCHASE
 * (stock-in) waybills — which the old per-cut list silently dropped into OTHER,
 * corrupting the inventory conservation math. Pork works the same way via the
 * "ღორის ხორცი" root. Veal ("ხბოს ხორცი") is a separate beef root.
 *
 * <h3>Categories</h3>
 * Names that match no root fall to {@link #OTHER} ("Unclassified"). Every
 * inventory-bearing category carries an editable write-off rate (BOR-79);
 * only {@link #BEEF}/{@link #PORK} default to a non-zero 28% (see
 * {@link #appliesWriteOff} / {@link #defaultWriteOffPercent}).
 *
 * Intentionally code-driven: the taxonomy is small, stable, and shared. Adding a
 * new root only means extending a list below.
 */
public final class ProductHierarchy {

    /** Parent category codes. */
    public static final String BEEF = "BEEF";
    public static final String PORK = "PORK";
    public static final String SHEEP = "SHEEP";
    public static final String CHICKEN = "CHICKEN";
    public static final String FAT = "FAT";
    public static final String OTHER_FOOD = "OTHER_FOOD";
    /** Purchase-only supplies (car maintenance, spare parts): never sold, own section. */
    public static final String SUPPLIES = "SUPPLIES";
    public static final String OTHER = "OTHER";

    /**
     * Whole-carcass primary meats whose write-off defaults to 28% of purchased.
     * Every other (non-supplies) category defaults to 0% — effectively
     * passthrough until the user sets a rate (BOR-79 editable per category).
     */
    private static final Set<String> DEFAULT_28_WRITE_OFF = Set.of(BEEF, PORK);

    /** Every selectable category, in display order. */
    private static final List<String> ALL_CATEGORIES =
            List.of(BEEF, PORK, SHEEP, CHICKEN, FAT, OTHER_FOOD, SUPPLIES, OTHER);

    /**
     * Auto-classification roots: category -> keyword roots (canonical Georgian
     * substrings). Matching is case-insensitive "contains", so cuts, plain names
     * and carcass variants all classify to the same parent. These seed the
     * editable per-product overrides; a user may reassign any product afterwards.
     */
    private static final Map<String, List<String>> ROOTS = new LinkedHashMap<>();

    static {
        ROOTS.put(BEEF, List.of(
                "საქონლის ხორცი",   // root: plain beef + all cuts (ძვლიანი/რბილი/სუკი) + carcass variants
                "ხბოს ხორცი"         // veal
        ));
        ROOTS.put(PORK, List.of(
                "ღორის ხორცი"        // root: plain pork + all cuts (რბილი/კისერი/ფერდი)
        ));
        ROOTS.put(FAT, List.of(
                "ქონი",              // tallow / fat (ღორის ქონი, საქონლის ქონი) — safe: not a substring of საქონლის
                "ცხიმ"               // ცხიმი – fat
        ));
        ROOTS.put(SHEEP, List.of(
                "ცხვრის",            // mutton (ცხვრის ხორცი)
                "ცხვარ",             // sheep
                "ბატკნის",           // lamb
                "ბატკან",
                "კრავ"               // lamb (კრავის ხორცი)
        ));
        ROOTS.put(CHICKEN, List.of(
                "ქათმის",            // chicken (ქათმის ხორცი)
                "ქათამ",             // chicken
                "წიწილ"              // broiler / chick
        ));
        // OTHER_FOOD and SUPPLIES have no auto-roots: they are assigned manually
        // on the Product Categories page (heterogeneous, no reliable keyword).
    }

    private ProductHierarchy() {
    }

    /** All tracked parent category codes with auto-classification roots. */
    public static List<String> parents() {
        return new ArrayList<>(ROOTS.keySet());
    }

    /** Every selectable category code (for validation / UI dropdowns). */
    public static List<String> allCategories() {
        return new ArrayList<>(ALL_CATEGORIES);
    }

    /** Whether a category code is one of the selectable categories. */
    public static boolean isValidCategory(String category) {
        return category != null && ALL_CATEGORIES.contains(category);
    }

    /** Keyword roots for a parent, or empty list. */
    public static List<String> childrenOf(String parent) {
        return new ArrayList<>(ROOTS.getOrDefault(parent, List.of()));
    }

    /**
     * Classify a raw product name into its parent category.
     *
     * @param productName raw goods name from a waybill line (may be null)
     * @return one of {@link #BEEF}, {@link #PORK} or {@link #OTHER}
     */
    public static String classify(String productName) {
        if (productName == null || productName.isBlank()) {
            return OTHER;
        }
        String normalized = productName.trim().toLowerCase();
        for (Map.Entry<String, List<String>> entry : ROOTS.entrySet()) {
            for (String root : entry.getValue()) {
                if (normalized.contains(root.toLowerCase())) {
                    return entry.getKey();
                }
            }
        }
        return OTHER;
    }

    /**
     * Whether a category carries an editable posib write-off rate. Every
     * inventory-bearing category does (BOR-79); only purchase-only SUPPLIES has
     * no inventory and therefore no write-off. Non-meat categories default to
     * 0% (passthrough) until the user sets a rate — see
     * {@link #defaultWriteOffPercent}.
     */
    public static boolean appliesWriteOff(String category) {
        return isValidCategory(category) && !SUPPLIES.equals(category);
    }

    /**
     * Default posib write-off percent for a category: 28 for whole-carcass
     * primary meats (BEEF, PORK), 0 for everything else (passthrough until the
     * user sets a rate).
     */
    public static java.math.BigDecimal defaultWriteOffPercent(String category) {
        return DEFAULT_28_WRITE_OFF.contains(category)
                ? new java.math.BigDecimal("28") : java.math.BigDecimal.ZERO;
    }

    /**
     * Whether a category is a purchase-only supply (car maintenance, spare parts):
     * never sold, excluded from the meat inventory/cash-gap math and shown in its
     * own Supplies section.
     */
    public static boolean isSupplies(String category) {
        return SUPPLIES.equals(category);
    }

    /** Human-facing category label. */
    public static String displayName(String category) {
        if (BEEF.equals(category)) return "Beef";
        if (PORK.equals(category)) return "Pork";
        if (SHEEP.equals(category)) return "Sheep";
        if (CHICKEN.equals(category)) return "Chicken";
        if (FAT.equals(category)) return "Fat";
        if (OTHER_FOOD.equals(category)) return "Other food";
        if (SUPPLIES.equals(category)) return "Supplies";
        return "Other";
    }
}
