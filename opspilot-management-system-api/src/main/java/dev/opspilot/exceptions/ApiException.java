package dev.opspilot.exceptions;

import dev.opspilot.enums.ErrorCode;

/**
 * Lỗi nghiệp vụ có mã ổn định, được {@link GlobalExceptionHandler} chuyển thành ProblemDetail.
 * Message chỉ dùng thông điệp an toàn để hiển thị; không đưa dữ liệu nhạy cảm vào đây.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
