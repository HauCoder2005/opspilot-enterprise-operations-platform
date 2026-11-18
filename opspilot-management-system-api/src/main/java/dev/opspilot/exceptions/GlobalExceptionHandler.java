package dev.opspilot.exceptions;

import dev.opspilot.dtos.ApiErrorResponse;
import dev.opspilot.enums.ApiHttpStatus;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TRACE_ID_MDC_KEY = "traceId";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleBodyValidation(MethodArgumentNotValidException exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fieldError.getField(), safeMessage(fieldError));
        }
        exception.getBindingResult().getGlobalErrors().forEach(error ->
                errors.putIfAbsent("request", safeMessage(error)));

        log.debug("Request body validation failed");
        return response(ApiHttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid request data", errors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodValidation(
            HandlerMethodValidationException exception
    ) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (ParameterValidationResult result : exception.getParameterValidationResults()) {
            String parameterName = result.getMethodParameter().getParameterName();
            String key = parameterName != null
                    ? parameterName
                    : "argument" + result.getMethodParameter().getParameterIndex();
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                errors.putIfAbsent(key, safeMessage(error));
            }
        }

        log.debug("Handler method validation failed");
        return response(ApiHttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid request data", errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintValidation(
            ConstraintViolationException exception
    ) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : exception.getConstraintViolations()) {
            errors.putIfAbsent(violation.getPropertyPath().toString(), violation.getMessage());
        }

        log.debug("Constraint validation failed");
        return response(ApiHttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid request data", errors);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class
    })
    public ResponseEntity<ApiErrorResponse> handleInvalidRequest(Exception exception) {
        log.debug("Invalid HTTP request type={}", exception.getClass().getSimpleName());
        return response(ApiHttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid request", Map.of());
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiErrorResponse> handleMissingRoute(Exception exception) {
        log.debug("Requested resource was not found");
        return response(ApiHttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found", Map.of());
    }

    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<ApiErrorResponse> handleApplicationException(ApplicationException exception) {
        if (exception.getStatus() == ApiHttpStatus.CONFLICT) {
            log.warn("Business conflict: {}", exception.getCode());
        } else {
            log.debug("Application request rejected: {}", exception.getCode());
        }
        return response(exception.getStatus(), exception.getCode(), exception.getMessage(), Map.of());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthentication(AuthenticationException exception) {
        log.warn("Authentication required");
        return response(
                ApiHttpStatus.UNAUTHORIZED,
                "AUTHENTICATION_REQUIRED",
                "Authentication is required",
                Map.of()
        );
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException exception) {
        log.warn("Access denied");
        return response(ApiHttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access is denied", Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpectedException(Exception exception) {
        // Lỗi Spring MVC có sẵn status (405, 406, 415...): giữ đúng status thay vì biến thành 500.
        if (exception instanceof ErrorResponse errorResponse) {
            ApiHttpStatus status = standardStatus(errorResponse.getStatusCode().value());
            if (status != null && status.category() == ApiHttpStatus.Category.CLIENT_ERROR) {
                log.debug("HTTP request rejected by framework status={}", status.getCode());
                return response(status, status.name(), status.getReasonPhrase(), Map.of());
            }
        }
        log.error("Unexpected request failure type={}",
                exception.getClass().getName(), sanitizedStackTrace(exception));
        return response(
                ApiHttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "An unexpected error occurred",
                Map.of()
        );
    }

    private ResponseEntity<ApiErrorResponse> response(
            ApiHttpStatus status,
            String code,
            String message,
            Map<String, String> errors
    ) {
        ApiErrorResponse body = new ApiErrorResponse(
                status.getCode(),
                code,
                message,
                currentTraceId(),
                errors
        );
        return new ResponseEntity<>(body, status.toHttpStatusCode());
    }

    private static ApiHttpStatus standardStatus(int code) {
        try {
            return ApiHttpStatus.fromCode(code);
        } catch (IllegalArgumentException unsupported) {
            return null;
        }
    }

    private static String currentTraceId() {
        String traceId = MDC.get(TRACE_ID_MDC_KEY);
        return traceId != null ? traceId : UUID.randomUUID().toString();
    }

    private static String safeMessage(MessageSourceResolvable error) {
        String message = error.getDefaultMessage();
        return message != null ? message : "Invalid value";
    }

    private static RuntimeException sanitizedStackTrace(Exception exception) {
        RuntimeException sanitized = new RuntimeException("Sanitized unexpected exception");
        sanitized.setStackTrace(exception.getStackTrace());
        return sanitized;
    }
}
