package dev.opspilot;

import dev.opspilot.security.filters.RequestIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void createsBackendTraceIdAddsHeaderAndCleansMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/test");
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "client-controlled-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> traceInsideChain = new AtomicReference<>();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                traceInsideChain.set(MDC.get(RequestIdFilter.TRACE_ID_MDC_KEY)));

        assertThat(traceInsideChain.get()).isNotBlank().isNotEqualTo("client-controlled-id");
        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo(traceInsideChain.get());
        assertThat(MDC.get(RequestIdFilter.TRACE_ID_MDC_KEY)).isNull();
    }

    @Test
    void restoresPreviousMdcValueAndDoesNotReuseIdAcrossRequests() throws Exception {
        MDC.put(RequestIdFilter.TRACE_ID_MDC_KEY, "outer-trace");

        String first = invoke();
        String second = invoke();

        assertThat(first).isNotEqualTo(second);
        assertThat(MDC.get(RequestIdFilter.TRACE_ID_MDC_KEY)).isEqualTo("outer-trace");
    }

    private String invoke() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> traceInsideChain = new AtomicReference<>();
        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                traceInsideChain.set(MDC.get(RequestIdFilter.TRACE_ID_MDC_KEY)));
        return traceInsideChain.get();
    }
}
