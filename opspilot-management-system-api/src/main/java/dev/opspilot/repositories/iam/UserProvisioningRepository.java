package dev.opspilot.repositories.iam;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class UserProvisioningRepository {

    private final JdbcClient jdbcClient;

    public UserProvisioningRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<EmployeeAccountTarget> findEmployeeForUpdate(long organizationId, long employeeId) {
        return jdbcClient.sql("""
                        SELECT e.id, e.user_id, e.full_name, e.employment_status, ea.org_unit_id
                          FROM employee e
                          LEFT JOIN employee_assignment ea ON ea.organization_id = e.organization_id
                                                          AND ea.employee_id = e.id
                                                          AND ea.is_primary AND ea.end_date IS NULL
                         WHERE e.organization_id = :orgId AND e.id = :employeeId AND e.deleted_at IS NULL
                         FOR UPDATE OF e
                        """)
                .param("orgId", organizationId)
                .param("employeeId", employeeId)
                .query((rs, rowNum) -> new EmployeeAccountTarget(
                        rs.getLong("id"),
                        rs.getObject("user_id", Long.class),
                        rs.getString("full_name"),
                        rs.getString("employment_status"),
                        rs.getObject("org_unit_id", Long.class)))
                .optional();
    }

    public CreatedUser insertUser(long organizationId, String username, String email, String phone,
                                  String passwordHash, String displayName, long actorUserId) {
        return jdbcClient.sql("""
                        INSERT INTO app_user (organization_id, username, email, phone, password_hash, display_name,
                                              must_change_password, created_by, updated_by)
                        VALUES (:orgId, :username, :email, :phone, :passwordHash, :displayName,
                                TRUE, :actorId, :actorId)
                        RETURNING id, username
                        """)
                .param("orgId", organizationId)
                .param("username", username)
                .param("email", email)
                .param("phone", phone)
                .param("passwordHash", passwordHash)
                .param("displayName", displayName)
                .param("actorId", actorUserId)
                .query((rs, rowNum) -> new CreatedUser(rs.getLong("id"), rs.getString("username")))
                .single();
    }

    public boolean linkEmployee(long organizationId, long employeeId, long userId, long actorUserId) {
        return jdbcClient.sql("""
                        UPDATE employee
                           SET user_id = :userId, updated_at = now(), updated_by = :actorId, version = version + 1
                         WHERE organization_id = :orgId AND id = :employeeId
                           AND user_id IS NULL AND deleted_at IS NULL AND employment_status <> 'TERMINATED'
                        """)
                .param("userId", userId)
                .param("actorId", actorUserId)
                .param("orgId", organizationId)
                .param("employeeId", employeeId)
                .update() == 1;
    }

    public record EmployeeAccountTarget(long id, Long userId, String displayName, String employmentStatus,
                                        Long primaryUnitId) {
    }

    public record CreatedUser(long id, String username) {
    }
}
