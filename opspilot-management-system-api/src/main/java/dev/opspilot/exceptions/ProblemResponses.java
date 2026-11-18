package dev.opspilot.exceptions;

import dev.opspilot.enums.ErrorCode;
import dev.opspilot.security.filters.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tạo response lỗi thống nhất dạng {@code application/problem+json} với {@code code} và {@code requestId},
 * dùng cho cả MVC ({@link GlobalExceptionHandler}) lẫn các handler của Spring Security (ngoài MVC).
 */
@Component
public class ProblemResponses {

    static final String CODE_PROPERTY = "code";
    static final String REQUEST_ID_PROPERTY = "requestId";

    private final JsonMapper jsonMapper;

    public ProblemResponses(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public static ProblemDetail problem(ErrorCode code, String detail, HttpServletRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(code.status(), detail);
        body.setProperty(CODE_PROPERTY, code.name());
        String requestId = request == null ? null : RequestIdFilter.currentRequestId(request);
        if (requestId != null) {
            body.setProperty(REQUEST_ID_PROPERTY, requestId);
        }
        if (request != null) {
            body.setInstance(URI.create(request.getRequestURI()));
        }
        return body;
    }

    /** Ghi trực tiếp vào servlet response (dùng ngoài DispatcherServlet, vd AuthenticationEntryPoint). */
    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code) throws IOException {
        ProblemDetail problem = problem(code, code.defaultMessage(), request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", problem.getType() == null ? "about:blank" : problem.getType().toString());
        body.put("title", problem.getTitle());
        body.put("status", problem.getStatus());
        body.put("detail", problem.getDetail());
        body.put("instance", problem.getInstance() == null ? null : problem.getInstance().toString());
        if (problem.getProperties() != null) {
            body.putAll(problem.getProperties());
        }
        byte[] bytes = jsonMapper.writeValueAsBytes(body);
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }
}
