package dev.opspilot.repositories.audit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Ghi thêm vào {@code audit_log} (append-only, partition theo tháng) bằng JdbcClient theo hướng dẫn schema.
 * Chạy trong giao dịch của use case gọi tới, với tenant đã được gắn (RLS WITH CHECK theo organization_id).
 */
@Repository
public class AuditLogRepository {

    private static final int USER_AGENT_MAX_LENGTH = 500;

    private final JdbcClient jdbcClient;

    public AuditLogRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void append(AuditEntry entry) {
        jdbcClient.sql("""
                        INSERT INTO audit_log (organization_id, actor_type, actor_user_id, action, entity_type,
                                               entity_id, result, reason, after_data, ip_address, user_agent, request_id)
                        VALUES (:organizationId, :actorType, :actorUserId, :action, :entityType,
                                :entityId, :result, :reason, CAST(:afterData AS jsonb), CAST(:ipAddress AS inet), :userAgent,
                                :requestId)
                        """)
                .param("organizationId", entry.organizationId())
                .param("actorType", entry.actorType().name())
                .param("actorUserId", entry.actorUserId())
                .param("action", entry.action())
                .param("entityType", entry.entityType())
                .param("entityId", entry.entityId())
                .param("result", entry.result().name())
                .param("reason", entry.reason())
                .param("afterData", entry.afterDataJson())
                .param("ipAddress", entry.ipAddress())
                .param("userAgent", truncate(entry.userAgent()))
                .param("requestId", entry.requestId())
                .update();
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= USER_AGENT_MAX_LENGTH) {
            return value;
        }
        return value.substring(0, USER_AGENT_MAX_LENGTH);
    }

    public enum ActorType { USER, AI, SYSTEM }

    public enum Result { SUCCESS, FAILURE, DENIED }

    /**
     * @param afterDataJson JSON do code tạo (không chứa mật khẩu, token hay dữ liệu client tự do)
     * @param ipAddress     địa chỉ IP dạng chuỗi hợp lệ cho kiểu INET, hoặc null
     */
    public record AuditEntry(
            long organizationId,
            ActorType actorType,
            Long actorUserId,
            String action,
            String entityType,
            Long entityId,
            Result result,
            String reason,
            String afterDataJson,
            String ipAddress,
            String userAgent,
            String requestId
    ) {
        /** Sự kiện do chính người dùng {@code userId} thực hiện trên tài khoản của họ. */
        public static AuditEntry ofUser(long organizationId, long userId, String action, Result result, String reason,
                                        String afterDataJson, String ipAddress, String userAgent, String requestId) {
            return new AuditEntry(organizationId, ActorType.USER, userId, action, "app_user", userId, result, reason,
                    afterDataJson, ipAddress, userAgent, requestId);
        }
    }
}
