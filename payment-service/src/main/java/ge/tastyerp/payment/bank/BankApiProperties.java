package ge.tastyerp.payment.bank;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Banking API configuration, bound from the {@code bank-api} block in
 * application.yml (which in turn reads TBC_DBI_* / BANK_SYNC_* env vars).
 *
 * TBC connectivity is TBC DBI ("Direct Bank Integration" / mygemini): a SOAP
 * service secured by mutual TLS with a client PKCS12 certificate plus a
 * WS-Security UsernameToken on every request. There is no OAuth token —
 * client-id/client-secret style credentials do not apply.
 */
@Data
@Component
@ConfigurationProperties(prefix = "bank-api")
public class BankApiProperties {

    private Tbc tbc = new Tbc();

    /** Fixed delay between scheduled syncs, in minutes. */
    private int syncIntervalMinutes = 60;

    /** Re-fetch window overlap: sync restarts this many days before the newest stored movement. */
    private int syncOverlapDays = 3;

    /** Minimum seconds between two syncs (throttle for manual + scheduled overlap). */
    private int minSyncIntervalSeconds = 120;

    /** First backfill start date (yyyy-MM-dd). Blank = day after the payment cutoff date. */
    private String syncStartDate = "";

    @Data
    public static class Tbc {
        private boolean enabled = false;
        private String endpoint = "https://secdbi.tbconline.ge/dbi/dbiService";
        private String username = "";
        private String password = "";
        private String certificatePath = "";
        private String certificateBase64 = "";
        private String certificatePassword = "";
        private String accountNumber = "";
        private String currency = "GEL";
        private int pageSize = 700;
        private int timeoutSeconds = 120;
    }
}
