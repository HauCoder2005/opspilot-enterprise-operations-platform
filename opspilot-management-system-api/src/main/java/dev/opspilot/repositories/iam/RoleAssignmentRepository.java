package dev.opspilot.repositories.iam;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Repository
public class RoleAssignmentRepository {

    private final JdbcClient jdbcClient;

    public RoleAssignmentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<RoleDetails> findActiveRole(long organizationId, long roleId) {
        List<RoleDetails> rows = jdbcClient.sql("""
                        SELECT r.id, r.code, COALESCE(array_agg(p.code ORDER BY p.code)
                            FILTER (WHERE p.code IS NOT NULL), ARRAY[]::varchar[]) AS permissions
                          FROM role r
                          LEFT JOIN role_permission rp ON rp.organization_id = r.organization_id AND rp.role_id = r.id
                          LEFT JOIN permission p ON p.id = rp.permission_id
                         WHERE r.organization_id = :orgId AND r.id = :roleId
                           AND r.status = 'ACTIVE' AND r.deleted_at IS NULL
                         GROUP BY r.id, r.code
                        """)
                .param("orgId", organizationId)
                .param("roleId", roleId)
                .query((rs, rowNum) -> new RoleDetails(
                        rs.getLong("id"), rs.getString("code"),
                        Arrays.asList((String[]) rs.getArray("permissions").getArray())))
                .list();
        return rows.stream().findFirst();
    }

    public boolean activeUserExists(long organizationId, long userId) {
        return jdbcClient.sql("""
                        SELECT EXISTS (SELECT 1 FROM app_user
                         WHERE organization_id = :orgId AND id = :userId
                           AND status = 'ACTIVE' AND deleted_at IS NULL)
                        """)
                .param("orgId", organizationId)
                .param("userId", userId)
                .query(Boolean.class)
                .single();
    }

    public AssignedRole insert(long organizationId, long userId, long roleId, long scopeUnitId,
                               boolean includeDescendants, Instant validFrom, Instant validTo,
                               String note, long actorUserId) {
        return jdbcClient.sql("""
                        INSERT INTO role_assignment (organization_id, user_id, role_id, scope_unit_id,
                                                     include_descendants, valid_from, valid_to, assigned_by, note,
                                                     created_by, updated_by)
                        VALUES (:orgId, :userId, :roleId, :scopeUnitId, :includeDescendants,
                                :validFrom, :validTo, :actorId, :note, :actorId, :actorId)
                        RETURNING id, valid_from, valid_to
                        """)
                .param("orgId", organizationId)
                .param("userId", userId)
                .param("roleId", roleId)
                .param("scopeUnitId", scopeUnitId)
                .param("includeDescendants", includeDescendants)
                .param("validFrom", OffsetDateTime.ofInstant(validFrom, ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("validTo", validTo == null ? null : OffsetDateTime.ofInstant(validTo, ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("actorId", actorUserId)
                .param("note", note, Types.VARCHAR)
                .query((rs, rowNum) -> new AssignedRole(
                        rs.getLong("id"),
                        rs.getObject("valid_from", OffsetDateTime.class).toInstant(),
                        rs.getObject("valid_to", OffsetDateTime.class) == null
                                ? null : rs.getObject("valid_to", OffsetDateTime.class).toInstant()))
                .single();
    }

    public record RoleDetails(long id, String code, List<String> permissions) {
    }

    public record AssignedRole(long id, Instant validFrom, Instant validTo) {
    }
}
