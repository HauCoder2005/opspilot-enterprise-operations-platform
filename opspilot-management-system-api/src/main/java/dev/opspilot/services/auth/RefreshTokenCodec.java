package dev.opspilot.services.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Định dạng refresh token: {@code <organizationId>.<256 bit ngẫu nhiên base64url>}.
 *
 * <p>Phần {@code organizationId} cho phép gắn tenant vào giao dịch TRƯỚC khi tìm token (bảng {@code refresh_token}
 * chịu RLS, và lúc refresh không có JWT hợp lệ). Nó không phải bí mật và không được tin: SHA-256 được tính trên toàn
 * bộ chuỗi, nên sửa phần org khiến hash không khớp bất kỳ token nào của tenant đó.
 */
final class RefreshTokenCodec {

    private static final Pattern FORMAT = Pattern.compile("^([1-9][0-9]{0,18})\\.([A-Za-z0-9_-]{43})$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private RefreshTokenCodec() {
    }

    static String generate(long organizationId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return organizationId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static Optional<Long> organizationIdOf(String rawToken) {
        if (rawToken == null) {
            return Optional.empty();
        }
        Matcher matcher = FORMAT.matcher(rawToken);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(matcher.group(1)));
        } catch (NumberFormatException overflow) {
            return Optional.empty();
        }
    }

    static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 not available", impossible);
        }
    }
}
