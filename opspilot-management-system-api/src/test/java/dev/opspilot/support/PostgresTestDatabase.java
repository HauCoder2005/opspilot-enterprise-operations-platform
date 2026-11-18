package dev.opspilot.support;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;

/**
 * PostgreSQL 18 dùng chung cho các integration test, dựng giống compose.team.yaml:
 * bootstrap role bằng superuser → Flyway migrate bằng superuser (script tự SET ROLE opspilot_owner)
 * → ứng dụng kết nối bằng {@code opspilot_app} (không superuser, không BYPASSRLS) để RLS có hiệu lực.
 */
public final class PostgresTestDatabase {

    private static final String APP_ROLE = "opspilot_app";
    private static final String APP_PASSWORD = HexFormat.of().formatHex(new SecureRandom().generateSeed(16));
    /** Secret JWT chỉ dùng trong test, sinh ngẫu nhiên mỗi lần chạy (không hardcode). */
    public static final String JWT_SECRET = java.util.Base64.getEncoder().encodeToString(new SecureRandom().generateSeed(48));
    private static final String JOB_ROLE = "opspilot_job";
    private static final String JOB_PASSWORD = HexFormat.of().formatHex(new SecureRandom().generateSeed(16));

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer("postgres:18")
            .withDatabaseName("opspilot")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("database/bootstrap/01_roles.sql")),
                    "/docker-entrypoint-initdb.d/01_roles.sql");

    static {
        CONTAINER.start();
        try (Connection connection = superuserConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE " + APP_ROLE + " PASSWORD '" + APP_PASSWORD + "'");
            statement.execute("ALTER ROLE " + JOB_ROLE + " PASSWORD '" + JOB_PASSWORD + "'");
        } catch (SQLException exception) {
            throw new IllegalStateException("Không cấu hình được role opspilot_app cho test", exception);
        }
        Flyway.configure()
                .dataSource(CONTAINER.getJdbcUrl(), CONTAINER.getUsername(), CONTAINER.getPassword())
                .locations("filesystem:src/main/resources/db/migration")
                .load()
                .migrate();
    }

    private PostgresTestDatabase() {
    }

    public static void registerAppDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("app.auth.token.jwt-secret", () -> JWT_SECRET);
    }

    /** Kết nối superuser chỉ dùng để seed/kiểm tra dữ liệu trong test (bỏ qua RLS). */
    public static Connection superuserConnection() throws SQLException {
        return DriverManager.getConnection(CONTAINER.getJdbcUrl(), CONTAINER.getUsername(), CONTAINER.getPassword());
    }

    public static DataSource superuserDataSource() {
        return new DriverManagerDataSource(CONTAINER.getJdbcUrl(), CONTAINER.getUsername(), CONTAINER.getPassword());
    }

    public static DataSource appDataSource() {
        return new DriverManagerDataSource(CONTAINER.getJdbcUrl(), APP_ROLE, APP_PASSWORD);
    }

    /** Tham số dòng lệnh Spring trỏ datasource vào superuser, dùng để chứng minh ứng dụng từ chối khởi động. */
    public static String[] superuserDataSourceArgs() {
        return new String[] {
                "--spring.datasource.url=" + CONTAINER.getJdbcUrl(),
                "--spring.datasource.username=" + CONTAINER.getUsername(),
                "--spring.datasource.password=" + CONTAINER.getPassword(),
                "--app.auth.token.jwt-secret=" + JWT_SECRET
        };
    }

    /** opspilot_job có BYPASSRLS: không bao giờ được dùng làm datasource API. */
    public static DataSource jobDataSource() {
        return new DriverManagerDataSource(CONTAINER.getJdbcUrl(), JOB_ROLE, JOB_PASSWORD);
    }

    /** Kết nối bằng role runtime của ứng dụng để kiểm tra hành vi RLS. */
    public static Connection appConnection() throws SQLException {
        return DriverManager.getConnection(CONTAINER.getJdbcUrl(), APP_ROLE, APP_PASSWORD);
    }
}
