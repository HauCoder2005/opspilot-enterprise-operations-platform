package dev.opspilot.auth;

import dev.opspilot.support.ApiClient.Response;
import dev.opspilot.support.PostgresTestDatabase;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static dev.opspilot.auth.AuthTestData.createOrganization;
import static dev.opspilot.auth.AuthTestData.uniqueOrgCode;
import static dev.opspilot.auth.AuthTestData.user;
import static org.assertj.core.api.Assertions.assertThat;

/** KAN-85 — paginated account listing constrained by permission, unit scope and PostgreSQL RLS. */
class IamUserListingIntegrationTest extends AbstractAuthIntegrationTest {

    @Test
    void listingRequiresJwtAndViewPermission() throws Exception {
        long root = unit(orgId, null, "ROOT");
        long actorId = user(orgId, unique("viewer"), PASSWORD).create();
        accountAt(orgId, root, unique("visible"), null, null, "ACTIVE");
        Response actor = api.login(orgCode, username(actorId), PASSWORD, "MOBILE");

        assertThat(api.get("/api/v1/iam/users").status()).isEqualTo(401);
        Response denied = list(actor, "");
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.code()).isEqualTo("ACCESS_DENIED");
    }

    @Test
    void listingReturnsOnlyAccountsInAuthorizedOrganizationAndUnitScope() throws Exception {
        long root = unit(orgId, null, "ROOT");
        long branchA = unit(orgId, root, "A");
        long branchB = unit(orgId, root, "B");
        long actorId = user(orgId, unique("branchviewer"), PASSWORD).create();
        grant(actorId, branchA, true, "iam.user.view");
        String visible = unique("visibleA");
        String hidden = unique("hiddenB");
        accountAt(orgId, branchA, visible, "visible@example.test", "+84901110001", "ACTIVE");
        accountAt(orgId, branchB, hidden, "hidden@example.test", "+84901110002", "ACTIVE");
        String foreignCode = uniqueOrgCode();
        long foreignOrg = createOrganization(foreignCode, "ACTIVE");
        long foreignRoot = unit(foreignOrg, null, "FOREIGN");
        String foreign = unique("foreign");
        accountAt(foreignOrg, foreignRoot, foreign, null, null, "ACTIVE");
        Response actor = api.login(orgCode, username(actorId), PASSWORD, "MOBILE");

        Response response = list(actor, "");

        assertThat(response.status()).isEqualTo(200);
        assertThat(usernames(response)).containsExactly(visible);
        assertThat(response.rawBody()).doesNotContain(hidden).doesNotContain(foreign)
                .doesNotContain("password_hash").doesNotContain("passwordHash")
                .doesNotContain("tokenVersion").doesNotContain("failedLoginCount");
        assertThat(list(actor, "?unitId=" + branchB).status()).isEqualTo(403);
        assertThat(list(actor, "?unitId=" + branchA).status()).isEqualTo(200);
    }

    @Test
    void searchStatusAndUnitFiltersAreAppliedInDatabase() throws Exception {
        long root = unit(orgId, null, "ROOT");
        long branchA = unit(orgId, root, "A");
        long branchB = unit(orgId, root, "B");
        long actorId = user(orgId, unique("filterviewer"), PASSWORD).create();
        grant(actorId, root, true, "iam.user.view");
        String alpha = unique("alpha");
        String beta = unique("beta");
        String gamma = unique("gamma");
        accountAt(orgId, branchA, alpha, "alpha-search@example.test", "+84907770001", "ACTIVE");
        accountAt(orgId, branchA, beta, "beta@example.test", "+84907770002", "LOCKED");
        accountAt(orgId, branchB, gamma, "gamma@example.test", "+84907779999", "DISABLED");
        Response actor = api.login(orgCode, username(actorId), PASSWORD, "MOBILE");

        assertThat(usernames(list(actor, "?q=" + encoded(alpha)))).containsExactly(alpha);
        assertThat(usernames(list(actor, "?q=" + encoded("alpha-search")))).containsExactly(alpha);
        assertThat(usernames(list(actor, "?q=" + encoded("+84907770002")))).containsExactly(beta);
        assertThat(usernames(list(actor, "?status=LOCKED"))).containsExactly(beta);
        assertThat(usernames(list(actor, "?unitId=" + branchB))).containsExactly(gamma);
    }

    @Test
    void paginationIsBoundedStableAndDoesNotDuplicateMultiUnitAccounts() throws Exception {
        long root = unit(orgId, null, "ROOT");
        long branch = unit(orgId, root, "BRANCH");
        long actorId = user(orgId, unique("pageviewer"), PASSWORD).create();
        grant(actorId, root, true, "iam.user.view");
        String first = unique("page1");
        String second = unique("page2");
        String third = unique("page3");
        accountAt(orgId, root, first, null, null, "ACTIVE");
        long secondEmployee = accountAt(orgId, root, second, null, null, "ACTIVE");
        AuthTestData.execute("""
                INSERT INTO employee_assignment
                    (organization_id, employee_id, org_unit_id, is_primary)
                VALUES (?, ?, ?, false)
                """, orgId, secondEmployee, branch);
        accountAt(orgId, root, third, null, null, "ACTIVE");
        Response actor = api.login(orgCode, username(actorId), PASSWORD, "MOBILE");

        Response page0 = list(actor, "?page=0&size=2");
        Response page1 = list(actor, "?page=1&size=2");

        assertThat(usernames(page0)).containsExactly(third, second);
        assertThat(usernames(page1)).containsExactly(first);
        assertThat(page0.body().get("meta").get("totalElements").asLong()).isEqualTo(3);
        assertThat(page0.body().get("meta").get("totalPages").asInt()).isEqualTo(2);
        assertThat(page0.body().get("meta").get("hasNext").asBoolean()).isTrue();
        assertThat(page1.body().get("meta").get("hasPrevious").asBoolean()).isTrue();
        assertThat(list(actor, "?size=101").status()).isEqualTo(400);
    }

    private Response list(Response actor, String query) {
        return api.get("/api/v1/iam/users" + query, "Authorization", bearer(actor));
    }

    private static List<String> usernames(Response response) {
        return response.body().get("data").valueStream().map(node -> node.get("username").asString()).toList();
    }

    private long accountAt(long organizationId, long unitId, String accountUsername,
                           String email, String phone, String status) throws SQLException {
        var builder = user(organizationId, accountUsername, PASSWORD).status(status);
        if (email != null) {
            builder.email(email);
        }
        if (phone != null) {
            builder.phone(phone);
        }
        long userId = builder.create();
        long employeeId = insertReturning("""
                INSERT INTO employee (organization_id, user_id, employee_code, full_name)
                VALUES (?, ?, ?, ?) RETURNING id
                """, organizationId, userId, unique("EMP"), "Employee " + accountUsername);
        AuthTestData.execute("""
                INSERT INTO employee_assignment (organization_id, employee_id, org_unit_id)
                VALUES (?, ?, ?)
                """, organizationId, employeeId, unitId);
        return employeeId;
    }

    private void grant(long userId, long unitId, boolean descendants, String permission) throws SQLException {
        long roleId = insertReturning("INSERT INTO role (organization_id, code, name) VALUES (?, ?, ?) RETURNING id",
                orgId, unique("VIEWER"), "Viewer");
        AuthTestData.execute("""
                INSERT INTO role_permission (organization_id, role_id, permission_id)
                SELECT ?, ?, id FROM permission WHERE code = ?
                """, orgId, roleId, permission);
        AuthTestData.execute("""
                INSERT INTO role_assignment (organization_id, user_id, role_id, scope_unit_id, include_descendants)
                VALUES (?, ?, ?, ?, ?)
                """, orgId, userId, roleId, unitId, descendants);
    }

    private long unit(long organizationId, Long parentId, String code) throws SQLException {
        return insertReturning("""
                INSERT INTO org_unit (organization_id, parent_id, path, depth, unit_type, code, name)
                VALUES (?, ?, '1', 0, ?, ?, ?) RETURNING id
                """, organizationId, parentId, parentId == null ? "COMPANY" : "BRANCH", code + unique(""), code);
    }

    private long insertReturning(String sql, Object... parameters) throws SQLException {
        try (Connection connection = PostgresTestDatabase.superuserConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private String username(long userId) throws SQLException {
        try (Connection connection = PostgresTestDatabase.superuserConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT username FROM app_user WHERE id = ?")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String unique(String prefix) {
        String value = prefix + Long.toUnsignedString(System.nanoTime(), 36);
        return value.substring(0, Math.min(30, value.length()));
    }
}
