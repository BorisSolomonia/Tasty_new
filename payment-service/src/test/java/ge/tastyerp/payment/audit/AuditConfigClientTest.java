package ge.tastyerp.payment.audit;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * config-service serialises a product override as {@code {name, category}}.
 * The audit layer used to read {@code productName} and therefore never applied
 * a single override — /audit showed most purchases as OTHER while
 * /audit-control (which reads {@code name}) showed them classified.
 */
class AuditConfigClientTest {

    @Test
    void categoryOverridesReadTheKeyConfigServiceActuallySends() {
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.getForObject(anyString(), eq(Map.class))).thenReturn(Map.of("data", List.of(
                Map.of("name", "ღორის ტანხორცი", "category", "PORK"),
                Map.of("productName", "legacy spelling", "category", "BEEF"))));
        AuditConfigClient client = new AuditConfigClient(rest);
        ReflectionTestUtils.setField(client, "configServiceUrl", "http://config");

        Map<String, String> overrides = client.categoryOverrides();

        assertEquals("PORK", overrides.get("ღორის ტანხორცი"));
        assertEquals("BEEF", overrides.get("legacy spelling"));
    }
}
