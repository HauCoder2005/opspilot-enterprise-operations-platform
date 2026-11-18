package dev.opspilot.configs;

import dev.opspilot.security.authentication.JwtPrincipalConverter;
import dev.opspilot.security.authentication.OpsPilotPrincipal;
import dev.opspilot.security.authentication.RefreshTokenCookies;
import dev.opspilot.security.SecurityErrorHandler;
import dev.opspilot.security.filters.RequestIdFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * API stateless dùng JWT Bearer (KAN-76).
 *
 * <ul>
 *   <li>Công khai: ping, health, Swagger (khi bật), login, refresh, logout, lấy CSRF token.</li>
 *   <li>Chỉ cần đăng nhập (kể cả khi bắt buộc đổi mật khẩu): /auth/me, /auth/change-password, /auth/logout-all.</li>
 *   <li>Mọi API khác dưới base path: đã đăng nhập VÀ đã hoàn tất đổi mật khẩu bắt buộc. Quyền theo nghiệp vụ/đơn vị
 *       do service kiểm tra qua policy (KAN-81), không quyết định ở URL.</li>
 *   <li>Còn lại: deny.</li>
 * </ul>
 *
 * <p>CSRF chỉ áp dụng cho request dựa vào cookie refresh token (POST /auth/refresh, /auth/logout có cookie):
 * double-submit token (cookie {@code XSRF-TOKEN} + header {@code X-XSRF-TOKEN}). Request dùng Bearer header hoặc
 * refresh token trong body (MOBILE) không mang credential tự động nên không cần CSRF.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            AppProperties properties,
            JwtDecoder jwtDecoder,
            JwtPrincipalConverter jwtPrincipalConverter,
            SecurityErrorHandler securityErrorHandler,
            RefreshTokenCookies refreshTokenCookies,
            @Value("${springdoc.api-docs.enabled:false}") boolean swaggerEnabled
    ) throws Exception {
        String base = properties.apiBasePath();
        String auth = base + "/auth";
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        RequestMatcher cookieAuthenticatedRequest = request -> refreshTokenCookies.isPresent(request)
                && (paths.matcher(HttpMethod.POST, auth + "/refresh").matches(request)
                || paths.matcher(HttpMethod.POST, auth + "/logout").matches(request));

        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieCustomizer(cookie -> cookie.sameSite("Strict").path(base));

        http
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .logout(logout -> logout.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .requireCsrfProtectionMatcher(cookieAuthenticatedRequest))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(jwtPrincipalConverter))
                        .authenticationEntryPoint(securityErrorHandler)
                        .accessDeniedHandler(securityErrorHandler))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(securityErrorHandler)
                        .accessDeniedHandler(securityErrorHandler))
                .authorizeHttpRequests(authorize -> {
                    // Lượt dispatch nội bộ tới /error sau một lỗi đã được xử lý phân quyền ở request gốc.
                    // Request trực tiếp tới /error (dispatch REQUEST) vẫn rơi vào denyAll bên dưới.
                    authorize.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
                    authorize.requestMatchers(HttpMethod.GET, base + "/system/ping").permitAll();
                    authorize.requestMatchers("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")
                            .permitAll();
                    if (swaggerEnabled) {
                        authorize.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs",
                                "/v3/api-docs.yaml", "/v3/api-docs/**").permitAll();
                    }
                    authorize.requestMatchers(HttpMethod.POST, auth + "/login", auth + "/refresh", auth + "/logout")
                            .permitAll();
                    authorize.requestMatchers(HttpMethod.GET, auth + "/csrf").permitAll();
                    authorize.requestMatchers(HttpMethod.GET, auth + "/me").authenticated();
                    authorize.requestMatchers(HttpMethod.POST, auth + "/change-password", auth + "/logout-all")
                            .authenticated();
                    authorize.requestMatchers(base + "/**").access(passwordChangeCompleted());
                    authorize.anyRequest().denyAll();
                });

        return http.build();
    }

    /** Đã xác thực bằng access token hợp lệ và không còn ở trạng thái bắt buộc đổi mật khẩu (KAN-78). */
    static AuthorizationManager<RequestAuthorizationContext> passwordChangeCompleted() {
        return (authenticationSupplier, context) -> {
            Authentication authentication = authenticationSupplier.get();
            boolean granted = authentication != null && authentication.isAuthenticated()
                    && authentication.getPrincipal() instanceof OpsPilotPrincipal principal
                    && !principal.mustChangePassword();
            return new AuthorizationDecision(granted);
        };
    }

    /**
     * DelegatingPasswordEncoder: hash mới dùng {@code {bcrypt}}; vẫn đọc được các định dạng có prefix khác
     * để nâng cấp thuật toán sau này. Tương thích hash {@code {bcrypt}} do công cụ onboarding (KAN-95) tạo.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AppProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.cors().allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-XSRF-TOKEN",
                RequestIdFilter.REQUEST_ID_HEADER));
        configuration.setExposedHeaders(List.of(RequestIdFilter.REQUEST_ID_HEADER, "WWW-Authenticate"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration(properties.apiBasePath() + "/**", configuration);
        return source;
    }
}
