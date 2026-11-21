package dev.opspilot.repositories.iam;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

@Repository
public class PolicyRepository {

    private static final String ACTIVE_ASSIGNMENT = """
             ra.revoked_at IS NULL
            AND ra.valid_from <= now()
            AND (ra.valid_to IS NULL OR ra.valid_to > now())
            """;

    private final JdbcClient jdbcClient;

    public PolicyRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public boolean has(long organizationId, long userId, String permission) {
        return jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1
                              FROM app_user u
                              JOIN role_assignment ra ON ra.organization_id = u.organization_id AND ra.user_id = u.id
                              JOIN role r ON r.organization_id = ra.organization_id AND r.id = ra.role_id
                              JOIN role_permission rp ON rp.organization_id = r.organization_id AND rp.role_id = r.id
                              JOIN permission p ON p.id = rp.permission_id
                             WHERE u.organization_id = :orgId AND u.id = :userId
                               AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
                               AND r.status = 'ACTIVE' AND r.deleted_at IS NULL
                               AND p.code = :permission AND """ + ACTIVE_ASSIGNMENT + ")")
                .param("orgId", organizationId)
                .param("userId", userId)
                .param("permission", permission)
                .query(Boolean.class)
                .single();
    }

    public boolean hasOnUnit(long organizationId, long userId, String permission, long unitId) {
        return jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1
                              FROM org_unit target
                              JOIN role_assignment ra ON ra.organization_id = target.organization_id
                              JOIN org_unit scope ON scope.organization_id = ra.organization_id AND scope.id = ra.scope_unit_id
                              JOIN role r ON r.organization_id = ra.organization_id AND r.id = ra.role_id
                              JOIN role_permission rp ON rp.organization_id = r.organization_id AND rp.role_id = r.id
                              JOIN permission p ON p.id = rp.permission_id
                              JOIN app_user u ON u.organization_id = ra.organization_id AND u.id = ra.user_id
                             WHERE target.organization_id = :orgId AND target.id = :unitId
                               AND target.deleted_at IS NULL AND target.status = 'ACTIVE'
                               AND u.id = :userId AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
                               AND r.status = 'ACTIVE' AND r.deleted_at IS NULL
                               AND p.code = :permission AND """ + ACTIVE_ASSIGNMENT + """
                               AND (target.id = scope.id OR (ra.include_descendants AND target.path <@ scope.path))
                        )
                        """)
                .param("orgId", organizationId)
                .param("unitId", unitId)
                .param("userId", userId)
                .param("permission", permission)
                .query(Boolean.class)
                .single();
    }

    public Set<Long> unitsWithPermission(long organizationId, long userId, String permission) {
        return new LinkedHashSet<>(jdbcClient.sql("""
                        SELECT DISTINCT target.id
                          FROM org_unit target
                          JOIN role_assignment ra ON ra.organization_id = target.organization_id
                          JOIN org_unit scope ON scope.organization_id = ra.organization_id AND scope.id = ra.scope_unit_id
                          JOIN role r ON r.organization_id = ra.organization_id AND r.id = ra.role_id
                          JOIN role_permission rp ON rp.organization_id = r.organization_id AND rp.role_id = r.id
                          JOIN permission p ON p.id = rp.permission_id
                          JOIN app_user u ON u.organization_id = ra.organization_id AND u.id = ra.user_id
                         WHERE target.organization_id = :orgId
                           AND target.deleted_at IS NULL AND target.status = 'ACTIVE'
                           AND u.id = :userId AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
                           AND r.status = 'ACTIVE' AND r.deleted_at IS NULL
                           AND p.code = :permission AND """ + ACTIVE_ASSIGNMENT + """
                           AND (target.id = scope.id OR (ra.include_descendants AND target.path <@ scope.path))
                         ORDER BY target.id
                        """)
                .param("orgId", organizationId)
                .param("userId", userId)
                .param("permission", permission)
                .query(Long.class)
                .list());
    }

    public Optional<Long> findPrimaryUnitForUser(long organizationId, long userId) {
        return jdbcClient.sql("""
                        SELECT ea.org_unit_id
                          FROM employee e
                          JOIN employee_assignment ea ON ea.organization_id = e.organization_id
                                                     AND ea.employee_id = e.id
                         WHERE e.organization_id = :orgId AND e.user_id = :userId
                           AND e.deleted_at IS NULL AND e.employment_status <> 'TERMINATED'
                           AND ea.is_primary AND ea.end_date IS NULL
                        """)
                .param("orgId", organizationId)
                .param("userId", userId)
                .query(Long.class)
                .optional();
    }
}
