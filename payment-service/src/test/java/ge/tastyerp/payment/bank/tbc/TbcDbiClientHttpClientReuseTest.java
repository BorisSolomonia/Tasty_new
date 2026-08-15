package ge.tastyerp.payment.bank.tbc;

import ge.tastyerp.payment.bank.BankApiProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * BOR-90 finding M-6 regression: the TBC SOAP client must reuse one
 * {@link HttpClient} (and therefore one parsed keystore / SSLContext) across
 * pages and syncs, rebuilding only when the certificate configuration changes.
 */
class TbcDbiClientHttpClientReuseTest {

    @Test
    @DisplayName("Repeated calls with the same certificate config build the HttpClient once")
    void reusesClientAcrossCalls() throws Exception {
        BankApiProperties properties = new BankApiProperties();
        BankApiProperties.Tbc config = properties.getTbc();
        config.setCertificatePath("/secrets/tbc.p12");
        config.setCertificatePassword("secret");
        AtomicInteger builds = new AtomicInteger();
        TbcDbiClient client = new TbcDbiClient(properties, c -> {
            builds.incrementAndGet();
            return HttpClient.newBuilder().build();
        });

        HttpClient first = client.httpClient(config);
        for (int page = 0; page < 50; page++) {
            assertSame(first, client.httpClient(config), "page " + page + " must reuse the client");
        }
        assertEquals(1, builds.get(), "the keystore/SSLContext must be parsed once, not per page");
    }

    @Test
    @DisplayName("A changed certificate configuration rebuilds the client; a changed password does not")
    void rebuildsOnlyWhenTlsIdentityChanges() throws Exception {
        BankApiProperties properties = new BankApiProperties();
        BankApiProperties.Tbc config = properties.getTbc();
        config.setCertificateBase64("QUJD");
        config.setCertificatePassword("secret");
        AtomicInteger builds = new AtomicInteger();
        TbcDbiClient client = new TbcDbiClient(properties, c -> {
            builds.incrementAndGet();
            return HttpClient.newBuilder().build();
        });

        HttpClient first = client.httpClient(config);
        config.setPassword("new-dbi-password"); // WS-Security password, not TLS identity
        assertSame(first, client.httpClient(config));
        assertEquals(1, builds.get());

        config.setCertificateBase64("REVG");
        HttpClient second = client.httpClient(config);
        assertNotSame(first, second);
        assertEquals(2, builds.get());
    }
}
