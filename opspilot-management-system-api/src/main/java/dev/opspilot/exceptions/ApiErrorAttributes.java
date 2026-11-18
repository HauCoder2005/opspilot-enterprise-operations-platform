package dev.opspilot.exceptions;

import dev.opspilot.enums.ErrorCode;
import dev.opspilot.security.filters.RequestIdFilter;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;

import java.util.Map;

/**
 * Bổ sung {@code code} và {@code requestId} cho body của {@code /error} (lỗi phát sinh ngoài DispatcherServlet,
 * vd trong filter). Không thêm message/exception/stack trace: giữ mặc định an toàn của Spring Boot.
 */
@Component
public class ApiErrorAttributes extends DefaultErrorAttributes {

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest webRequest, ErrorAttributeOptions options) {
        Map<String, Object> attributes = super.getErrorAttributes(webRequest, options);
        Object requestId = webRequest.getAttribute(RequestIdFilter.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (requestId != null) {
            attributes.put(ProblemResponses.REQUEST_ID_PROPERTY, requestId);
        }
        Object status = attributes.get("status");
        if (status instanceof Integer code && code >= 500) {
            attributes.put(ProblemResponses.CODE_PROPERTY, ErrorCode.INTERNAL_ERROR.name());
        }
        return attributes;
    }
}
