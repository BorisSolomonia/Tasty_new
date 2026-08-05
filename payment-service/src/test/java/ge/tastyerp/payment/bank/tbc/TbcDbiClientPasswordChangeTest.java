package ge.tastyerp.payment.bank.tbc;

import ge.tastyerp.payment.bank.BankApiProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TbcDbiClientPasswordChangeTest {

    @Test
    void changePasswordRejectsMissingOtpBeforeCallingTbc() {
        TbcDbiClient client = new TbcDbiClient(validProperties());

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> client.changePassword("", "NewPass1!", null)
        );

        assertEquals("TBC Digipass/Nonce code is required for password change.", exception.getMessage());
    }

    private BankApiProperties validProperties() {
        BankApiProperties properties = new BankApiProperties();
        BankApiProperties.Tbc tbc = properties.getTbc();
        tbc.setEnabled(true);
        tbc.setEndpoint("https://secdbi.tbconline.ge/dbi/dbiService");
        tbc.setUsername("test-user");
        tbc.setPassword("TempPass1!");
        tbc.setCertificateBase64("placeholder");
        tbc.setCertificatePassword("cert-pass");
        tbc.setAccountNumber("GE00TB0000000000000000");
        tbc.setCurrency("GEL");
        tbc.setPageSize(700);
        tbc.setTimeoutSeconds(120);
        return properties;
    }
}
