package ge.tastyerp.common.dto.auditlayer;

/**
 * The kind of immutable source record an audit mapping points at (BOR-89 §6).
 *
 * <p>Source records are never modified by the audit layer. A mapping only ever
 * references one by {@link AuditSourceType} plus its source row id.</p>
 */
public enum AuditSourceType {

    /** A bank statement row (Firestore {@code bankTransactions}). */
    BANK,

    /** An RS.ge waybill / document row. */
    RS_GE,

    /** Payment evidence supplied by a counterparty — a check or receipt. */
    CHECK,

    /** A manually entered audit-layer record with no upstream source row. */
    MANUAL
}
