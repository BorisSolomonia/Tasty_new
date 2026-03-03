package ge.tastyerp.payment.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Configuration for RestTemplate beans.
 * Used for inter-service communication (calling waybill-service and config-service).
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(30))
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
                .build();
    }
}
