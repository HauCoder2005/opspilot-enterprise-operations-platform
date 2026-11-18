package dev.opspilot.security.tenant;

import dev.opspilot.OpsPilotApplication;
import dev.opspilot.support.PostgresTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Quyết định đã duyệt: profile prod từ chối khởi động nếu datasource không chịu RLS; dev chỉ cảnh báo. */
class RlsSessionGuardTest {

    @Test
    void acceptsOpspilotAppInProd() {
        assertThatCode(() -> guard(PostgresTestDatabase.appDataSource(), "prod").verify()).doesNotThrowAnyException();
    }

    @Test
    void rejectsSuperuserInProd() {
        assertThatThrownBy(() -> guard(PostgresTestDatabase.superuserDataSource(), "prod").verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("khong_phai_superuser")
                .hasMessageContaining("dang_nhap_dung_opspilot_app");
    }

    @Test
    void rejectsBypassRlsJobRoleInProd() {
        // opspilot_job không được cấp EXECUTE trên rls_session_check(): bị từ chối ngay khi gọi hàm.
        assertThatThrownBy(() -> guard(PostgresTestDatabase.jobDataSource(), "prod").verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rls_session_check");
    }

    @Test
    void onlyWarnsForSuperuserInDev() {
        assertThatCode(() -> guard(PostgresTestDatabase.superuserDataSource(), "dev").verify()).doesNotThrowAnyException();
    }

    @Test
    void applicationRefusesToStartAsSuperuserInProd() {
        String[] args = Stream.concat(Arrays.stream(PostgresTestDatabase.superuserDataSourceArgs()),
                Stream.of("--spring.profiles.active=prod", "--server.port=0", "--spring.main.keep-alive=false"))
                .toArray(String[]::new);

        assertThatThrownBy(() -> new SpringApplicationBuilder(OpsPilotApplication.class).run(args).close())
                .hasStackTraceContaining("Datasource không chịu Row-Level Security");
    }

    @Test
    void applicationStartsAsSuperuserInDev() {
        String[] args = Stream.concat(Arrays.stream(PostgresTestDatabase.superuserDataSourceArgs()),
                Stream.of("--spring.profiles.active=dev", "--server.port=0", "--spring.main.keep-alive=false"))
                .toArray(String[]::new);

        assertThatCode(() -> new SpringApplicationBuilder(OpsPilotApplication.class).run(args).close())
                .doesNotThrowAnyException();
    }

    private static RlsSessionGuard guard(DataSource dataSource, String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return new RlsSessionGuard(JdbcClient.create(dataSource), environment);
    }
}
