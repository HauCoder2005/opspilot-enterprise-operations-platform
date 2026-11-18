package dev.opspilot.exceptions;

import dev.opspilot.enums.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * Chuyển lỗi thành {@code application/problem+json} kèm {@code code} và {@code requestId}.
 * Không phản hồi lại giá trị client gửi lên (có thể chứa mật khẩu), chỉ trả tên trường lỗi.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApiException(ApiException exception, HttpServletRequest request) {
        return respond(exception.errorCode(), exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<String> fields = exception.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getField)
                .distinct()
                .sorted()
                .toList();
        ResponseEntity<ProblemDetail> response =
                respond(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), request);
        response.getBody().setProperty("fields", fields);
        return response;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException exception, HttpServletRequest request) {
        return respond(ErrorCode.MALFORMED_REQUEST, ErrorCode.MALFORMED_REQUEST.defaultMessage(), request);
    }

    /**
     * Lưới an toàn cho lỗi không lường trước: log đầy đủ ở server, client chỉ nhận 500 chung, không lộ chi tiết.
     * Lỗi Spring MVC có sẵn status ({@link ErrorResponse}: 404, 405, 415...) giữ nguyên status của chúng.
     * Lỗi của Spring Security được ném lại để ExceptionTranslationFilter trả 401/403 đúng chuẩn.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) throws Exception {
        if (exception instanceof AccessDeniedException || exception instanceof AuthenticationException) {
            throw exception;
        }
        if (exception instanceof ErrorResponse errorResponse) {
            ProblemDetail body = errorResponse.getBody();
            String requestId = dev.opspilot.security.filters.RequestIdFilter.currentRequestId(request);
            if (requestId != null) {
                body.setProperty(ProblemResponses.REQUEST_ID_PROPERTY, requestId);
            }
            return ResponseEntity.status(errorResponse.getStatusCode()).headers(errorResponse.getHeaders()).body(body);
        }
        log.error("Unhandled exception", exception);
        return respond(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage(), request);
    }

    private static ResponseEntity<ProblemDetail> respond(ErrorCode code, String detail, HttpServletRequest request) {
        return ResponseEntity.status(code.status()).body(ProblemResponses.problem(code, detail, request));
    }
}
