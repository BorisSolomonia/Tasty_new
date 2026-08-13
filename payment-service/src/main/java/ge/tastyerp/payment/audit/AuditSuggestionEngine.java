package ge.tastyerp.payment.audit;

import ge.tastyerp.common.dto.auditlayer.AuditMappingDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingSplitDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingStatus;
import ge.tastyerp.common.dto.auditlayer.AuditSourceRowDto;
import ge.tastyerp.common.dto.auditlayer.AuditSourceType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * Automatic mapping suggestions for bank rows (BOR-89 §7).
 *
 * <h3>The line this engine will not cross</h3>
 * <p>A rule may be applied automatically only when it rests on something the
 * <b>bank itself asserts</b> — its own transaction-type column, or a structural
 * fact about the counterparty such as a card-withdrawal channel or the shape of a
 * tax number. Anything that rests on reading a free-text description is offered
 * as a {@link AuditMappingStatus#SUGGESTED suggestion} and contributes nothing to
 * any total until a person accepts it. That is the ticket's "do not silently
 * auto-map low-confidence rows", and it is why every result carries a reason
 * string: a classification whose basis cannot be read back is not audit
 * evidence.</p>
 *
 * <h3>Vocabulary</h3>
 * <p>Derived from the real TBC export (9,333 rows, 2023-01-03 → 2026-08-13),
 * not invented. Outflow types observed:</p>
 * <pre>
 *   გადარიცხვა თანხის გატანა   transfer / cash withdrawal   1,973 rows
 *   სხვა ხარჯები               other charges (bank fees)      752 rows
 *   საბიუჯეტო გადარიცხვები     budget / tax transfers          47 rows
 *   კონვერტაცია                currency conversion             34 rows
 * </pre>
 * <p>Inflows are uniformly {@code შემოსავალი} (income). Within
 * transfer/withdrawal the counterparty separates the channels: card withdrawals
 * carry TBC's own card-network description and no tax code, while supplier
 * transfers name a legal entity.</p>
 */
@Slf4j
@Component
public class AuditSuggestionEngine {

    // --- transaction types, exactly as the bank writes them ---
    static final String TYPE_TRANSFER_OR_WITHDRAWAL = "გადარიცხვა თანხის გატანა";
    static final String TYPE_OTHER_CHARGES = "სხვა ხარჯები";
    static final String TYPE_BUDGET_TRANSFER = "საბიუჯეტო გადარიცხვები";
    static final String TYPE_CONVERSION = "კონვერტაცია";
    static final String TYPE_INCOME = "შემოსავალი";

    /** TBC's own wording for its card-network cash withdrawals. */
    static final String CARD_WITHDRAWAL_MARKER = "ბარათებით";

    /** Legal-entity name prefixes: LLC, JSC, sole trader. */
    private static final List<String> ENTITY_PREFIXES = List.of("შპს", "სს ", "ი/მ", "ააიპ");

    /** Payroll wording seen in the export. Description-based, so never automatic. */
    private static final List<String> PAYROLL_WORDS =
            List.of("ხელფასი", "შრომის ანაზღაურება", "საპენსიო");

    /**
     * @return a proposed mapping for the row, or {@code null} when nothing can be
     *         said about it — leaving it unmapped for a human is a valid outcome
     *         and a better one than a guess
     */
    public AuditMappingDto suggest(AuditSourceRowDto row) {
        return suggest(row, AuditSupplierRegistry.empty());
    }

    /**
     * @param suppliers who RS.ge says actually sold to this business. A transfer
     *                  to a counterparty in this registry can be classified as a
     *                  supplier settlement automatically, because the claim rests
     *                  on a purchase document rather than on the shape of a name.
     */
    public AuditMappingDto suggest(AuditSourceRowDto row, AuditSupplierRegistry suppliers) {
        if (row == null || row.getSourceType() != AuditSourceType.BANK) {
            return null;
        }
        BigDecimal amount = row.getAmount() == null ? null : row.getAmount().abs();
        if (amount == null || amount.signum() <= 0) {
            return null;
        }
        boolean outflow = "DEBIT".equalsIgnoreCase(trim(row.getDirection()));
        String type = trim(row.getTransactionType());
        String counterparty = trim(row.getCounterpartyName());
        String tin = trim(row.getCounterpartyTin());
        String description = trim(row.getDescription());

        // --- automatic: the bank's own classification ---
        if (outflow && TYPE_OTHER_CHARGES.equals(type)) {
            return build(row, amount, AuditCategories.NON_SUPPLIER_EXPENSE, counterparty, tin,
                    AuditMappingStatus.AUTO_MAPPED, 95,
                    "The bank classifies this row as '" + TYPE_OTHER_CHARGES
                            + "' (other charges) — a bank fee, which buys no inventory.");
        }
        if (outflow && TYPE_BUDGET_TRANSFER.equals(type)) {
            return build(row, amount, AuditCategories.NON_SUPPLIER_EXPENSE, counterparty, tin,
                    AuditMappingStatus.AUTO_MAPPED, 95,
                    "The bank classifies this row as '" + TYPE_BUDGET_TRANSFER
                            + "' (budget/tax transfer), not a supplier settlement.");
        }
        if (outflow && counterparty != null && counterparty.contains(CARD_WITHDRAWAL_MARKER)) {
            // Cash left the account through a card. Where it went afterwards is
            // exactly what the operator must allocate, so it lands in the
            // unresolved bucket by design rather than being called a payment.
            return build(row, amount, AuditCategories.CASH_WITHDRAWAL_UNRESOLVED, counterparty, tin,
                    AuditMappingStatus.AUTO_MAPPED, 95,
                    "Card cash withdrawal (counterparty is TBC's own card network). "
                            + "The bank proves the cash left the account and nothing more — "
                            + "where it went is still unallocated.");
        }
        if (!outflow && TYPE_INCOME.equals(type) && isValidTin(tin)) {
            return build(row, amount, AuditCategories.CUSTOMER_RECEIPT, counterparty, tin,
                    AuditMappingStatus.AUTO_MAPPED, 90,
                    "Money in, typed '" + TYPE_INCOME + "' by the bank, from an identified "
                            + "counterparty (tax code " + tin + ").");
        }

        // --- automatic: RS.ge says this counterparty really did sell to us ---
        if (outflow && suppliers.isDocumentedSupplier(tin)) {
            return build(row, amount, AuditCategories.SUPPLIER_BANK_PAYMENT, counterparty, tin,
                    AuditMappingStatus.AUTO_MAPPED, 90,
                    "Tax code " + tin + " appears as the seller on RS.ge purchase documents "
                            + "totalling " + suppliers.documentedPurchases(tin).setScale(2,
                                    java.math.RoundingMode.HALF_UP)
                            + ", so money paid to them settles a documented purchase.");
        }

        // --- suggestions: plausible, but a person decides ---
        if (outflow && TYPE_TRANSFER_OR_WITHDRAWAL.equals(type) && isLegalEntity(counterparty, tin)) {
            return build(row, amount, AuditCategories.NON_SUPPLIER_EXPENSE, counterparty, tin,
                    AuditMappingStatus.SUGGESTED, 55,
                    "Bank transfer to an organisation that has NO purchase documentation on "
                            + "RS.ge" + (tin == null ? "" : " (tax code " + tin + ")")
                            + ". It cannot be a supplier settlement on the evidence available, "
                            + "so it is offered as a non-supplier expense — but the real "
                            + "question is what this money bought.");
        }
        if (outflow && matchesAny(description, PAYROLL_WORDS)) {
            return build(row, amount, AuditCategories.NON_SUPPLIER_EXPENSE, counterparty, tin,
                    AuditMappingStatus.SUGGESTED, 80,
                    "The payment description reads as payroll. Based on free text, "
                            + "so it needs confirming.");
        }
        if (TYPE_CONVERSION.equals(type)) {
            return build(row, amount, AuditCategories.NON_SUPPLIER_EXPENSE, counterparty, tin,
                    AuditMappingStatus.SUGGESTED, 60,
                    "Currency conversion. Not a settlement with anyone, but how it should be "
                            + "treated depends on your accounting choice.");
        }

        return null;
    }

    /**
     * A Georgian tax number: 9 digits for a legal entity, 11 for an individual.
     * Anything else is not an identification we can rely on.
     */
    static boolean isValidTin(String tin) {
        if (tin == null) {
            return false;
        }
        String digits = tin.trim();
        return (digits.length() == 9 || digits.length() == 11) && digits.chars().allMatch(Character::isDigit);
    }

    /** 9-digit tax numbers belong to organisations; so do these name prefixes. */
    static boolean isLegalEntity(String name, String tin) {
        if (tin != null && tin.trim().length() == 9 && tin.trim().chars().allMatch(Character::isDigit)) {
            return true;
        }
        if (name == null) {
            return false;
        }
        String lower = name.trim().toLowerCase(Locale.ROOT);
        return ENTITY_PREFIXES.stream().anyMatch(p -> lower.startsWith(p.trim().toLowerCase(Locale.ROOT)));
    }

    private static boolean matchesAny(String text, List<String> needles) {
        return text != null && needles.stream().anyMatch(text::contains);
    }

    private AuditMappingDto build(AuditSourceRowDto row, BigDecimal amount, String category,
                                  String counterparty, String tin,
                                  AuditMappingStatus status, int confidence, String reason) {
        return AuditMappingDto.builder()
                .sourceType(row.getSourceType())
                .sourceRowId(row.getSourceRowId())
                .sourceAmount(amount)
                .status(status)
                .confidence(confidence)
                .suggestionReason(reason)
                .splits(List.of(AuditMappingSplitDto.builder()
                        .categoryCode(category)
                        .counterpartyName(counterparty)
                        .counterpartyTin(tin)
                        .amount(amount)
                        .build()))
                .build();
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }
}
