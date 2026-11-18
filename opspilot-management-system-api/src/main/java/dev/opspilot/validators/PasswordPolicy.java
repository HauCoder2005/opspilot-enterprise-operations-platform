package dev.opspilot.validators;

import java.nio.charset.StandardCharsets;

/**
 * Chính sách mật khẩu tối thiểu (KAN-78). Độ phức tạp/blacklist/lịch sử mật khẩu là OPEN DECISION.
 * Giới hạn 72 byte UTF-8 vì BCrypt chỉ dùng 72 byte đầu: vượt quá sẽ khiến phần sau bị bỏ qua âm thầm.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 128;
    public static final int MAX_BCRYPT_BYTES = 72;

    private PasswordPolicy() {
    }

    public static boolean isAcceptable(String password) {
        if (password == null || password.isBlank()) {
            return false;
        }
        int length = password.codePointCount(0, password.length());
        return length >= MIN_LENGTH && length <= MAX_LENGTH
                && password.getBytes(StandardCharsets.UTF_8).length <= MAX_BCRYPT_BYTES;
    }
}
