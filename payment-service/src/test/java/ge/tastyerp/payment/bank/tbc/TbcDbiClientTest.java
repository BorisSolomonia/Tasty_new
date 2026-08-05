package ge.tastyerp.payment.bank.tbc;

import ge.tastyerp.payment.bank.BankApiProperties;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class TbcDbiClientTest {

    @Test
    void accountMovementsRequestStartsFromFirstPageIndexZero() {
        BankApiProperties properties = new BankApiProperties();
        BankApiProperties.Tbc config = properties.getTbc();
        config.setUsername("user");
        config.setAccountNumber("GE00TB0000000000000000GEL");
        config.setCurrency("GEL");
        TbcDbiClient client = new TbcDbiClient(properties);

        String envelope = client.buildAccountMovementsEnvelope(
            config,
            "password",
            LocalDate.of(2026, 2, 1),
            LocalDate.of(2026, 2, 2),
            TbcDbiClient.FIRST_PAGE_INDEX,
            700
        );

        assertThat(envelope).contains("<myg:pageIndex>0</myg:pageIndex>");
        assertThat(envelope).contains("<myg:pageSize>700</myg:pageSize>");
    }

    @Test
    void parseMovementsWithNestedAmountStructure() {
        String soapXml = """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:myg="http://www.mygemini.com/schemas/mygemini">
              <soapenv:Body>
                <myg:GetAccountMovementsResponseIo>
                  <myg:accountMovements>
                    <myg:accountMovementIo>
                      <myg:amount>
                        <myg:amount>123.45</myg:amount>
                        <myg:currency>GEL</myg:currency>
                      </myg:amount>
                      <myg:debitCredit>1</myg:debitCredit>
                      <myg:docDate>2026-05-24</myg:docDate>
                      <myg:partnerName>John Doe</myg:partnerName>
                      <myg:description>Invoice payment</myg:description>
                      <myg:documentKey>REF12345</myg:documentKey>
                    </myg:accountMovementIo>
                  </myg:accountMovements>
                </myg:GetAccountMovementsResponseIo>
              </soapenv:Body>
            </soapenv:Envelope>
            """;

        BankApiProperties properties = new BankApiProperties();
        BankApiProperties.Tbc config = properties.getTbc();
        config.setAccountNumber("GE00TB0000000000000000GEL");
        config.setCurrency("GEL");

        TbcDbiClient client = new TbcDbiClient(properties);
        java.util.List<BankTransaction> transactions = client.parseMovements(soapXml, config).transactions();

        assertThat(transactions).hasSize(1);
        BankTransaction tx = transactions.get(0);
        assertThat(tx.amount()).isEqualByComparingTo("123.45");
        assertThat(tx.direction()).isEqualTo("CREDIT");
        assertThat(tx.date()).isEqualTo(LocalDate.of(2026, 5, 24));
        assertThat(tx.counterparty()).isEqualTo("John Doe");
        assertThat(tx.description()).isEqualTo("Invoice payment");
        assertThat(tx.reference()).isEqualTo("REF12345");
    }

    @Test
    void parseMovementsUsesPartnerTaxCodeAsCounterpartyInn() {
        String soapXml = """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ns2="http://www.mygemini.com/schemas/mygemini">
              <soapenv:Body>
                <ns2:accountMovementIo>
                  <ns2:amount>200.00</ns2:amount>
                  <ns2:debitCredit>0</ns2:debitCredit>
                  <ns2:docDate>2026-05-20</ns2:docDate>
                  <ns2:partnerName>Supplier X</ns2:partnerName>
                  <ns2:partnerTaxCode>123456789</ns2:partnerTaxCode>
                  <ns2:partnerAccount>GE00TB0000000000000001GEL</ns2:partnerAccount>
                  <ns2:description>Supplier payment</ns2:description>
                  <ns2:documentKey>REF-TAX</ns2:documentKey>
                </ns2:accountMovementIo>
              </soapenv:Body>
            </soapenv:Envelope>
            """;

        BankApiProperties properties = new BankApiProperties();
        BankApiProperties.Tbc config = properties.getTbc();
        config.setAccountNumber("GE00TB0000000000000000GEL");
        config.setCurrency("GEL");

        TbcDbiClient client = new TbcDbiClient(properties);
        java.util.List<BankTransaction> transactions = client.parseMovements(soapXml, config).transactions();

        assertThat(transactions).hasSize(1);
        BankTransaction tx = transactions.get(0);
        assertThat(tx.direction()).isEqualTo("DEBIT");
        assertThat(tx.counterpartyInn()).isEqualTo("123456789");
        assertThat(tx.counterpartyAccount()).isEqualTo("GE00TB0000000000000001GEL");
    }

    @Test
    void parseMovementsWithFlatAmountStructureAndSuffix() {
        String soapXml = """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:myg="http://www.mygemini.com/schemas/mygemini">
              <soapenv:Body>
                <myg:accountMovementIo>
                  <myg:amount>99.99 GEL</myg:amount>
                  <myg:debitCredit>0</myg:debitCredit>
                  <myg:docDate>2026-05-20T12:00:00</myg:docDate>
                  <myg:partnerName>Jane Smith</myg:partnerName>
                  <myg:description>Monthly fee</myg:description>
                  <myg:documentKey>REF999</myg:documentKey>
                </myg:accountMovementIo>
              </soapenv:Body>
            </soapenv:Envelope>
            """;

        BankApiProperties properties = new BankApiProperties();
        BankApiProperties.Tbc config = properties.getTbc();
        config.setAccountNumber("GE00TB0000000000000000GEL");
        config.setCurrency("GEL");

        TbcDbiClient client = new TbcDbiClient(properties);
        java.util.List<BankTransaction> transactions = client.parseMovements(soapXml, config).transactions();

        assertThat(transactions).hasSize(1);
        BankTransaction tx = transactions.get(0);
        assertThat(tx.amount()).isEqualByComparingTo("99.99");
        assertThat(tx.direction()).isEqualTo("DEBIT");
        assertThat(tx.date()).isEqualTo(LocalDate.of(2026, 5, 20));
        assertThat(tx.counterparty()).isEqualTo("Jane Smith");
    }

    @Test
    void parseMovementsFiltersZeroAmounts() {
        String soapXml = """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:myg="http://www.mygemini.com/schemas/mygemini">
              <soapenv:Body>
                <myg:accountMovementIo>
                  <myg:amount>0.00</myg:amount>
                  <myg:debitCredit>1</myg:debitCredit>
                  <myg:docDate>2026-05-24</myg:docDate>
                </myg:accountMovementIo>
              </soapenv:Body>
            </soapenv:Envelope>
            """;

        BankApiProperties properties = new BankApiProperties();
        BankApiProperties.Tbc config = properties.getTbc();
        TbcDbiClient client = new TbcDbiClient(properties);
        TbcDbiClient.ParsedMovements parsed = client.parseMovements(soapXml, config);

        assertThat(parsed.transactions()).isEmpty();
        assertThat(parsed.rawCount()).isEqualTo(1);
    }
}
