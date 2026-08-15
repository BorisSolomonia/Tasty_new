package ge.tastyerp.payment.config;

import ge.tastyerp.common.infrastructure.RequestIdPropagatingInterceptor;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Configuration for RestTemplate beans.
 * Used for inter-service communication (calling waybill-service and config-service).
 *
 * <p>Both templates carry {@link RequestIdPropagatingInterceptor} so the
 * {@code X-Request-Id} minted for the inbound request travels to the downstream
 * service and its logs can be joined to ours.</p>
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(30))
                .additionalInterceptors(new RequestIdPropagatingInterceptor())
                .build();
    }

    /**
     * Long-timeout RestTemplate for slow internal calls (e.g. waybill aggregation).
     * Waybill aggregation fetches 300+ days from RS.ge in ~113 chunks and can take 1-3 minutes.
     */
    @Bean("internalRestTemplate")
    public RestTemplate internalRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(300))
                .additionalInterceptors(new RequestIdPropagatingInterceptor())
                .build();
    }
}
