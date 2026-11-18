package dev.opspilot.enums;

import org.springframework.http.HttpStatus;

/**
 * Mã lỗi nghiệp vụ trả về trong thuộc tính {@code code} của {@link org.springframework.http.ProblemDetail}.
 * Thông điệp mặc định an toàn để hiển thị, không chứa dữ liệu nội bộ.
 */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Dữ liệu yêu cầu không hợp lệ."),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Không đọc được nội dung yêu cầu."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Thông tin đăng nhập không chính xác."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Yêu cầu chưa được xác thực hoặc access token không hợp lệ."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập không hợp lệ hoặc đã hết hạn. Vui lòng đăng nhập lại."),
    ACCOUNT_LOCKED(HttpStatus.FORBIDDEN, "Tài khoản đã bị khoá. Vui lòng liên hệ quản trị viên."),
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "Tài khoản đã bị vô hiệu hoá. Vui lòng liên hệ quản trị viên."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Bạn không có quyền thực hiện thao tác này."),
    CSRF_TOKEN_INVALID(HttpStatus.FORBIDDEN, "Thiếu hoặc sai CSRF token."),
    PASSWORD_CHANGE_REQUIRED(HttpStatus.FORBIDDEN, "Bạn phải đổi mật khẩu trước khi tiếp tục."),
    CURRENT_PASSWORD_INCORRECT(HttpStatus.BAD_REQUEST, "Mật khẩu hiện tại không chính xác."),
    PASSWORD_POLICY_VIOLATION(HttpStatus.BAD_REQUEST, "Mật khẩu mới phải dài 8–128 ký tự và không vượt quá 72 byte UTF-8."),
    PASSWORD_REUSE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "Mật khẩu mới phải khác mật khẩu hiện tại."),
    SELF_RESET_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "Không thể tự reset mật khẩu của chính mình; hãy dùng chức năng đổi mật khẩu."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy tài khoản."),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "Dữ liệu vừa được thay đổi bởi thao tác khác. Vui lòng thử lại."),
    POLICY_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Dịch vụ phân quyền chưa sẵn sàng."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Đã xảy ra lỗi hệ thống. Vui lòng thử lại sau.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
