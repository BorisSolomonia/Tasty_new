package ge.tastyerp.payment.audit;

import ge.tastyerp.common.dto.auditlayer.CounterpartyAliasDto;
import ge.tastyerp.common.dto.auditlayer.CounterpartyIdentitySource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Counterparty identity when the statement names someone without numbering them.
 *
 * <p>Grounded in the real TBC export: `შპს ერთგული ვაჟა პაპა` appears on 12 rows
 * carrying tax code 404737344 and on 212 rows carrying none. Treating those 212
 * as unidentified understated payments to that supplier by ₾4,553,024.</p>
 */
class AuditCounterpartyResolverTest {

    private static final String VAZHA = "შპს ერთგული ვაჟა პაპა";
    private static final String VAZHA_TIN = "404737344";

    private static AuditCounterpartyResolver learnedFromRealShape() {
        return AuditCounterpartyResolver.build(List.of(
                new AuditCounterpartyResolver.NameTin(VAZHA, VAZHA_TIN),
                new AuditCounterpartyResolver.NameTin(VAZHA, VAZHA_TIN),
                new AuditCounterpartyResolver.NameTin("შპს არგო", "400151009")), List.of());
    }

    @Test
    void aPrintedTaxCodeIsAlwaysUsedAsIs() {
        var r = learnedFromRealShape().resolve("400151009", "შპს არგო");

        assertEquals("400151009", r.tin());
        assertEquals(CounterpartyIdentitySource.DIRECT, r.source());
    }

    @Test
    void aBlankTaxCodeIsRecoveredFromTheName() {
        var r = learnedFromRealShape().resolve("  ", VAZHA);

        assertEquals(VAZHA_TIN, r.tin(), "the same name carries this code on other rows");
        assertEquals(CounterpartyIdentitySource.RESOLVED_BY_NAME, r.source());
        assertTrue(r.basis().contains(VAZHA_TIN), "the basis must be checkable: " + r.basis());
    }

    @Test
    void anAmbiguousNameIsNeverResolved() {
        // Two counterparties sharing a name is exactly when a guess does damage:
        // money would be attributed to the wrong party, invisibly.
        var resolver = AuditCounterpartyResolver.build(List.of(
                new AuditCounterpartyResolver.NameTin("გიორგი", "11111111111"),
                new AuditCounterpartyResolver.NameTin("გიორგი", "22222222222"),
                new AuditCounterpartyResolver.NameTin("გიორგი", "11111111111")), List.of());

        var r = resolver.resolve(null, "გიორგი");

        assertNull(r.tin(), "a more frequent code is still a guess");
        assertEquals(CounterpartyIdentitySource.AMBIGUOUS, r.source());
        assertTrue(r.basis().contains("more than one tax code"));
    }

    @Test
    void aManualAliasBeatsLearnedEvidence() {
        var resolver = AuditCounterpartyResolver.build(
                List.of(new AuditCounterpartyResolver.NameTin("შვიდი ტრეიდ გრუპი", "999999999")),
                List.of(CounterpartyAliasDto.builder()
                        .rawName("შვიდი ტრეიდ გრუპი")
                        .normalizedName(AuditCounterpartyResolver.normalize("შვიდი ტრეიდ გრუპი"))
                        .counterpartyTin("400308065")
                        .build()));

        var r = resolver.resolve(null, "შვიდი ტრეიდ გრუპი");

        assertEquals("400308065", r.tin(), "a person's instruction outranks inference");
        assertEquals(CounterpartyIdentitySource.MANUAL_ALIAS, r.source());
    }

    @Test
    void anUnknownNameStaysUnresolvedRatherThanBeingGuessed() {
        var r = learnedFromRealShape().resolve(null,
                "TBCBank_ის  VISA/MC ბარათებით TBCBank_ის ბანკომატებში");

        assertNull(r.tin(), "an ATM has no counterparty to find");
        assertEquals(CounterpartyIdentitySource.UNRESOLVED, r.source());
    }

    @Test
    void trailingQualifiersAfterACommaOrSemicolonAreIgnoredWhenMatching() {
        // Real forms: "შპს მაგსი, 405135946" and
        // "შპს ხორცი 2022;  არსენ გაგნიძე 57001009022".
        assertEquals(AuditCounterpartyResolver.normalize("შპს მაგსი"),
                AuditCounterpartyResolver.normalize("შპს მაგსი, 405135946"));
        assertEquals(AuditCounterpartyResolver.normalize("შპს ხორცი 2022"),
                AuditCounterpartyResolver.normalize("შპს ხორცი 2022;  არსენ გაგნიძე 57001009022"));
    }

    @Test
    void doubleSpacingDoesNotDefeatMatching() {
        assertEquals(AuditCounterpartyResolver.normalize("შპს  ერთგული   ვაჟა პაპა"),
                AuditCounterpartyResolver.normalize("შპს ერთგული ვაჟა პაპა"));
    }

    @Test
    void namesDifferingOnlyByATrailingNumberStayDistinct() {
        // Deliberately conservative: two people whose names differ by a number
        // are two people, and merging them would move money between them.
        assertTrue(!AuditCounterpartyResolver.normalize("ალექსანდრე თოფურიძე 010080")
                .equals(AuditCounterpartyResolver.normalize("ალექსანდრე თოფურიძე 990011")));
    }

    @Test
    void anEmptyResolverNeverInventsAnIdentity() {
        var r = AuditCounterpartyResolver.empty().resolve(null, VAZHA);

        assertNull(r.tin());
        assertEquals(CounterpartyIdentitySource.UNRESOLVED, r.source());
    }
}
