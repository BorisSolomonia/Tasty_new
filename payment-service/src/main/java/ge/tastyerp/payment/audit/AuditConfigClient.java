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

    /** Available product categories, so the UI offers exactly what the ledger understands. */
    public java.util.List<String> productCategories() {
        return ge.tastyerp.common.dto.audit.ProductHierarchy.allCategories();
    }

    /**
     * Sets a product's category in the SHARED config-service store — the same
     * record {@code /audit-control} reads and writes. A product carries one
     * category globally, so this is not a per-row classification.
     */
    public void setProductCategory(String productName, String category) {
        java.util.Map<String, Object> body = new HashMap<>();
        body.put("name", productName);
        body.put("category", category);
        internalRestTemplate.put(configServiceUrl + "/api/config/product-categories", body);
    }

    /**
     * The shared "unreal / documentation-only" customer set (canonical TINs), as
     * maintained on /audit-control. Degrades to empty when config-service is
     * unreachable — the overview then says so in its notes rather than failing.
     */
    @SuppressWarnings("unchecked")
    public java.util.Set<String> unrealCustomers() {
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            Map<String, Object> response = internalRestTemplate
                    .getForObject(configServiceUrl + "/api/config/unreal-customers", Map.class);
            if (response != null && response.get("data") instanceof List) {
                for (Object id : (List<Object>) response.get("data")) {
                    if (id != null) {
                        out.add(ge.tastyerp.common.util.TinValidator.canonicalId(String.valueOf(id)));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not fetch unreal customers; treating all customers as real: {}", e.getMessage());
        }
        return out;
    }

    private final ge.tastyerp.common.util.SimpleTtlCache<String, Map<String, String>> namesCache =
            ge.tastyerp.common.util.SimpleTtlCache.named("audit.customerNames", 60_000, 1);

    /** Canonical TIN -> customer name from the customers collection (60 s cache). Empty on failure. */
    @SuppressWarnings("unchecked")
    public Map<String, String> customerNames() {
        return namesCache.getOrCompute("all", () -> {
            Map<String, String> names = new HashMap<>();
            try {
                Map<String, Object> response = internalRestTemplate
                        .getForObject(configServiceUrl + "/api/config/customers", Map.class);
                if (response != null && response.get("data") instanceof List) {
                    for (Map<String, Object> c : (List<Map<String, Object>>) response.get("data")) {
                        Object id = c.get("identification");
                        Object name = c.get("customerName");
                        if (id != null && name != null) {
                            names.put(ge.tastyerp.common.util.TinValidator.canonicalId(String.valueOf(id)),
                                    String.valueOf(name));
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Could not fetch customer names: {}", e.getMessage());
            }
            return names;
        });
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
                    // config-service serialises ProductCategoryDto as {name, category};
                    // reading "productName" here left every audit-layer override
                    // silently unapplied (BOR-92 v2). Accept both spellings.
                    Object name = row.get("name") != null ? row.get("name") : row.get("productName");
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
