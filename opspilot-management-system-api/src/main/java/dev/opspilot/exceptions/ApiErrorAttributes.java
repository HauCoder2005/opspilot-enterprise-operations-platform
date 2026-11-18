package dev.opspilot.exceptions;

import dev.opspilot.security.filters.RequestIdFilter;
import org.slf4j.MDC;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.WebRequest;

import java.util.Map;

/**
 * Bổ sung {@code code} và {@code traceId} cho body của {@code /error} (lỗi phát sinh ngoài DispatcherServlet,
 * vd trong filter), để vẫn đối chiếu được với log. Không thêm message/exception/stack trace: giữ mặc định an toàn
 * của Spring Boot.
 */
@Component
public class ApiErrorAttributes extends DefaultErrorAttributes {

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest webRequest, ErrorAttributeOptions options) {
        Map<String, Object> attributes = super.getErrorAttributes(webRequest, options);
        String traceId = MDC.get(RequestIdFilter.TRACE_ID_MDC_KEY);
        if (traceId != null) {
            attributes.put("traceId", traceId);
        }
        if (attributes.get("status") instanceof Integer status && status >= 500) {
            attributes.put("code", "INTERNAL_ERROR");
        }
        return attributes;
    }
}
