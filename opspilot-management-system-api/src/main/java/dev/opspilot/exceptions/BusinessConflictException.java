package dev.opspilot.exceptions;

import dev.opspilot.enums.ApiHttpStatus;

public class BusinessConflictException extends ApplicationException {

    public BusinessConflictException(String message) {
        super(ApiHttpStatus.CONFLICT, "BUSINESS_CONFLICT", message);
    }
}
