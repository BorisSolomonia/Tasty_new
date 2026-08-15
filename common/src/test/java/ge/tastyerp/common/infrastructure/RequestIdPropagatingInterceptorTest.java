package ge.tastyerp.common.infrastructure;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/** BOR-82 observability finding O-1: the correlation id must survive the service hop. */
class RequestIdPropagatingInterceptorTest {

    private final RequestIdPropagatingInterceptor interceptor = new RequestIdPropagatingInterceptor();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private String headerSentWithMdc(String requestId, String presetHeader) throws IOException {
        if (requestId != null) {
            MDC.put(RequestCorrelationFilter.MDC_KEY, requestId);
        }
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, "http://config-service/api/config/settings");
        if (presetHeader != null) {
            request.getHeaders().set(RequestCorrelationFilter.HEADER, presetHeader);
        }
        AtomicReference<String> seen = new AtomicReference<>();
        ClientHttpRequestExecution execution = (req, body) -> {
            seen.set(req.getHeaders().getFirst(RequestCorrelationFilter.HEADER));
            return mock(ClientHttpResponse.class);
        };
        interceptor.intercept(request, new byte[0], execution);
        return seen.get();
    }

    @Test
    @DisplayName("The MDC request id is forwarded as X-Request-Id")
    void forwardsMdcRequestId() throws IOException {
        assertEquals("req-123", headerSentWithMdc("req-123", null));
    }

    @Test
    @DisplayName("No MDC id (e.g. a scheduled job) sends no header rather than a blank one")
    void noMdcNoHeader() throws IOException {
        assertNull(headerSentWithMdc(null, null));
    }

    @Test
    @DisplayName("An explicitly set header wins over the MDC value")
    void explicitHeaderWins() throws IOException {
        assertEquals("explicit", headerSentWithMdc("req-123", "explicit"));
    }
}
