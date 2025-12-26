package ge.tastyerp.gateway.filter;

import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Adds a simple end-to-end response time header.
 */
@Component
public class ResponseTimeFilter implements GlobalFilter, Ordered {

    private static final String ATTR_START_NANOS = ResponseTimeFilter.class.getName() + ".startNanos";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, org.springframework.cloud.gateway.filter.GatewayFilterChain chain) {
        exchange.getAttributes().put(ATTR_START_NANOS, System.nanoTime());

        return chain.filter(exchange).doFinally(signalType -> {
            Object startObj = exchange.getAttributes().get(ATTR_START_NANOS);
            if (!(startObj instanceof Long startNanos)) {
                return;
            }

            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            HttpHeaders headers = exchange.getResponse().getHeaders();
            if (!headers.containsKey("X-Response-Time")) {
                headers.add("X-Response-Time", elapsedMs + "ms");
            }
        });
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}

