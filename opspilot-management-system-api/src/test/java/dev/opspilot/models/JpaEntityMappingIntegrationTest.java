package dev.opspilot.models;

import dev.opspilot.enums.ClientType;
import dev.opspilot.enums.hr.EmploymentStatus;
import dev.opspilot.enums.iam.UserStatus;
import dev.opspilot.enums.organization.OrgUnitType;
import dev.opspilot.models.hr.Employee;
import dev.opspilot.models.hr.EmployeeAssignment;
import dev.opspilot.models.hr.JobTitle;
import dev.opspilot.models.iam.AppUser;
import dev.opspilot.models.iam.Permission;
import dev.opspilot.models.iam.RefreshToken;
import dev.opspilot.models.iam.Role;
import dev.opspilot.models.iam.RoleAssignment;
import dev.opspilot.models.iam.RolePermission;
import dev.opspilot.models.organization.OrgUnit;
import dev.opspilot.models.organization.Organization;
import dev.opspilot.security.authentication.OpsPilotAuthenticationToken;
import dev.opspilot.security.authentication.OpsPilotPrincipal;
import dev.opspilot.security.tenant.TenantDatabaseContext;
import dev.opspilot.support.PostgresTestDatabase;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Entity JPA của ORG/IAM/HR khớp schema thật: {@code ddl-auto=validate} kiểm tra mọi bảng/cột/kiểu khi khởi động;
 * các test lưu/đọc chạy bằng opspilot_app với tenant gắn vào giao dịch (RLS có hiệu lực).
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
class JpaEntityMappingIntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.registerAppDataSource(registry);
    }

    @Autowired
    EntityManager entityManager;

    @Autowired
    TenantDatabaseContext tenantContext;

    @Autowired
    PlatformTransactionManager transactionManager;

    long orgId;

    @BeforeEach
    void createTenant() throws SQLException {
        orgId = insertOrganization("M" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void persistsAndReloadsWholeOrgIamHrGraph() {
        long actorId = inTenant(orgId, em -> {
            AppUser actor = new AppUser(orgId, "actor", "{bcrypt}placeholder", "Actor");
            em.persist(actor);
            return actor.getId();
        });
        authenticateAs(actorId);

        Ids ids = inTenant(orgId, em -> {
            OrgUnit root = new OrgUnit(orgId, null, OrgUnitType.COMPANY, "ROOT", "Công ty");
            em.persist(root);
            em.flush();
            OrgUnit branch = new OrgUnit(orgId, root.getId(), OrgUnitType.BRANCH, "CN-A", "Chi nhánh A");
            branch.setMetadata("{\"region\":\"south\"}");
            em.persist(branch);
            em.flush();
            assertThat(root.getPath()).isEqualTo(root.getId().toString());
            assertThat(root.getDepth()).isEqualTo((short) 0);
            assertThat(branch.getPath()).isEqualTo(root.getId() + "." + branch.getId());
            assertThat(branch.getDepth()).isEqualTo((short) 1);

            AppUser user = new AppUser(orgId, "staff.a", "{bcrypt}placeholder", "Staff A");
            user.setEmail("staff.a@example.com");
            em.persist(user);
            Role role = new Role(orgId, "CUSTOM_ROLE", "Custom");
            em.persist(role);
            em.flush();
            long permissionId = em.createQuery("select p from Permission p where p.code = 'iam.user.view'", Permission.class)
                    .getSingleResult().getId();
            RolePermission rolePermission = new RolePermission(orgId, role.getId(), permissionId, actorId);
            em.persist(rolePermission);
            RoleAssignment assignment = new RoleAssignment(orgId, user.getId(), role.getId(), branch.getId(), true,
                    Instant.now().minusSeconds(60), null, actorId);
            em.persist(assignment);
            JobTitle jobTitle = new JobTitle(orgId, "CASHIER", "Thu ngân");
            em.persist(jobTitle);
            Employee employee = new Employee(orgId, "NV001", "Nguyễn Văn A");
            employee.linkUser(user.getId());
            employee.setHireDate(LocalDate.of(2026, 1, 5));
            em.persist(employee);
            em.flush();
            EmployeeAssignment workplace = new EmployeeAssignment(orgId, employee.getId(), branch.getId(),
                    jobTitle.getId(), true, LocalDate.of(2026, 1, 5));
            em.persist(workplace);
            em.flush();
            assertThat(rolePermission.getGrantedAt()).as("granted_at do DB sinh").isNotNull();
            return new Ids(branch.getId(), user.getId(), role.getId(), assignment.getId(), employee.getId(),
                    workplace.getId(), rolePermission.getId());
        });

        inTenant(orgId, em -> {
            OrgUnit branch = em.find(OrgUnit.class, ids.branchId);
            assertThat(branch.getMetadata()).contains("\"region\"").contains("south");
            assertThat(branch.getCreatedBy()).isEqualTo(actorId);
            assertThat(branch.getCreatedAt()).isNotNull();

            AppUser user = em.find(AppUser.class, ids.userId);
            assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(user.isMustChangePassword()).isTrue();
            assertThat(user.getOrganizationId()).isEqualTo(orgId);

            RoleAssignment assignment = em.find(RoleAssignment.class, ids.assignmentId);
            assertThat(assignment.isEffectiveAt(Instant.now())).isTrue();
            assertThat(assignment.getScopeUnitId()).isEqualTo(ids.branchId);

            Employee employee = em.find(Employee.class, ids.employeeId);
            assertThat(employee.getUserId()).isEqualTo(ids.userId);
            assertThat(employee.getEmploymentStatus()).isEqualTo(EmploymentStatus.ACTIVE);
            assertThat(employee.getMetadata()).isEqualTo("{}");

            assertThat(em.find(EmployeeAssignment.class, ids.workplaceId).isActiveOn(LocalDate.of(2026, 2, 1))).isTrue();
            assertThat(em.find(RolePermission.class, ids.rolePermissionId).getRoleId()).isEqualTo(ids.roleId);
            return null;
        });
    }

    @Test
    void updatingUserViaJpaDoesNotOverwriteSecurityColumnsManagedBySql() throws SQLException {
        long userId = inTenant(orgId, em -> {
            AppUser user = new AppUser(orgId, "counter.user", "{bcrypt}placeholder", "Before");
            em.persist(user);
            return user.getId();
        });
        // Mô phỏng các cập nhật nguyên tử của đăng nhập/đổi mật khẩu.
        superuserUpdate("UPDATE app_user SET failed_login_count = 3, token_version = 7, password_hash = '{bcrypt}changed',"
                + " must_change_password = FALSE WHERE id = ?", userId);

        inTenant(orgId, em -> {
            AppUser user = em.find(AppUser.class, userId);
            user.setDisplayName("After");
            user.setStatus(UserStatus.LOCKED);
            return null;
        });

        inTenant(orgId, em -> {
            AppUser user = em.find(AppUser.class, userId);
            assertThat(user.getDisplayName()).isEqualTo("After");
            assertThat(user.getStatus()).isEqualTo(UserStatus.LOCKED);
            assertThat(user.getFailedLoginCount()).isEqualTo((short) 3);
            assertThat(user.getTokenVersion()).isEqualTo(7);
            assertThat(user.isMustChangePassword()).isFalse();
            assertThat(user.getVersion()).as("version tăng khi JPA sửa").isEqualTo(1);
            return null;
        });
        assertThat(superuserString("SELECT password_hash FROM app_user WHERE id = ?", userId)).isEqualTo("{bcrypt}changed");
    }

    @Test
    void organizationAndRefreshTokenMapAgainstRealColumns() {
        inTenant(orgId, em -> {
            Organization organization = em.find(Organization.class, orgId);
            assertThat(organization.getCurrency()).isEqualTo("VND");
            assertThat(organization.getSettings()).isEqualTo("{}");
            organization.setName("Đã đổi tên");
            return null;
        });
        long userId = inTenant(orgId, em -> {
            AppUser user = new AppUser(orgId, "rt.user", "{bcrypt}placeholder", "RT");
            em.persist(user);
            em.flush();
            em.createNativeQuery("""
                    INSERT INTO refresh_token (organization_id, user_id, token_hash, expires_at, client_type, ip_address)
                    VALUES (?1, ?2, repeat('a', 64), now() + interval '1 day', 'MOBILE', CAST('10.0.0.1' AS inet))
                    """).setParameter(1, orgId).setParameter(2, user.getId()).executeUpdate();
            return user.getId();
        });

        inTenant(orgId, em -> {
            assertThat(em.find(Organization.class, orgId).getName()).isEqualTo("Đã đổi tên");
            RefreshToken token = em.createQuery("select t from RefreshToken t where t.userId = :u", RefreshToken.class)
                    .setParameter("u", userId).getSingleResult();
            assertThat(token.getFamilyId()).as("trigger gán family_id = id").isEqualTo(token.getId());
            assertThat(token.getClientType()).isEqualTo(ClientType.MOBILE);
            assertThat(token.getIpAddress()).isEqualTo("10.0.0.1");
            assertThat(token.isActive(Instant.now())).isTrue();
            assertThat(token.toString()).doesNotContain("aaaa");
            return null;
        });
    }

    @Test
    void rowsOfAnotherTenantAreInvisibleThroughJpa() throws SQLException {
        long userId = inTenant(orgId, em -> {
            AppUser user = new AppUser(orgId, "tenant.a", "{bcrypt}placeholder", "Tenant A");
            em.persist(user);
            return user.getId();
        });
        long otherOrg = insertOrganization("N" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));

        AppUser seenFromOtherTenant = inTenant(otherOrg, em -> em.find(AppUser.class, userId));
        Organization foreignOrganization = inTenant(otherOrg, em -> em.find(Organization.class, orgId));

        assertThat(seenFromOtherTenant).isNull();
        assertThat(foreignOrganization).isNull();
    }

    private <T> T inTenant(long tenantId, Function<EntityManager, T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            tenantContext.bind(tenantId);
            return work.apply(entityManager);
        });
    }

    private void authenticateAs(long userId) {
        SecurityContextHolder.getContext().setAuthentication(new OpsPilotAuthenticationToken(
                new OpsPilotPrincipal(userId, orgId, 0, false, "test-jti"), null));
    }

    private static long insertOrganization(String code) throws SQLException {
        try (Connection c = PostgresTestDatabase.superuserConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO organization (code, name) VALUES (?, ?) RETURNING id")) {
            ps.setString(1, code);
            ps.setString(2, "Org " + code);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void superuserUpdate(String sql, long id) throws SQLException {
        try (Connection c = PostgresTestDatabase.superuserConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private static String superuserString(String sql, long id) {
        try (Connection c = PostgresTestDatabase.superuserConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Ids(long branchId, long userId, long roleId, long assignmentId, long employeeId, long workplaceId,
                       long rolePermissionId) {
    }
}
