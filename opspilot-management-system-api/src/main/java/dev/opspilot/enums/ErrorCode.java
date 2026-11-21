package dev.opspilot.enums;

import org.springframework.http.HttpStatus;

/**
 * Mã lỗi nghiệp vụ của Authentication/IAM, trả trong {@code ApiErrorResponse.code}.
 * Lỗi validation, request sai định dạng, 401/403 của Spring Security và lỗi hệ thống dùng mã chung của
 * {@code GlobalExceptionHandler}/{@code SecurityErrorHandler}. Thông điệp an toàn để hiển thị.
 */
public enum ErrorCode {
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Thông tin đăng nhập không chính xác."),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "Authentication is required"),
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
    EMPLOYEE_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy nhân viên hợp lệ để cấp tài khoản."),
    EMPLOYEE_ALREADY_HAS_ACCOUNT(HttpStatus.CONFLICT, "Nhân viên đã được cấp tài khoản."),
    ACCOUNT_IDENTIFIER_CONFLICT(HttpStatus.CONFLICT, "Username, email hoặc số điện thoại đã được sử dụng."),
    ROLE_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy vai trò hợp lệ."),
    ROLE_ASSIGNMENT_CONFLICT(HttpStatus.CONFLICT, "Vai trò đã được gán trong khoảng thời gian này."),
    PRIVILEGE_ESCALATION_NOT_ALLOWED(HttpStatus.FORBIDDEN, "Không được cấp vai trò có quyền vượt quá thẩm quyền của bạn."),
    OWNER_ASSIGNMENT_REQUIRES_APPROVAL(HttpStatus.FORBIDDEN, "Việc cấp vai trò OWNER cần quy trình được phê duyệt riêng."),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "Dữ liệu vừa được thay đổi bởi thao tác khác. Vui lòng thử lại."),
    POLICY_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Dịch vụ phân quyền chưa sẵn sàng.");

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
