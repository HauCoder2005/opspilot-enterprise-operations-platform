package dev.opspilot.services.auth;

/**
 * Thông tin kết nối của client, chỉ dùng cho audit và thông tin phiên.
 *
 * @param ipAddress địa chỉ IP do servlet container cung cấp (không đọc header do client tự đặt)
 * @param userAgent header User-Agent, có thể null
 * @param requestId mã request để nối audit với log ứng dụng
 */
public record ClientInfo(String ipAddress, String userAgent, String requestId) {
}
