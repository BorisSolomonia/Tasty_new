package ge.tastyerp.payment.audit;

import ge.tastyerp.common.dto.auditlayer.AuditMappingRuleDto;
import ge.tastyerp.common.dto.auditlayer.AuditSourceRowDto;
import ge.tastyerp.common.dto.auditlayer.AuditSourceType;
import ge.tastyerp.common.dto.auditlayer.MappingRuleCriterion;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What "similar" is allowed to mean (BOR-91).
 *
 * <p>The governing rule: a reusable mapping must catch exactly what the user was
 * shown it would catch. Every predicate is an equality test on a field the bank
 * supplied — anything looser makes the reach of a rule unpredictable, and an
 * unpredictable rule eventually classifies something nobody chose.</p>
 */
class AuditMappingRuleMatcherTest {

    private static AuditSourceRowDto row(String direction, String tin, String name,
                                         String description, String type) {
        return AuditSourceRowDto.builder()
                .sourceType(AuditSourceType.BANK)
                .sourceRowId("r-" + description)
                .direction(direction)
                .amount(new BigDecimal("100"))
                .resolvedCounterpartyTin(tin)
                .counterpartyName(name)
                .description(description)
                .transactionType(type)
                .build();
    }

    private static final AuditSourceRowDto SUBJECT =
            row("DEBIT", "404737344", "შპს ერთგული ვაჟა პაპა", "პროდუქციის საფასური",
                    "გადარიცხვა თანხის გატანა");

    private static AuditMappingRuleDto rule(MappingRuleCriterion criterion) {
        return AuditMappingRuleMatcher.ruleFrom(criterion, SUBJECT);
    }

    @Test
    void counterpartyRuleCatchesEveryPaymentToThatCounterparty() {
        AuditMappingRuleDto r = rule(MappingRuleCriterion.COUNTERPARTY);

        assertTrue(AuditMappingRuleMatcher.matches(r,
                row("DEBIT", "404737344", "შპს ერთგული ვაჟა პაპა", "სხვა დანიშნულება", "სხვა")),
                "same counterparty, different description — still caught");
    }

    @Test
    void aRuleNeverCrossesDirection() {
        AuditMappingRuleDto r = rule(MappingRuleCriterion.COUNTERPARTY);

        assertFalse(AuditMappingRuleMatcher.matches(r,
                row("CREDIT", "404737344", "შპს ერთგული ვაჟა პაპა", "პროდუქციის საფასური", "შემოსავალი")),
                "money received from a counterparty is not money paid to them");
    }

    @Test
    void counterpartyAndDescriptionIsNarrowerThanCounterpartyAlone() {
        AuditMappingRuleDto narrow = rule(MappingRuleCriterion.COUNTERPARTY_AND_DESCRIPTION);
        AuditSourceRowDto otherPurpose =
                row("DEBIT", "404737344", "შპს ერთგული ვაჟა პაპა", "ხელფასი", "გადარიცხვა თანხის გატანა");

        assertFalse(AuditMappingRuleMatcher.matches(narrow, otherPurpose),
                "one counterparty can carry different payment types");
        assertTrue(AuditMappingRuleMatcher.matches(rule(MappingRuleCriterion.COUNTERPARTY), otherPurpose));
    }

    @Test
    void descriptionRuleIgnoresWhoItIsWith() {
        AuditMappingRuleDto r = rule(MappingRuleCriterion.DESCRIPTION);

        assertTrue(AuditMappingRuleMatcher.matches(r,
                row("DEBIT", "999999999", "სხვა კომპანია", "პროდუქციის საფასური", "სხვა")));
    }

    @Test
    void transactionTypeRuleFollowsTheBanksOwnClassification() {
        AuditMappingRuleDto r = rule(MappingRuleCriterion.TRANSACTION_TYPE);

        assertTrue(AuditMappingRuleMatcher.matches(r,
                row("DEBIT", null, "ვინმე", "სხვა", "გადარიცხვა თანხის გატანა")));
        assertFalse(AuditMappingRuleMatcher.matches(r,
                row("DEBIT", null, "ვინმე", "სხვა", "სხვა ხარჯები")));
    }

    @Test
    void anIdentifiedCounterpartyNeverMatchesAnUnidentifiedOne() {
        // The dangerous case: a blank tax code must not silently join a rule
        // built on a real one, or money moves to the wrong party.
        AuditMappingRuleDto r = rule(MappingRuleCriterion.COUNTERPARTY);

        assertFalse(AuditMappingRuleMatcher.matches(r,
                row("DEBIT", null, "სულ სხვა სახელი", "პროდუქციის საფასური", "გადარიცხვა")));
    }

    @Test
    void unidentifiedCounterpartiesMatchOnNormalisedNameOnly() {
        AuditSourceRowDto noTin = row("DEBIT", null, "შვიდი ტრეიდ გრუპი", "მომსახურება", "გადარიცხვა");
        AuditMappingRuleDto r = AuditMappingRuleMatcher.ruleFrom(
                MappingRuleCriterion.COUNTERPARTY, noTin);

        assertTrue(AuditMappingRuleMatcher.matches(r,
                row("DEBIT", null, "შვიდი  ტრეიდ   გრუპი", "სხვა", "გადარიცხვა")),
                "whitespace differences are not different counterparties");
        assertFalse(AuditMappingRuleMatcher.matches(r,
                row("DEBIT", null, "შვიდი ტრეიდ გრუპი 2", "სხვა", "გადარიცხვა")));
    }

    @Test
    void aRevokedRuleMatchesNothing() {
        AuditMappingRuleDto r = rule(MappingRuleCriterion.COUNTERPARTY);
        r.setActive(false);

        assertFalse(AuditMappingRuleMatcher.matches(r, SUBJECT),
                "withdrawing a rule must stop it acting, including on its own source row");
    }

    @Test
    void everyCriterionExplainsItselfInPlainLanguage() {
        for (MappingRuleCriterion criterion : MappingRuleCriterion.values()) {
            String explanation = AuditMappingRuleMatcher.explain(criterion, SUBJECT);
            assertTrue(explanation != null && explanation.length() > 15,
                    criterion + " must be explainable before the user confirms it");
            assertTrue(explanation.startsWith("Every"),
                    "the explanation should state the reach: " + explanation);
        }
    }

    @Test
    void criteriaThatCannotBeBuiltFromARowAreNotOffered() {
        AuditSourceRowDto bare = row("DEBIT", null, null, null, null);

        for (MappingRuleCriterion criterion : MappingRuleCriterion.values()) {
            assertFalse(AuditMappingRuleMatcher.isApplicable(criterion, bare),
                    criterion + " has nothing to key on here and must not be offered");
        }
    }

    @Test
    void theRuleBuiltFromARowAlwaysCatchesThatRow() {
        for (MappingRuleCriterion criterion : MappingRuleCriterion.values()) {
            assertTrue(AuditMappingRuleMatcher.matches(rule(criterion), SUBJECT),
                    criterion + " must at minimum catch the transaction it was built from");
        }
        assertEquals(4, MappingRuleCriterion.values().length);
    }
}
