package dev.opspilot.controllers;

import dev.opspilot.security.filters.RequestIdFilter;
import dev.opspilot.services.auth.ClientInfo;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;

/** Trích thông tin kết nối của request cho audit, dùng chung giữa các controller. */
public final class ClientRequests {

    private ClientRequests() {
    }

    /** IP do servlet container cung cấp (không đọc header client tự đặt), User-Agent và trace id do backend sinh. */
    public static ClientInfo clientInfo(HttpServletRequest request) {
        return new ClientInfo(request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT),
                RequestIdFilter.currentTraceId(request));
    }
}
