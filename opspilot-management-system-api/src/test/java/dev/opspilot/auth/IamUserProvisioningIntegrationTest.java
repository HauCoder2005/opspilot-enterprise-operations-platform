package dev.opspilot.auth;

import dev.opspilot.support.ApiClient.Response;
import dev.opspilot.support.PostgresTestDatabase;
import dev.opspilot.security.authentication.OpsPilotPrincipal;
import dev.opspilot.services.iam.IPolicyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static dev.opspilot.auth.AuthTestData.createOrganization;
import static dev.opspilot.auth.AuthTestData.queryInt;
import static dev.opspilot.auth.AuthTestData.uniqueOrgCode;
import static dev.opspilot.auth.AuthTestData.user;
import static dev.opspilot.auth.LoginIntegrationTest.concurrently;
import static org.assertj.core.api.Assertions.assertThat;

/** KAN-81/85/87 — policy scope, employee account provisioning and privilege-safe role assignment. */
class IamUserProvisioningIntegrationTest extends AbstractAuthIntegrationTest {

    @Autowired
    private IPolicyService policyService;

    @Test
    void policySupportsGlobalScopedAndEnumeratedPermissionChecks() throws Exception {
        long root = unit(orgId, null, "ROOT");
        long branch = unit(orgId, root, "BRANCH");
        long child = unit(orgId, branch, "CHILD");
        long sibling = unit(orgId, root, "SIBLING");
        long actorId = user(orgId, unique("policy"), PASSWORD).create();
        grant(actorId, branch, true, "iam.user.manage");
        OpsPilotPrincipal actor = new OpsPilotPrincipal(actorId, orgId, 0, false, "test");

        assertThat(policyService.has(actor, "iam.user.manage")).isTrue();
        assertThat(policyService.hasOnUnit(actor, "iam.user.manage", branch)).isTrue();
        assertThat(policyService.hasOnUnit(actor, "iam.user.manage", child)).isTrue();
        assertThat(policyService.hasOnUnit(actor, "iam.user.manage", sibling)).isFalse();
        assertThat(policyService.unitsWithPermission(actor, "iam.user.manage"))
                .containsExactlyInAnyOrder(branch, child);
    }

    @Test
    void accountCreationRequiresJwtAuthentication() throws Exception {
        long root = unit(orgId, null, "ROOT");
        long employee = employee(orgId, root, unique("NOAUTH"));

        Response response = api.post("/api/v1/iam/users", dev.opspilot.support.ApiClient.json(Map.of(
                "employeeId", employee,
                "username", unique("unauthenticated"))));

        assertThat(response.status()).isEqualTo(401);
    }

    @Test
    void scopedAdminCreatesEmployeeAccountWithOneTimePassword() throws Exception {
        long root = unit(orgId, null, "ROOT");
        long branch = unit(orgId, root, "BRANCH");
        long adminId = user(orgId, unique("admin"), PASSWORD).create();
        grant(adminId, branch, true, "iam.user.manage");
        long employee = employee(orgId, branch, unique("EMP"));
        Response admin = api.login(orgCode, username(adminId), PASSWORD, "MOBILE");

        Response created = createUser(admin, employee, unique("account"), "new@example.test", "+84901111111");

        assertThat(created.status()).isEqualTo(201);
        long userId = created.body().get("userId").asLong();
        String temporaryPassword = created.text("temporaryPassword");
        assertThat(temporaryPassword).hasSize(16);
        assertThat(created.rawBody()).doesNotContain("passwordHash").doesNotContain("password_hash").doesNotContain("{bcrypt}");
        assertThat(created.header("Cache-Control")).contains("no-store");
        assertThat(queryInt("SELECT count(*) FROM employee WHERE id = ? AND user_id = ?", employee, userId)).isEqualTo(1);
        assertThat(queryInt("SELECT count(*) FROM app_user WHERE id = ? AND must_change_password", userId)).isEqualTo(1);
        Response firstLogin = api.login(orgCode, created.text("username"), temporaryPassword, "WEB");
        assertThat(firstLogin.status()).isEqualTo(200);
        assertThat(firstLogin.body().get("mustChangePassword").asBoolean()).isTrue();
        assertThat(queryInt("SELECT count(*) FROM audit_log WHERE organization_id = ? AND action = 'iam.user.create'"
                + " AND entity_id = ? AND result = 'SUCCESS'", orgId, userId)).isEqualTo(1);
    }

    @Test
    void userWithoutPermissionAndManagerOutsideScopeAreDenied() throws Exception {
        long root = unit(orgId, null, "ROOT");
        long branchA = unit(orgId, root, "A");
        long branchB = unit(orgId, root, "B");
        long noPermissionId = user(orgId, unique("none"), PASSWORD).create();
        long managerId = user(orgId, unique("manager"), PASSWORD).create();
        grant(managerId, branchA, true, "iam.user.manage");
        long employeeA = employee(orgId, branchA, unique("EA"));
        long employeeB = employee(orgId, branchB, unique("EB"));

        Response noPermission = api.login(orgCode, username(noPermissionId), PASSWORD, "MOBILE");
        Response manager = api.login(orgCode, username(managerId), PASSWORD, "MOBILE");

        assertThat(createUser(noPermission, employeeA, unique("denied"), null, null).status()).isEqualTo(403);
        assertThat(createUser(manager, employeeB, unique("outside"), null, null).status()).isEqualTo(403);
        assertThat(createUser(manager, employeeA, unique("inside"), null, null).status()).isEqualTo(201);
    }

    @Test
    void cannotProvisionEmployeeFromAnotherOrganization() throws Exception {
        long root = unit(orgId, null, "ROOT");
        long adminId = user(orgId, unique("crossadmin"), PASSWORD).create();
        grant(adminId, root, true, "iam.user.manage");
        String otherCode = uniqueOrgCode();
        long otherOrg = createOrganization(otherCode, "ACTIVE");
        long otherRoot = unit(otherOrg, null, "ROOT2");
        long foreignEmployee = employee(otherOrg, otherRoot, unique("FOREIGN"));
        Response admin = api.login(orgCode, username(adminId), PASSWORD, "MOBILE");

        Response response = createUser(admin, foreignEmployee, unique("cross"), null, null);

        assertThat(response.status()).isEqualTo(404);
        assertThat(queryInt("SELECT count(*) FROM employee WHERE id = ? AND user_id IS NULL", foreignEmployee)).isEqualTo(1);
    }

    @Test
    void duplicateUsernameEmailAndPhoneReturnConflict() throws Exception {
        long root = unit(orgId, null, "ROOT");
        long adminId = user(orgId, unique("duplicateadmin"), PASSWORD).create();
        grant(adminId, root, true, "iam.user.manage");
        String existingUsername = unique("existing");
        user(orgId, existingUsername, PASSWORD).email("used@example.test").phone("+84902222222").create();
        Response admin = api.login(orgCode, username(adminId), PASSWORD, "MOBILE");

        Response duplicateUsername = createUser(admin, employee(orgId, root, unique("E1")), existingUsername, null, null);
        Response duplicateEmail = createUser(admin, employee(orgId, root, unique("E2")), unique("u2"), "used@example.test", null);
        Response duplicatePhone = createUser(admin, employee(orgId, root, unique("E3")), unique("u3"), null, "+84902222222");

        assertThat(List.of(duplicateUsername, duplicateEmail, duplicatePhone))
                .extracting(Response::status).containsOnly(409);
        assertThat(List.of(duplicateUsername, duplicateEmail, duplicatePhone))
                .extracting(Response::code).containsOnly("ACCOUNT_IDENTIFIER_CONFLICT");
    }

    @Test
    void concurrentProvisioningCreatesOnlyOneAccountForEmployee() throws Exception {
        long root = unit(orgId, null, "ROOT");
        long adminId = user(orgId, unique("raceadmin"), PASSWORD).create();
        grant(adminId, root, true, "iam.user.manage");
        long employee = employee(orgId, root, unique("RACE"));
        Response admin = api.login(orgCode, username(adminId), PASSWORD, "MOBILE");

        List<Response> responses = concurrently(2,
                () -> createUser(admin, employee, unique("raceuser"), null, null));

        assertThat(responses).extracting(Response::status).containsExactlyInAnyOrder(201, 409);
        assertThat(queryInt("SELECT count(*) FROM app_user u JOIN employee e ON e.user_id = u.id WHERE e.id = ?", employee))
                .isEqualTo(1);
    }

    @Test
    void roleAssignmentEnforcesScopePermissionSubsetAndOwnerGuard() throws Exception {
        long root = unit(orgId, null, "ROOT");
        long branch = unit(orgId, root, "BRANCH");
        long actorId = user(orgId, unique("roleadmin"), PASSWORD).create();
        grant(actorId, branch, true, "iam.role.assign", "iam.user.view");
        long targetId = user(orgId, unique("target"), PASSWORD).create();
        long allowedRole = role(orgId, unique("LIMITED"), "iam.user.view");
        long elevatedRole = role(orgId, unique("ELEVATED"), "audit.view");
        long ownerRole = role(orgId, "OWNER", "iam.user.view");
        long otherOrg = createOrganization(uniqueOrgCode(), "ACTIVE");
        long foreignRole = role(otherOrg, unique("FOREIGN"), "iam.user.view");
        Response actor = api.login(orgCode, username(actorId), PASSWORD, "MOBILE");

        Response assigned = assignRole(actor, targetId, allowedRole, branch);
        Response elevated = assignRole(actor, targetId, elevatedRole, branch);
        Response owner = assignRole(actor, targetId, ownerRole, branch);
        Response foreign = assignRole(actor, targetId, foreignRole, branch);

        assertThat(assigned.status()).isEqualTo(201);
        assertThat(elevated.status()).isEqualTo(403);
        assertThat(elevated.code()).isEqualTo("PRIVILEGE_ESCALATION_NOT_ALLOWED");
        assertThat(owner.status()).isEqualTo(403);
        assertThat(owner.code()).isEqualTo("OWNER_ASSIGNMENT_REQUIRES_APPROVAL");
        assertThat(foreign.status()).isEqualTo(404);
        assertThat(foreign.code()).isEqualTo("ROLE_NOT_FOUND");
        assertThat(queryInt("SELECT count(*) FROM role_assignment WHERE id = ? AND organization_id = ?",
                assigned.body().get("assignmentId").asLong(), orgId)).isEqualTo(1);
    }

    @Test
    void concurrentOverlappingRoleAssignmentsCreateOnlyOneGrant() throws Exception {
        long root = unit(orgId, null, "ROOT");
        long actorId = user(orgId, unique("concurrentgrant"), PASSWORD).create();
        grant(actorId, root, true, "iam.role.assign", "iam.user.view");
        long targetId = user(orgId, unique("concurrenttarget"), PASSWORD).create();
        long roleId = role(orgId, unique("CONCURRENT"), "iam.user.view");
        Response actor = api.login(orgCode, username(actorId), PASSWORD, "MOBILE");

        List<Response> responses = concurrently(2, () -> assignRole(actor, targetId, roleId, root));

        assertThat(responses).extracting(Response::status).containsExactlyInAnyOrder(201, 409);
        assertThat(queryInt("SELECT count(*) FROM role_assignment WHERE organization_id = ? AND user_id = ?"
                + " AND role_id = ? AND scope_unit_id = ?", orgId, targetId, roleId, root)).isEqualTo(1);
    }

    private Response createUser(Response actor, long employeeId, String accountUsername, String email, String phone) {
        return api.post("/api/v1/iam/users", dev.opspilot.support.ApiClient.json(Map.of(
                        "employeeId", employeeId,
                        "username", accountUsername,
                        "email", email == null ? "" : email,
                        "phone", phone == null ? "" : phone)),
                "Authorization", bearer(actor));
    }

    private Response assignRole(Response actor, long targetUserId, long roleId, long unitId) {
        return api.post("/api/v1/iam/users/" + targetUserId + "/role-assignments",
                dev.opspilot.support.ApiClient.json(Map.of(
                        "roleId", roleId,
                        "scopeUnitId", unitId,
                        "includeDescendants", true,
                        "validFrom", Instant.now().toString())),
                "Authorization", bearer(actor));
    }

    private long unit(long organizationId, Long parentId, String code) throws SQLException {
        return insertReturning("""
                INSERT INTO org_unit (organization_id, parent_id, path, depth, unit_type, code, name)
                VALUES (?, ?, '1', 0, ?, ?, ?) RETURNING id
                """, organizationId, parentId, parentId == null ? "COMPANY" : "BRANCH", code + unique(""), code);
    }

    private long employee(long organizationId, long unitId, String code) throws SQLException {
        long employeeId = insertReturning("""
                INSERT INTO employee (organization_id, employee_code, full_name) VALUES (?, ?, ?) RETURNING id
                """, organizationId, code, "Employee " + code);
        AuthTestData.execute("INSERT INTO employee_assignment (organization_id, employee_id, org_unit_id) VALUES (?, ?, ?)",
                organizationId, employeeId, unitId);
        return employeeId;
    }

    private void grant(long userId, long unitId, boolean descendants, String... permissions) throws SQLException {
        long roleId = role(orgId, unique("GRANT"), permissions);
        AuthTestData.execute("""
                INSERT INTO role_assignment (organization_id, user_id, role_id, scope_unit_id, include_descendants)
                VALUES (?, ?, ?, ?, ?)
                """, orgId, userId, roleId, unitId, descendants);
    }

    private long role(long organizationId, String code, String... permissions) throws SQLException {
        long roleId = insertReturning("INSERT INTO role (organization_id, code, name) VALUES (?, ?, ?) RETURNING id",
                organizationId, code, code);
        for (String permission : permissions) {
            AuthTestData.execute("""
                    INSERT INTO role_permission (organization_id, role_id, permission_id)
                    SELECT ?, ?, id FROM permission WHERE code = ?
                    """, organizationId, roleId, permission);
        }
        return roleId;
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

    private static String unique(String prefix) {
        String value = prefix + Long.toUnsignedString(System.nanoTime(), 36);
        return value.substring(0, Math.min(30, value.length()));
    }
}
