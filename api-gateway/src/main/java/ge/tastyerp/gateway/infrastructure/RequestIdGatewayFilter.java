package ge.tastyerp.gateway.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Ensures every request through the gateway has an X-Request-Id header for cross-service tracing.
 */
@Slf4j
@Component
public class RequestIdGatewayFilter implements GlobalFilter, Ordered {

    private static final String HEADER = "X-Request-Id";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest req = exchange.getRequest();
        String requestId = req.getHeaders().getFirst(HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        String rid = requestId;
        ServerHttpRequest mutated = req.mutate()
                .headers(h -> h.set(HEADER, rid))
                .build();

        long startNs = System.nanoTime();
        return chain.filter(exchange.mutate().request(mutated).build())
                .doOnSuccess((ignored) -> {
                    long ms = (System.nanoTime() - startNs) / 1_000_000;
                    String path = req.getURI().getRawPath();
                    String qs = req.getURI().getRawQuery();
                    String full = path + (qs != null ? "?" + qs : "");
                    Integer status = exchange.getResponse().getStatusCode() != null
                            ? exchange.getResponse().getStatusCode().value()
                            : null;
                    exchange.getResponse().getHeaders().set(HEADER, rid);
                    log.info("{} {} -> {} ({}ms) {}", req.getMethod(), full, status, ms, rid);
                })
                .doOnError((err) -> {
                    long ms = (System.nanoTime() - startNs) / 1_000_000;
                    exchange.getResponse().getHeaders().set(HEADER, rid);
                    log.warn("{} {} -> error ({}ms) {}: {}", req.getMethod(), req.getURI(), ms, rid, err.getMessage());
                });
    }
}

