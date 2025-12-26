package ge.tastyerp.gateway.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.time.Duration;
import java.util.Optional;

/**
 * Simple in-memory rate limiter using Bucket4j.
 * For production with multiple instances, use Redis-backed rate limiting.
 */
@Component
public class RateLimiterConfig extends AbstractGatewayFilterFactory<RateLimiterConfig.Config> {

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterAccess(Duration.ofHours(1))
            .build();

    public RateLimiterConfig() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String key = resolveKey(exchange);
            Bucket bucket = buckets.get(key, k -> createBucket(config));

            if (bucket.tryConsume(1)) {
                return chain.filter(exchange);
            }

            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            return exchange.getResponse().setComplete();
        };
    }

    private String resolveKey(ServerWebExchange exchange) {
        // Prefer X-Forwarded-For first hop; fallback to X-Real-IP; then remote address.
        String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }

        String xRealIp = exchange.getRequest().getHeaders().getFirst("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp.trim();
        }

        return Optional.ofNullable(exchange.getRequest().getRemoteAddress())
                .map(addr -> addr.getAddress().getHostAddress())
                .orElse("unknown");
    }

    private Bucket createBucket(Config config) {
        Bandwidth limit = Bandwidth.classic(
                config.getCapacity(),
                Refill.greedy(config.getTokens(), Duration.ofSeconds(config.getPeriodSeconds()))
        );
        return Bucket.builder().addLimit(limit).build();
    }

    public static class Config {
        private int capacity = 100;
        private int tokens = 100;
        private int periodSeconds = 60;

        public int getCapacity() { return capacity; }
        public void setCapacity(int capacity) { this.capacity = capacity; }
        public int getTokens() { return tokens; }
        public void setTokens(int tokens) { this.tokens = tokens; }
        public int getPeriodSeconds() { return periodSeconds; }
        public void setPeriodSeconds(int periodSeconds) { this.periodSeconds = periodSeconds; }
    }
}
