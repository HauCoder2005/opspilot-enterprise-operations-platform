package dev.opspilot.exceptions;

import dev.opspilot.enums.ApiHttpStatus;

public class InvalidBusinessOperationException extends ApplicationException {

    public InvalidBusinessOperationException(String message) {
        super(ApiHttpStatus.UNPROCESSABLE_CONTENT, "INVALID_BUSINESS_OPERATION", message);
    }
}
