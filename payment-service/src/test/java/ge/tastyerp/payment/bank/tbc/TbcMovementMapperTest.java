package ge.tastyerp.payment.bank.tbc;

import ge.tastyerp.common.dto.payment.PaymentDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TbcMovementMapperTest {

    private static final LocalDate CUTOFF = LocalDate.of(2025, 4, 29);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 13, 12, 0);

    @Test
    void creditWithValidTinBecomesPayment() {
        BankTransaction tx = credit("123456789", "REF12345");

        Optional<PaymentDto> payment = TbcMovementMapper.toPayment(tx, CUTOFF, NOW);

        assertThat(payment).isPresent();
        PaymentDto dto = payment.get();
        assertThat(dto.getCustomerId()).isEqualTo("123456789");
        assertThat(dto.getAmount()).isEqualByComparingTo("150.00");
        assertThat(dto.getSource()).isEqualTo("tbc");
        assertThat(dto.isAfterCutoff()).isTrue();
        assertThat(dto.getUniqueCode()).isEqualTo("2026-07-01|15000|123456789|refREF12345");
    }

    @Test
    void uniqueCodeFallsBackToContentHashWithoutReference() {
        BankTransaction tx = credit("123456789", "");

        Optional<PaymentDto> payment = TbcMovementMapper.toPayment(tx, CUTOFF, NOW);

        assertThat(payment).isPresent();
        assertThat(payment.get().getUniqueCode()).startsWith("2026-07-01|15000|123456789|h");
    }

    @Test
    void creditWithoutValidTinIsNotAPayment() {
        BankTransaction noTin = credit("", "REF1");
        BankTransaction badTin = credit("12345", "REF2");

        assertThat(TbcMovementMapper.toPayment(noTin, CUTOFF, NOW)).isEmpty();
        assertThat(TbcMovementMapper.toPayment(badTin, CUTOFF, NOW)).isEmpty();
    }

    @Test
    void debitIsNeverAPayment() {
        BankTransaction tx = new BankTransaction(
            LocalDate.of(2026, 7, 1), BankTransaction.DEBIT, new BigDecimal("150.00"),
            "GEL", "GE00TB0000000000000000GEL", "Supplier X", "123456789",
            "GE00TB0000000000000001GEL", "Supplier payment", "REF12345", null);

        assertThat(TbcMovementMapper.toPayment(tx, CUTOFF, NOW)).isEmpty();
    }

    @Test
    void paymentOnOrBeforeCutoffIsFlagged() {
        BankTransaction onCutoff = new BankTransaction(
            CUTOFF, BankTransaction.CREDIT, new BigDecimal("150.00"),
            "GEL", "GE00TB0000000000000000GEL", "Customer", "123456789",
            null, "payment", "REF", null);

        Optional<PaymentDto> payment = TbcMovementMapper.toPayment(onCutoff, CUTOFF, NOW);

        assertThat(payment).isPresent();
        assertThat(payment.get().isAfterCutoff()).isFalse();
    }

    @Test
    void bankTransactionMapKeepsBothDirectionsAndReceiverIds() {
        BankTransaction tx = new BankTransaction(
            LocalDate.of(2026, 7, 1), BankTransaction.DEBIT, new BigDecimal("99.99"),
            "GEL", "GE00TB0000000000000000GEL", "Supplier X", "123456789",
            "GE00TB0000000000000001GEL", "Supplier payment", "REF-TAX", "<raw/>");

        Map<String, Object> data = TbcMovementMapper.toBankTransactionMap(tx, NOW);

        assertThat(data.get("source")).isEqualTo("tbc-dbi");
        assertThat(data.get("direction")).isEqualTo("DEBIT");
        assertThat(data.get("amount")).isEqualTo(99.99);
        assertThat(data.get("counterpartyInn")).isEqualTo("123456789");
        assertThat(data.get("isExpense")).isEqualTo(true);
        assertThat(data.get("isIncome")).isEqualTo(false);
        assertThat(data).doesNotContainKey("rawPayload");
    }

    /** BOR-81 B-1/B-2: DBI bank rows must never share the Excel mirror's source tag or lose their identity between syncs. */
    @Test
    void bankRowIdsAreDeterministicAndDistinctFromTheExcelMirror() {
        BankTransaction a = credit("123456789", "REF12345");
        BankTransaction sameAgain = credit("123456789", "REF12345");
        BankTransaction noRef1 = credit("123456789", "");

        assertThat(TbcMovementMapper.BANK_ROW_SOURCE).isNotEqualTo("tbc");
        assertThat(TbcMovementMapper.bankRowId(a, 1)).isEqualTo("tbc-dbi|2026-07-01|CREDIT|15000|refREF12345|1");
        assertThat(TbcMovementMapper.bankRowId(a, 1)).isEqualTo(TbcMovementMapper.bankRowId(sameAgain, 1));
        // identical same-day movements without a reference stay apart by ordinal, not by a random id
        assertThat(TbcMovementMapper.bankRowId(noRef1, 1)).isNotEqualTo(TbcMovementMapper.bankRowId(noRef1, 2));
        assertThat(TbcMovementMapper.bankRowId(noRef1, 1)).startsWith("tbc-dbi|2026-07-01|CREDIT|15000|h");
    }

    private BankTransaction credit(String inn, String reference) {
        return new BankTransaction(
            LocalDate.of(2026, 7, 1), BankTransaction.CREDIT, new BigDecimal("150.00"),
            "GEL", "GE00TB0000000000000000GEL", "Customer Y", inn,
            null, "Invoice payment", reference, null);
    }
}
