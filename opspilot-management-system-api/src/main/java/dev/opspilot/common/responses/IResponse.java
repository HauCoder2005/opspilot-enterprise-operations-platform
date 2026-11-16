package dev.opspilot.common.responses;

import dev.opspilot.dtos.ApiResponse;
import dev.opspilot.enums.ApiHttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Objects;

/**
 * Stateless response helpers for REST controllers.
 */
public interface IResponse {

    default <T> ResponseEntity<ApiResponse<T, Void>> response(T data) {
        return response(data, ApiHttpStatus.OK);
    }

    default <T, M> ResponseEntity<ApiResponse<T, M>> response(T data, M meta) {
        return response(data, meta, ApiHttpStatus.OK);
    }

    default <T> ResponseEntity<ApiResponse<T, Void>> response(T data, ApiHttpStatus status) {
        return response(new ApiResponse<>(data, null), status);
    }

    default <T, M> ResponseEntity<ApiResponse<T, M>> response(
            T data,
            M meta,
            ApiHttpStatus status
    ) {
        return response(new ApiResponse<>(data, meta), status);
    }

    default <T, M> ResponseEntity<ApiResponse<T, M>> response(ApiResponse<T, M> body) {
        return response(body, ApiHttpStatus.OK);
    }

    default <T, M> ResponseEntity<ApiResponse<T, M>> response(
            ApiResponse<T, M> body,
            ApiHttpStatus status
    ) {
        Objects.requireNonNull(body, "body must not be null");
        requireBodyStatus(status);
        return new ResponseEntity<>(body, status.toHttpStatusCode());
    }

    default ResponseEntity<Void> response(ApiHttpStatus status) {
        requireBodylessStatus(status);
        return new ResponseEntity<>(status.toHttpStatusCode());
    }

    private static void requireBodyStatus(ApiHttpStatus status) {
        Objects.requireNonNull(status, "status must not be null");
        if (status.category() == ApiHttpStatus.Category.INFORMATIONAL) {
            throw new IllegalArgumentException("Informational status cannot complete a JSON response: " + status);
        }
        if (status.category() == ApiHttpStatus.Category.REDIRECTION) {
            throw new IllegalArgumentException("Redirection responses require dedicated header handling: " + status);
        }
        if (status == ApiHttpStatus.NO_CONTENT || status == ApiHttpStatus.RESET_CONTENT) {
            throw new IllegalArgumentException("HTTP status does not permit a JSON response body: " + status);
        }
    }

    private static void requireBodylessStatus(ApiHttpStatus status) {
        Objects.requireNonNull(status, "status must not be null");
        if (status.category() == ApiHttpStatus.Category.INFORMATIONAL) {
            throw new IllegalArgumentException("Informational status cannot complete a response: " + status);
        }
        if (status.category() == ApiHttpStatus.Category.REDIRECTION
                && status != ApiHttpStatus.NOT_MODIFIED) {
            throw new IllegalArgumentException("Redirection responses require dedicated header handling: " + status);
        }
    }
}
