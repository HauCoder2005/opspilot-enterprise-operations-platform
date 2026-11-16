package dev.opspilot.dtos;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Generic API payload with optional, typed metadata.
 */
public record ApiResponse<T, M>(
        T data,
        @JsonInclude(JsonInclude.Include.NON_NULL) M meta
) {
}
