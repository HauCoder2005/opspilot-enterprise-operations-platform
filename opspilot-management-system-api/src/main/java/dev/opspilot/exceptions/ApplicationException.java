package dev.opspilot.exceptions;

import dev.opspilot.enums.ApiHttpStatus;

import java.util.Objects;

/**
 * Base type for expected application failures with a safe client-facing message.
 */
public abstract class ApplicationException extends RuntimeException {

    private final ApiHttpStatus status;
    private final String code;

    protected ApplicationException(ApiHttpStatus status, String code, String message) {
        super(message);
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.code = Objects.requireNonNull(code, "code must not be null");
    }

    public ApiHttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
