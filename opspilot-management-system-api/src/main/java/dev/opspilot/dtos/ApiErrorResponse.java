package dev.opspilot.dtos;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Safe, stable representation of an API error.
 */
public record ApiErrorResponse(
        int status,
        String code,
        String message,
        String traceId,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, String> errors
) {

    public ApiErrorResponse {
        errors = errors == null || errors.isEmpty() ? Map.of() : Map.copyOf(errors);
    }
}
