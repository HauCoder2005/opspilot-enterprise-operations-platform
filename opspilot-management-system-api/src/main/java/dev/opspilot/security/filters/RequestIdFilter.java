package dev.opspilot.security.filters;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gắn mã request cho mọi request (kể cả lượt ERROR dispatch) để đối chiếu log, audit và response lỗi.
 * Nhận {@code X-Request-Id} của client/proxy nếu đúng định dạng an toàn, nếu không thì sinh UUID mới.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
    public static final String MDC_KEY = "requestId";

    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    /** Mã request của request hiện tại, hoặc null nếu filter chưa chạy (vd test gọi trực tiếp). */
    public static String currentRequestId(ServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value instanceof String id ? id : null;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = currentRequestId(request);
        if (requestId == null) {
            String incoming = request.getHeader(HEADER);
            requestId = incoming != null && SAFE_ID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
            request.setAttribute(ATTRIBUTE, requestId);
        }
        response.setHeader(HEADER, requestId);
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }
}
