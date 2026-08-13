package ge.tastyerp.payment.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the user-editable settings the audit layer needs from config-service.
 *
 * <p>Deliberately the <b>same endpoints</b> {@code AuditControlService} already
 * uses, so the audit views and the existing dashboard cannot drift onto
 * different write-off percentages or category assignments.</p>
 *
 * <p>Every call degrades to an empty overlay rather than failing: a config outage
 * must not take the audit dashboard down, and an empty overlay falls back to the
 * documented defaults.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditConfigClient {

    /** Field name matches the bean name "internalRestTemplate" for by-name resolution. */
    private final RestTemplate internalRestTemplate;

    @Value("${config.service.url:http://config-service:8888}")
    private String configServiceUrl;

    /** Category -> write-off percentage (whole percent, e.g. 28). */
    @SuppressWarnings("unchecked")
    public Map<String, BigDecimal> writeOffRates() {
        Map<String, BigDecimal> rates = new HashMap<>();
        try {
            Map<String, Object> response = internalRestTemplate
                    .getForObject(configServiceUrl + "/api/config/write-off-rates", Map.class);
            if (response != null && response.get("data") instanceof List) {
                for (Map<String, Object> row : (List<Map<String, Object>>) response.get("data")) {
                    Object category = row.get("category");
                    Object percent = row.get("percent");
                    if (category != null && percent != null) {
                        rates.put(String.valueOf(category), new BigDecimal(String.valueOf(percent)));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not fetch write-off rates; falling back to the 28% default: {}",
                    e.getMessage());
        }
        return rates;
    }

    /** Product name -> user-assigned category override. */
    @SuppressWarnings("unchecked")
    public Map<String, String> categoryOverrides() {
        Map<String, String> overrides = new HashMap<>();
        try {
            Map<String, Object> response = internalRestTemplate
                    .getForObject(configServiceUrl + "/api/config/product-categories", Map.class);
            if (response != null && response.get("data") instanceof List) {
                for (Map<String, Object> row : (List<Map<String, Object>>) response.get("data")) {
                    Object name = row.get("productName");
                    Object category = row.get("category");
                    if (name != null && category != null) {
                        overrides.put(String.valueOf(name).trim().toLowerCase(),
                                String.valueOf(category));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not fetch product category overrides: {}", e.getMessage());
        }
        return overrides;
    }
}
