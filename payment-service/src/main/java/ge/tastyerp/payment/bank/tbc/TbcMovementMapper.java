package ge.tastyerp.payment.bank.tbc;

import ge.tastyerp.common.dto.payment.PaymentDto;
import ge.tastyerp.common.util.AmountUtils;
import ge.tastyerp.common.util.TinValidator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Maps TBC DBI movements onto Tasty ERP storage shapes.
 *
 * CREDIT movements whose counterparty TIN is a valid Georgian TIN become
 * {@link PaymentDto}s for the payments collection (they join the customer
 * debt rollup). ALL movements — credits and debits alike — are additionally
 * stored raw-ish in the bankTransactions collection for cash-flow tracking.
 *
 * uniqueCode: the Excel path uses date|amountCents|customerId|balanceCents,
 * where the running balance disambiguates same-day same-amount payments.
 * DBI movements carry no running balance, so the bank's document reference
 * takes that slot instead (prefixed so it can never collide with a
 * balance-cents value). The uniqueCode is the Firestore doc ID, which makes
 * re-fetching an overlap window idempotent.
 */
public final class TbcMovementMapper {

    /** Source tag on payments (joins the debt rollup with the Excel path) and the sync-state key. */
    public static final String SOURCE = "tbc";

    /**
     * Source tag on {@code bankTransactions} rows written by the DBI sync.
     *
     * <p>Distinct from the Excel mirror's {@code "tbc"} on purpose (BOR-81
     * finding B-1): both writers used to tag rows {@code "tbc"}, and the sync's
     * window replacement deleted by source+date — so enabling DBI would have
     * deleted every mirrored statement row dated in the window.</p>
     */
    public static final String BANK_ROW_SOURCE = "tbc-dbi";

    private TbcMovementMapper() {
    }

    /**
     * Map a CREDIT movement to a payment. Empty when the movement is not an
     * income or its counterparty TIN is missing/invalid (those rows still land
     * in bankTransactions — they are counted as unmatched, never silently lost).
     */
    public static Optional<PaymentDto> toPayment(BankTransaction tx, LocalDate cutoffDate, LocalDateTime uploadedAt) {
        if (!tx.isIncome()) {
            return Optional.empty();
        }
        String tin = TinValidator.normalize(tx.counterpartyInn());
        if (!TinValidator.isValid(tin)) {
            return Optional.empty();
        }
        BigDecimal amount = AmountUtils.round(tx.amount());
        return Optional.of(PaymentDto.builder()
                .uniqueCode(buildUniqueCode(tx, tin, amount))
                .customerId(tin)
                .customerName(blankToNull(tx.counterparty()))
                .paymentDate(tx.date())
                .amount(amount)
                .balance(BigDecimal.ZERO)
                .description(blankToNull(tx.description()))
                .source(SOURCE)
                .isAfterCutoff(cutoffDate == null || tx.date().isAfter(cutoffDate))
                .uploadedAt(uploadedAt)
                .build());
    }

    static String buildUniqueCode(BankTransaction tx, String tin, BigDecimal amount) {
        String reference = tx.reference() == null ? "" : tx.reference().trim();
        String disambiguator = reference.isEmpty()
                ? "h" + Integer.toHexString(safeHash(tx))
                : "ref" + reference;
        return String.join("|", tx.date().toString(), toCents(amount), tin, disambiguator);
    }

    /**
     * Deterministic {@code bankTransactions} document id for a DBI movement.
     *
     * <p>{@code tbc-dbi|date|direction|amountCents|ref…|n}. Re-fetching an overlap
     * window therefore rewrites the same documents instead of creating new ones
     * (BOR-81 finding B-2: the sync used to assign a fresh random id on every
     * run, which orphaned every human mapping the audit layer keys on the doc
     * id). Two genuinely identical same-day movements without a bank reference
     * are kept apart by {@code ordinal} (1, 2, …) — the caller counts
     * occurrences within one fetch, and DBI returns movements in a stable order.</p>
     */
    public static String bankRowId(BankTransaction tx, int ordinal) {
        String reference = tx.reference() == null ? "" : tx.reference().trim();
        String disambiguator = reference.isEmpty()
                ? "h" + Integer.toHexString(safeHash(tx))
                : "ref" + reference;
        return String.join("|", BANK_ROW_SOURCE, tx.date().toString(), tx.direction(),
                toCents(AmountUtils.round(tx.amount())), disambiguator, String.valueOf(ordinal));
    }

    /** Key that identifies "the same movement" for the ordinal count in {@link #bankRowId}. */
    public static String bankRowKey(BankTransaction tx) {
        return bankRowId(tx, 0);
    }

    /** Firestore document for the bankTransactions collection. */
    public static Map<String, Object> toBankTransactionMap(BankTransaction tx, LocalDateTime syncedAt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source", BANK_ROW_SOURCE);
        data.put("date", tx.date().toString());
        data.put("direction", tx.direction());
        data.put("amount", AmountUtils.round(tx.amount()).doubleValue());
        data.put("currency", tx.currency());
        data.put("accountNumber", tx.accountNumber());
        data.put("counterparty", blankToNull(tx.counterparty()));
        data.put("counterpartyInn", blankToNull(tx.counterpartyInn()));
        data.put("counterpartyAccount", blankToNull(tx.counterpartyAccount()));
        data.put("description", blankToNull(tx.description()));
        data.put("reference", blankToNull(tx.reference()));
        data.put("isExpense", tx.isExpense());
        data.put("isIncome", tx.isIncome());
        data.put("syncedAt", syncedAt.toString());
        return data;
    }

    private static String toCents(BigDecimal amount) {
        return String.valueOf(amount.multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValue());
    }

    private static int safeHash(BankTransaction tx) {
        return java.util.Objects.hash(tx.date(), tx.direction(), tx.amount(),
                tx.counterpartyAccount(), tx.description());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
