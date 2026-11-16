package dev.opspilot.security.filters;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String TRACE_ID_MDC_KEY = "traceId";

    private static final String TRACE_ID_ATTRIBUTE = RequestIdFilter.class.getName() + ".traceId";

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String traceId = traceId(request);
        String previousTraceId = MDC.get(TRACE_ID_MDC_KEY);

        MDC.put(TRACE_ID_MDC_KEY, traceId);
        response.setHeader(REQUEST_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            restoreMdc(previousTraceId);
        }
    }

    private static String traceId(HttpServletRequest request) {
        Object existingTraceId = request.getAttribute(TRACE_ID_ATTRIBUTE);
        if (existingTraceId instanceof String traceId) {
            return traceId;
        }

        String traceId = UUID.randomUUID().toString();
        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        return traceId;
    }

    private static void restoreMdc(String previousTraceId) {
        if (previousTraceId == null) {
            MDC.remove(TRACE_ID_MDC_KEY);
        } else {
            MDC.put(TRACE_ID_MDC_KEY, previousTraceId);
        }
    }
}
