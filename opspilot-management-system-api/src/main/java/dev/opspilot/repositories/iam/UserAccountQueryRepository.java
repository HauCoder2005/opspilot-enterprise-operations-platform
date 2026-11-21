package dev.opspilot.repositories.iam;

import dev.opspilot.dtos.iam.UserAccountResponse;
import dev.opspilot.enums.iam.UserStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public class UserAccountQueryRepository {

    private static final String VISIBLE_ACCOUNT = """
            FROM app_user u
            WHERE u.organization_id = :orgId
              AND u.deleted_at IS NULL
              AND EXISTS (
                    SELECT 1
                      FROM employee e
                      JOIN employee_assignment ea
                        ON ea.organization_id = e.organization_id
                       AND ea.employee_id = e.id
                     WHERE e.organization_id = u.organization_id
                       AND e.user_id = u.id
                       AND e.deleted_at IS NULL
                       AND ea.end_date IS NULL
                       AND ea.org_unit_id IN (:unitIds)
              )
            """;

    private final JdbcClient jdbcClient;

    public UserAccountQueryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public AccountPage find(long organizationId, Collection<Long> unitIds, String search,
                            UserStatus status, int page, int size) {
        String filters = filters(search, status);
        long total = statement("SELECT count(*) " + VISIBLE_ACCOUNT + filters,
                organizationId, unitIds, search, status)
                .query(Long.class)
                .single();
        List<UserAccountResponse> content = statement("""
                        SELECT u.id, u.username, u.email, u.phone, u.status,
                               u.must_change_password, u.created_at
                        """ + VISIBLE_ACCOUNT + filters + """

                        ORDER BY u.created_at DESC, u.id DESC
                        LIMIT :size OFFSET :offset
                        """, organizationId, unitIds, search, status)
                .param("size", size)
                .param("offset", (long) page * size)
                .query((rs, rowNum) -> new UserAccountResponse(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("email"),
                        rs.getString("phone"),
                        UserStatus.valueOf(rs.getString("status")),
                        rs.getBoolean("must_change_password"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
        return new AccountPage(content, total);
    }

    private JdbcClient.StatementSpec statement(String sql, long organizationId, Collection<Long> unitIds,
                                                String search, UserStatus status) {
        JdbcClient.StatementSpec statement = jdbcClient.sql(sql)
                .param("orgId", organizationId)
                .param("unitIds", unitIds);
        if (search != null) {
            statement = statement.param("search", search);
        }
        if (status != null) {
            statement = statement.param("status", status.name());
        }
        return statement;
    }

    private static String filters(String search, UserStatus status) {
        StringBuilder sql = new StringBuilder();
        if (search != null) {
            sql.append(" AND (position(lower(:search) in lower(u.username)) > 0"
                    + " OR position(lower(:search) in lower(coalesce(u.email, ''))) > 0"
                    + " OR position(lower(:search) in lower(coalesce(u.phone, ''))) > 0)");
        }
        if (status != null) {
            sql.append(" AND u.status = :status");
        }
        return sql.toString();
    }

    public record AccountPage(List<UserAccountResponse> content, long totalElements) {
    }
}
