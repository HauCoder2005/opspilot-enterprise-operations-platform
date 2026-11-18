package dev.opspilot.configs;

import dev.opspilot.security.authentication.ICurrentUserProvider;
import dev.opspilot.security.authentication.OpsPilotPrincipal;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA Auditing cho {@code created_at/by}, {@code updated_at/by}. Người thực hiện lấy từ access token đã xác minh;
 * không có người dùng (job, onboarding) thì để null.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(auditorAwareRef = "currentUserAuditor")
public class JpaAuditingConfig {

    @Bean
    AuditorAware<Long> currentUserAuditor(ICurrentUserProvider currentUser) {
        return () -> currentUser.currentUser().map(OpsPilotPrincipal::userId);
    }
}
