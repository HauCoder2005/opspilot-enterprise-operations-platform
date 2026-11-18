package dev.opspilot.security.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Kiểm tra lúc khởi động rằng datasource chịu Row-Level Security, qua {@code rls_session_check()} (V14).
 *
 * <p>Quyết định đã duyệt ngày 17/11/2026: profile {@code prod} TỪ CHỐI khởi động nếu có kiểm tra không đạt
 * (kết nối không phải {@code opspilot_app}, superuser, BYPASSRLS, kế thừa owner/job/etl...). Các profile khác
 * (dev dùng {@code postgres}) chỉ ghi cảnh báo: khi đó tenant isolation KHÔNG được RLS bảo vệ và kết quả chạy
 * không chứng minh được cách ly dữ liệu giữa doanh nghiệp.
 *
 * <p>Chạy sau khi mọi singleton được tạo, trước khi web server mở cổng.
 */
@Component
public class RlsSessionGuard implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(RlsSessionGuard.class);
    private static final Profiles ENFORCED_PROFILES = Profiles.of("prod");

    private final JdbcClient jdbcClient;
    private final Environment environment;

    public RlsSessionGuard(JdbcClient jdbcClient, Environment environment) {
        this.jdbcClient = jdbcClient;
        this.environment = environment;
    }

    @Override
    public void afterSingletonsInstantiated() {
        verify();
    }

    public void verify() {
        String problem = findProblem();
        if (problem == null) {
            log.info("RLS session check passed");
            return;
        }
        if (environment.acceptsProfiles(ENFORCED_PROFILES)) {
            throw new IllegalStateException("Datasource không chịu Row-Level Security; từ chối khởi động ở profile prod. "
                    + problem + " Cấu hình DB_USERNAME=opspilot_app (không dùng postgres/owner/job/etl).");
        }
        log.warn("RLS KHÔNG có hiệu lực với datasource hiện tại ({}). Chỉ chấp nhận cho phát triển local; "
                + "profile prod sẽ từ chối khởi động. Tenant isolation lúc này chỉ dựa vào điều kiện SQL trong code.", problem);
    }

    private String findProblem() {
        List<Check> checks;
        try {
            checks = jdbcClient.sql("SELECT check_name, ok FROM rls_session_check()")
                    .query((rs, rowNum) -> new Check(rs.getString("check_name"), rs.getBoolean("ok")))
                    .list();
        } catch (DataAccessException exception) {
            log.debug("rls_session_check() call failed", exception);
            return "Không gọi được rls_session_check() (database chưa migrate hoặc role không được cấp quyền).";
        }
        List<String> failed = checks.stream().filter(check -> !check.ok()).map(Check::name).toList();
        if (checks.isEmpty()) {
            return "rls_session_check() không trả về kết quả.";
        }
        return failed.isEmpty() ? null : "Kiểm tra không đạt: " + failed + ".";
    }

    private record Check(String name, boolean ok) {
    }
}
