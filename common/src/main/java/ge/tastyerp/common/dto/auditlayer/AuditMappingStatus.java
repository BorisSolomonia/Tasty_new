package ge.tastyerp.common.dto.auditlayer;

/**
 * Mapping lifecycle status for one source row (BOR-89 §7).
 *
 * <p>The ticket's rule "do not silently auto-map low-confidence rows" is enforced
 * by keeping {@link #SUGGESTED} distinct from {@link #AUTO_MAPPED}: a suggestion
 * contributes nothing to any total until a human accepts it.</p>
 */
public enum AuditMappingStatus {

    /** No mapping exists. The row's full amount is unresolved. */
    UNMAPPED,

    /** The engine proposed a mapping that nobody has accepted. Not counted. */
    SUGGESTED,

    /** A high-confidence rule mapped the row automatically. Counted. */
    AUTO_MAPPED,

    /** Splits exist but do not cover the full source amount. */
    PARTIALLY_MAPPED,

    /** A person mapped the row by hand. */
    MANUALLY_MAPPED,

    /** A person replaced an automatic mapping. The prior value stays in the log. */
    OVERRIDDEN,

    /** The mapping was withdrawn. Retained for history; excluded from totals. */
    VOIDED
}
