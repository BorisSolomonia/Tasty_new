package ge.tastyerp.payment.audit;

import ge.tastyerp.common.dto.audit.ProductMovementDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingStatus;
import ge.tastyerp.common.dto.auditlayer.AuditSourceRowDto;
import ge.tastyerp.common.dto.auditlayer.AuditSourceType;
import ge.tastyerp.common.dto.waybill.WaybillType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Classification rules, checked against rows taken from the real TBC export
 * (account GE47TB7625036080100008, 9,333 rows, 2023-01-03 → 2026-08-13).
 *
 * <p>The rule that matters most: a bank withdrawal is never automatically a
 * supplier payment (BOR-89 §13). The bank proves cash left the account and
 * nothing more.</p>
 */
class AuditSuggestionEngineTest {

    private final AuditSuggestionEngine engine = new AuditSuggestionEngine();

    private static AuditSourceRowDto row(String direction, String amount, String type,
                                         String counterparty, String tin, String description) {
        return AuditSourceRowDto.builder()
                .sourceType(AuditSourceType.BANK)
                .sourceRowId("r1")
                .direction(direction)
                .amount(new BigDecimal(amount))
                .transactionType(type)
                .counterpartyName(counterparty)
                .counterpartyTin(tin)
                .description(description)
                .build();
    }

    @Test
    void cardCashWithdrawalIsNeverCalledASupplierPayment() {
        // 1,134 real rows, ₾2,110,810 — the cash channel.
        AuditMappingDto m = engine.suggest(row("DEBIT", "5000",
                AuditSuggestionEngine.TYPE_TRANSFER_OR_WITHDRAWAL,
                "TBCBank_ის  VISA/MC ბარათებით TBCBank_ის ბანკომატში", null, "თანხის გატანა"));

        assertNotNull(m);
        assertEquals(AuditCategories.CASH_WITHDRAWAL_UNRESOLVED,
                m.getSplits().get(0).getCategoryCode(),
                "cash leaving by card proves nothing about who received it");
        assertEquals(AuditMappingStatus.AUTO_MAPPED, m.getStatus());
        assertTrue(m.getSuggestionReason().contains("still unallocated"));
    }

    @Test
    void bankOwnChargesAreAutoClassifiedAsNonSupplierExpense() {
        AuditMappingDto m = engine.suggest(row("DEBIT", "6",
                AuditSuggestionEngine.TYPE_OTHER_CHARGES,
                "საკომისიო შემოსავალი - იურ", null, "საკასო მომსახურების საკომი"));

        assertEquals(AuditCategories.NON_SUPPLIER_EXPENSE, m.getSplits().get(0).getCategoryCode());
        assertEquals(AuditMappingStatus.AUTO_MAPPED, m.getStatus());
        assertEquals(95, m.getConfidence());
    }

    @Test
    void budgetTransfersAreAutoClassified() {
        AuditMappingDto m = engine.suggest(row("DEBIT", "1200",
                AuditSuggestionEngine.TYPE_BUDGET_TRANSFER, "ხაზინა", null, "გადასახადი"));

        assertEquals(AuditCategories.NON_SUPPLIER_EXPENSE, m.getSplits().get(0).getCategoryCode());
        assertEquals(AuditMappingStatus.AUTO_MAPPED, m.getStatus());
    }

    @Test
    void identifiedIncomingMoneyIsAutoClassifiedAsACustomerReceipt() {
        AuditMappingDto m = engine.suggest(row("CREDIT", "10000",
                AuditSuggestionEngine.TYPE_INCOME, "შპს სამიკიტნო-მაჭახელა", "405452567",
                "საქონლის ღირებულება"));

        assertEquals(AuditCategories.CUSTOMER_RECEIPT, m.getSplits().get(0).getCategoryCode());
        assertEquals(AuditMappingStatus.AUTO_MAPPED, m.getStatus());
    }

    @Test
    void incomingMoneyWithoutAnIdentifiedCounterpartyIsLeftAlone() {
        AuditMappingDto m = engine.suggest(row("CREDIT", "10000",
                AuditSuggestionEngine.TYPE_INCOME, "ვიღაც", null, "საქონლის ღირებულება"));

        assertNull(m, "unidentified money in is exactly what a human should look at");
    }

    /** A registry standing in for RS.ge purchase documents. */
    private static AuditSupplierRegistry registryWith(String tin, String amount) {
        return AuditSupplierRegistry.from(List.of(ProductMovementDto.builder()
                .type(WaybillType.PURCHASE)
                .counterpartyId(tin)
                .amount(new BigDecimal(amount))
                .productName("ხორცი")
                .build()));
    }

    @Test
    void paymentToACounterpartyRsGeConfirmsAsASellerIsASupplierSettlement() {
        // ₾4,553,024 of real transfers went to this counterparty, and RS.ge
        // documents ₾7,777,462 of purchases from them. That is evidence, not a
        // name pattern, so it can be classified automatically.
        AuditMappingDto m = engine.suggest(
                row("DEBIT", "20000", AuditSuggestionEngine.TYPE_TRANSFER_OR_WITHDRAWAL,
                        "შპს ერთგული ვაჟა პაპა", "404737344", "პროდუქციის საფასური"),
                registryWith("404737344", "7777462.85"));

        assertEquals(AuditCategories.SUPPLIER_BANK_PAYMENT, m.getSplits().get(0).getCategoryCode());
        assertEquals(AuditMappingStatus.AUTO_MAPPED, m.getStatus());
        assertTrue(m.getSuggestionReason().contains("purchase documents"),
                "the reason must cite the evidence: " + m.getSuggestionReason());
    }

    @Test
    void paymentToAnOrganisationThatNeverSoldAnythingIsNotASupplierSettlement() {
        // ₾65,761 really went to this counterparty across 68 payments, and RS.ge
        // has no purchase document from them at all. Calling that a supplier
        // settlement would feed the coverage control a fiction.
        AuditMappingDto m = engine.suggest(
                row("DEBIT", "500", AuditSuggestionEngine.TYPE_TRANSFER_OR_WITHDRAWAL,
                        "შპს სან პეტროლიუმ ჯორჯია", "404391136", "G-FORCE ევრო დიზელი 100ლიტრი"),
                registryWith("404737344", "7777462.85"));

        assertTrue(!AuditCategories.SUPPLIER_BANK_PAYMENT.equals(m.getSplits().get(0).getCategoryCode()),
                "no purchase document means no supplier settlement");
        assertEquals(AuditMappingStatus.SUGGESTED, m.getStatus());
        assertTrue(m.getSuggestionReason().contains("NO purchase documentation"));
        assertTrue(AuditMappingService.effectiveSplits(m).isEmpty(),
                "and until accepted it counts toward nothing");
    }

    @Test
    void withoutTheRegistryNoTransferIsEverCalledASupplierSettlement() {
        // Defensive: if the RS.ge feed is unavailable the engine must degrade to
        // claiming less, never to guessing more.
        AuditMappingDto m = engine.suggest(row("DEBIT", "20000",
                AuditSuggestionEngine.TYPE_TRANSFER_OR_WITHDRAWAL,
                "შპს ერთგული ვაჟა პაპა", "404737344", "პროდუქციის საფასური"));

        assertTrue(!AuditCategories.SUPPLIER_BANK_PAYMENT.equals(m.getSplits().get(0).getCategoryCode()));
    }

    @Test
    void theRegistryIsBuiltOnlyFromPurchaseDocuments() {
        AuditSupplierRegistry r = AuditSupplierRegistry.from(List.of(
                ProductMovementDto.builder().type(WaybillType.PURCHASE)
                        .counterpartyId("400151009").amount(new BigDecimal("725939.92")).build(),
                ProductMovementDto.builder().type(WaybillType.SALE)
                        .counterpartyId("405452567").amount(new BigDecimal("10000")).build()));

        assertTrue(r.isDocumentedSupplier("400151009"), "they sold to us");
        assertTrue(!r.isDocumentedSupplier("405452567"),
                "a customer we sold to is not a supplier we bought from");
        assertEquals(1, r.size());
    }

    @Test
    void payrollWordingIsSuggestedNotAutomatic() {
        AuditMappingDto m = engine.suggest(row("DEBIT", "5300",
                AuditSuggestionEngine.TYPE_TRANSFER_OR_WITHDRAWAL,
                "გიორგი ბიტიაშვილი", "14001020942", "ივლისის თვის ხელფასი"));

        assertEquals(AuditMappingStatus.SUGGESTED, m.getStatus(),
                "free-text description matching must never be automatic");
        assertTrue(m.getSuggestionReason().contains("free text"));
    }

    @Test
    void transferToAnIndividualWithNoRecognisableSignalIsLeftUnmapped() {
        AuditMappingDto m = engine.suggest(row("DEBIT", "3000",
                AuditSuggestionEngine.TYPE_TRANSFER_OR_WITHDRAWAL,
                "ალექსანდრე თოფურიძე 010080", "01008026584", "სამეურნეო ხარჯი"));

        assertNull(m, "an 11-digit personal tax code is not evidence of anything; "
                + "leaving it for a person beats guessing");
    }

    @Test
    void everySuggestionCarriesAReasonAndAConfidence() {
        for (AuditMappingDto m : new AuditMappingDto[]{
                engine.suggest(row("DEBIT", "6", AuditSuggestionEngine.TYPE_OTHER_CHARGES, "x", null, "")),
                engine.suggest(row("DEBIT", "100", AuditSuggestionEngine.TYPE_TRANSFER_OR_WITHDRAWAL,
                        "შპს არგო", "400151009", "პროდუქციის საფასური"))}) {
            assertNotNull(m.getSuggestionReason(), "a classification nobody can read back is not evidence");
            assertNotNull(m.getConfidence());
        }
    }

    @Test
    void tinShapeDistinguishesOrganisationsFromPeople() {
        assertTrue(AuditSuggestionEngine.isLegalEntity(null, "404737344"), "9 digits = organisation");
        assertTrue(!AuditSuggestionEngine.isLegalEntity(null, "01008026584"), "11 digits = person");
        assertTrue(AuditSuggestionEngine.isLegalEntity("შპს მითკო 2023", null), "შპს = LLC");
        assertTrue(AuditSuggestionEngine.isValidTin("405452567"));
        assertTrue(!AuditSuggestionEngine.isValidTin("12ab"));
    }

    @Test
    void documentRowsAreNotThisEnginesBusiness() {
        assertNull(engine.suggest(AuditSourceRowDto.builder()
                .sourceType(AuditSourceType.RS_GE).sourceRowId("d1")
                .amount(new BigDecimal("100")).build()));
    }
}
