package ge.tastyerp.common.dto.auditlayer;

/**
 * What makes another transaction "similar" enough to inherit a mapping
 * (BOR-91).
 *
 * <p>The user picks one of these when they choose to apply a mapping beyond the
 * single row in front of them, and the UI shows how many rows each option would
 * catch <em>before</em> they confirm. That is deliberate: silently generalising
 * one classification into a rule is how an audit tool ends up asserting things
 * nobody decided.</p>
 *
 * <p>Every criterion also matches on direction — a payment to a counterparty and
 * a receipt from the same counterparty are never the same thing.</p>
 */
public enum MappingRuleCriterion {

    /**
     * Every transaction with this counterparty, in this direction. The broadest
     * option: "everything I pay this supplier is a supplier payment". Risky when
     * one counterparty covers several kinds of payment.
     */
    COUNTERPARTY,

    /**
     * This counterparty <em>and</em> this exact payment description. Narrower —
     * lets one counterparty carry different treatments for different payment
     * types.
     */
    COUNTERPARTY_AND_DESCRIPTION,

    /**
     * Any transaction with this exact description, whoever it is with. Useful for
     * bank-generated wording such as a card-withdrawal or commission line.
     */
    DESCRIPTION,

    /**
     * Every transaction the bank gave this transaction type, in this direction —
     * e.g. all rows typed "სხვა ხარჯები". The bank's own classification, so the
     * broadest but also the most objective.
     */
    TRANSACTION_TYPE
}
