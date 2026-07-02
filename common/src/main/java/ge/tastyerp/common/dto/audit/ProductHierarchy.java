package ge.tastyerp.common.dto.audit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * Only {@link #BEEF} and {@link #PORK} are tracked product categories. Everything
 * else is {@link #OTHER} ("Unclassified") — surfaced for visibility but never
 * subjected to the write-off algorithm (see {@link #appliesWriteOff}).
 *
 * Intentionally code-driven: the taxonomy is small, stable, and shared. Adding a
 * new root only means extending a list below.
 */
public final class ProductHierarchy {

    /** Parent category codes. */
    public static final String BEEF = "BEEF";
    public static final String PORK = "PORK";
    public static final String OTHER = "OTHER";

    /**
     * Tracked parent category -> keyword roots (canonical Georgian substrings).
     * Matching is case-insensitive "contains", so cuts, plain names and carcass
     * variants all classify to the same parent.
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
    }

    private ProductHierarchy() {
    }

    /** All tracked parent category codes (excludes the OTHER / Unclassified bucket). */
    public static List<String> parents() {
        return new ArrayList<>(ROOTS.keySet());
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
     * Whether a category participates in the daily write-off / overage algorithm.
     * Only real tracked meat categories do; OTHER (Unclassified) is a passthrough
     * inventory with no write-off ceiling.
     */
    public static boolean appliesWriteOff(String category) {
        return ROOTS.containsKey(category);
    }

    /** Human-facing category label (OTHER surfaces as "Unclassified"). */
    public static String displayName(String category) {
        if (BEEF.equals(category)) return "Beef";
        if (PORK.equals(category)) return "Pork";
        return "Unclassified";
    }
}
