package dev.opspilot.exceptions;

import dev.opspilot.enums.ApiHttpStatus;
import dev.opspilot.enums.ErrorCode;

/**
 * Lỗi nghiệp vụ dự kiến của Authentication/IAM, mang mã ổn định từ {@link ErrorCode}.
 * Kế thừa {@link ApplicationException} để {@link GlobalExceptionHandler} trả {@code ApiErrorResponse} chuẩn.
 * Message chỉ dùng thông điệp an toàn để hiển thị; không đưa dữ liệu nhạy cảm vào đây.
 */
public class ApiException extends ApplicationException {

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode) {
        super(ApiHttpStatus.fromCode(errorCode.status().value()), errorCode.name(), errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
