package ge.tastyerp.common.dto.audit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Product parent/child hierarchy for the Audit Control module (BOR-74, Phase 1).
 *
 * The legacy system only knew flat Georgian product names (see
 * {@code ProductSalesService}). This class introduces an explicit
 * Parent -> Child relationship so that child products (e.g. "Beef front",
 * "Beef back") aggregate into a single parent node ("Beef") for unified
 * inventory counting.
 *
 * It is intentionally code-driven (not a DB collection) because the meat
 * product taxonomy is small, stable and shared by multiple services. Adding a
 * new child only requires extending the keyword list below.
 *
 * NOTE: Origin tracking (Georgian vs Imported) is deliberately NOT modelled
 * here — that concept was removed from scope per BOR-74.
 */
public final class ProductHierarchy {

    /** Parent category codes. */
    public static final String BEEF = "BEEF";
    public static final String PORK = "PORK";
    public static final String OTHER = "OTHER";

    /**
     * Parent category -> list of known child product names (canonical Georgian
     * names). Matching is done by case-insensitive "contains", so partial /
     * suffixed variants returned by RS.ge still classify correctly.
     */
    private static final Map<String, List<String>> CHILDREN = new LinkedHashMap<>();

    static {
        CHILDREN.put(BEEF, List.of(
                "საქონლის ხორცი (ძვლიანი)",
                "საქონლის ხორცი (რბილი)",
                "საქონლის ხორცი (სუკი)",
                "ხბოს ხორცი"
        ));
        CHILDREN.put(PORK, List.of(
                "ღორის ხორცი (რბილი)",
                "ღორის ხორცი (კისერი)",
                "ღორის ხორცი (ფერდი)",
                "ღორის ხორცი"
        ));
    }

    private ProductHierarchy() {
    }

    /** All parent category codes (excluding the OTHER bucket). */
    public static List<String> parents() {
        return new ArrayList<>(CHILDREN.keySet());
    }

    /** Known child product names for a parent, or empty list. */
    public static List<String> childrenOf(String parent) {
        return new ArrayList<>(CHILDREN.getOrDefault(parent, List.of()));
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
        String normalized = productName.trim();
        for (Map.Entry<String, List<String>> entry : CHILDREN.entrySet()) {
            for (String child : entry.getValue()) {
                if (normalized.equals(child) || normalized.contains(child)) {
                    return entry.getKey();
                }
            }
        }
        return OTHER;
    }
}
