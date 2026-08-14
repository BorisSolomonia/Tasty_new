package ge.tastyerp.payment.audit;

import ge.tastyerp.common.dto.auditlayer.AuditMappingRuleDto;
import ge.tastyerp.common.dto.auditlayer.AuditSourceRowDto;
import ge.tastyerp.common.dto.auditlayer.MappingRuleCriterion;

import java.util.Locale;

/**
 * Decides whether a transaction is "similar" to the one a rule was built from
 * (BOR-91).
 *
 * <p>Kept deliberately small and exact. Every predicate is an equality test on a
 * field the bank supplied — no fuzzy matching, no substring guessing. A rule the
 * user cannot predict the reach of is a rule that will eventually classify
 * something they did not intend, and in an audit that is indistinguishable from
 * the fraud the module exists to surface.</p>
 *
 * <p>Direction is part of every criterion: money out to a counterparty and money
 * in from the same counterparty are never the same transaction kind.</p>
 */
public final class AuditMappingRuleMatcher {

    private AuditMappingRuleMatcher() {
    }

    /** Builds the rule a given criterion would produce from a given row. */
    public static AuditMappingRuleDto ruleFrom(MappingRuleCriterion criterion, AuditSourceRowDto row) {
        return AuditMappingRuleDto.builder()
                .criterion(criterion)
                .direction(trim(row.getDirection()))
                .counterpartyTin(identityOf(row))
                .counterpartyName(normalize(row.getCounterpartyName()))
                .description(trim(row.getDescription()))
                .transactionType(trim(row.getTransactionType()))
                .active(true)
                .build();
    }

    /**
     * @return true when {@code row} is caught by {@code rule}
     */
    public static boolean matches(AuditMappingRuleDto rule, AuditSourceRowDto row) {
        if (rule == null || row == null || !rule.isActive()) {
            return false;
        }
        if (!equalsIgnoreCase(rule.getDirection(), trim(row.getDirection()))) {
            return false;
        }
        return switch (rule.getCriterion()) {
            case COUNTERPARTY -> sameCounterparty(rule, row);
            case COUNTERPARTY_AND_DESCRIPTION ->
                    sameCounterparty(rule, row)
                            && equalsIgnoreCase(rule.getDescription(), trim(row.getDescription()));
            case DESCRIPTION ->
                    rule.getDescription() != null
                            && equalsIgnoreCase(rule.getDescription(), trim(row.getDescription()));
            case TRANSACTION_TYPE ->
                    rule.getTransactionType() != null
                            && equalsIgnoreCase(rule.getTransactionType(), trim(row.getTransactionType()));
        };
    }

    /**
     * Counterparty match uses the resolved tax code when there is one, and falls
     * back to the normalised name otherwise — the same identity the aggregates
     * use, so a rule catches exactly the rows the totals attribute to that
     * counterparty.
     */
    private static boolean sameCounterparty(AuditMappingRuleDto rule, AuditSourceRowDto row) {
        String rowTin = identityOf(row);
        if (rule.getCounterpartyTin() != null && rowTin != null) {
            return rule.getCounterpartyTin().equals(rowTin);
        }
        if (rule.getCounterpartyTin() != null || rowTin != null) {
            // One side is identified and the other is not: not the same party as
            // far as we can prove, so no match.
            return false;
        }
        String ruleName = rule.getCounterpartyName();
        return ruleName != null && ruleName.equals(normalize(row.getCounterpartyName()));
    }

    /** A plain-language statement of what a rule catches, shown before confirming. */
    public static String explain(MappingRuleCriterion criterion, AuditSourceRowDto row) {
        String direction = "DEBIT".equalsIgnoreCase(trim(row.getDirection())) ? "payment to" : "receipt from";
        String who = row.getCounterpartyName() != null ? row.getCounterpartyName()
                : (identityOf(row) != null ? "tax code " + identityOf(row) : "this counterparty");
        return switch (criterion) {
            case COUNTERPARTY ->
                    "Every " + direction + " " + who + ".";
            case COUNTERPARTY_AND_DESCRIPTION ->
                    "Every " + direction + " " + who + " described as \""
                            + trim(row.getDescription()) + "\".";
            case DESCRIPTION ->
                    "Every transaction described as \"" + trim(row.getDescription())
                            + "\", whoever it is with.";
            case TRANSACTION_TYPE ->
                    "Every transaction the bank typed \"" + trim(row.getTransactionType())
                            + "\" in this direction.";
        };
    }

    /** Whether a criterion can be built from this row at all. */
    public static boolean isApplicable(MappingRuleCriterion criterion, AuditSourceRowDto row) {
        return switch (criterion) {
            case COUNTERPARTY -> identityOf(row) != null || normalize(row.getCounterpartyName()) != null;
            case COUNTERPARTY_AND_DESCRIPTION ->
                    (identityOf(row) != null || normalize(row.getCounterpartyName()) != null)
                            && trim(row.getDescription()) != null;
            case DESCRIPTION -> trim(row.getDescription()) != null;
            case TRANSACTION_TYPE -> trim(row.getTransactionType()) != null;
        };
    }

    private static String identityOf(AuditSourceRowDto row) {
        String resolved = trim(row.getResolvedCounterpartyTin());
        return resolved != null ? resolved : trim(row.getCounterpartyTin());
    }

    private static String normalize(String name) {
        return AuditCounterpartyResolver.normalize(name);
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return a.toLowerCase(Locale.ROOT).equals(b.toLowerCase(Locale.ROOT));
    }
}
