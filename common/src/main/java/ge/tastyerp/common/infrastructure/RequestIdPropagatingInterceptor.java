package ge.tastyerp.common.infrastructure;

import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * Copies the current request's correlation id (bound to MDC by
 * {@link RequestCorrelationFilter}) onto outgoing service-to-service calls as
 * {@code X-Request-Id}, so payment-service → config-service / waybill-service
 * hops share one id.
 *
 * <p>Before BOR-82 (observability finding O-1) the filter minted and logged ids
 * but no {@code RestTemplate} forwarded them, so the downstream service logged an
 * unrelated fresh id and a wrong write-off rate on the dashboard could not be
 * joined to the config-service line that served it — only correlated by
 * wall-clock across three {@code docker logs} streams.</p>
 */
public class RequestIdPropagatingInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        String requestId = MDC.get(RequestCorrelationFilter.MDC_KEY);
        if (requestId != null && !requestId.isBlank()
                && !request.getHeaders().containsKey(RequestCorrelationFilter.HEADER)) {
            request.getHeaders().set(RequestCorrelationFilter.HEADER, requestId);
        }
        return execution.execute(request, body);
    }
}
