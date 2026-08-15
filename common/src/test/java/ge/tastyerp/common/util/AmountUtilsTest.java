package ge.tastyerp.common.util;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class AmountUtilsTest {

    @Test
    void parseAmount_ShouldHandleNull() {
        assertEquals(BigDecimal.ZERO, AmountUtils.parseAmount(null));
    }

    @Test
    void parseAmount_ShouldHandleNumbers() {
        assertEquals(new BigDecimal("10.50"), AmountUtils.parseAmount(10.5));
        assertEquals(new BigDecimal("100.00"), AmountUtils.parseAmount(100));
    }

    @Test
    void parseAmount_ShouldHandleStrings() {
        assertEquals(new BigDecimal("10.50"), AmountUtils.parseAmount("10.50"));
        assertEquals(new BigDecimal("10.50"), AmountUtils.parseAmount("10,50")); // Comma decimal
    }

    @Test
    void parseAmount_ShouldHandleThousandsSeparators() {
        assertEquals(new BigDecimal("1000.50"), AmountUtils.parseAmount("1,000.50"));
        assertEquals(new BigDecimal("1000.00"), AmountUtils.parseAmount("1 000"));
    }

    /** BOR-81 finding B-14: the European form must not lose three orders of magnitude. */
    @Test
    void parseAmount_ShouldHandleEuropeanThousandsAndDecimal() {
        assertEquals(new BigDecimal("1234.56"), AmountUtils.parseAmount("1.234,56"));
        assertEquals(new BigDecimal("1234567.89"), AmountUtils.parseAmount("1.234.567,89"));
        assertEquals(new BigDecimal("1234567.89"), AmountUtils.parseAmount("1,234,567.89"));
        assertEquals(new BigDecimal("-1234.50"), AmountUtils.parseAmount("-1.234,5"));
    }

    @Test
    void parseAmount_ShouldHandleCurrencySymbolsAndWhitespace() {
        assertEquals(new BigDecimal("50.00"), AmountUtils.parseAmount(" 50.00 "));
        assertEquals(new BigDecimal("50.00"), AmountUtils.parseAmount("GEL 50.00"));
    }

    @Test
    void toCents_ShouldConvertCorrectly() {
        assertEquals(1050, AmountUtils.toCents(new BigDecimal("10.50")));
        assertEquals(0, AmountUtils.toCents(null));
    }

    @Test
    void fromCents_ShouldConvertCorrectly() {
        assertEquals(new BigDecimal("10.50"), AmountUtils.fromCents(1050));
    }
}
