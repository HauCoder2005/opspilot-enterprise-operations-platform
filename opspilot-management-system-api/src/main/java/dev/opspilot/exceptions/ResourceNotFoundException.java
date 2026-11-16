package dev.opspilot.exceptions;

import dev.opspilot.enums.ApiHttpStatus;

public class ResourceNotFoundException extends ApplicationException {

    public ResourceNotFoundException(String message) {
        super(ApiHttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", message);
    }
}
