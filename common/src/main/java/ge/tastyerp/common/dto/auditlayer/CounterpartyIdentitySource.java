package ge.tastyerp.common.dto.auditlayer;

/**
 * How a source row's counterparty came to be identified (BOR-89 §6, §13).
 *
 * <p>Identity drives the supplier coverage control, so how it was established
 * has to travel with it. A tax code the bank printed and a tax code inferred
 * from a name are not the same evidence, and the UI must never present them as
 * though they were.</p>
 */
public enum CounterpartyIdentitySource {

    /** The statement itself carried the tax code. Strongest. */
    DIRECT,

    /**
     * The tax-code column was blank, but the counterparty name matches — exactly,
     * after normalisation — a name that carries this tax code on other rows or on
     * RS.ge documents. Inferred, but from the operator's own data.
     */
    RESOLVED_BY_NAME,

    /** A person explicitly taught this name-to-tax-code link. */
    MANUAL_ALIAS,

    /**
     * The name maps to more than one tax code, so it was deliberately NOT
     * resolved. Guessing between them would be worse than leaving it open.
     */
    AMBIGUOUS,

    /** No tax code and nothing to infer one from — e.g. an ATM withdrawal. */
    UNRESOLVED
}
