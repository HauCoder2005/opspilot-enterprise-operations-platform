package dev.opspilot.configs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            AppProperties properties,
            @Value("${springdoc.api-docs.enabled:false}") boolean swaggerEnabled
    ) throws Exception {
        String pingPath = properties.apiBasePath() + "/system/ping";

        http
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers(HttpMethod.GET, pingPath).permitAll();
                    authorize.requestMatchers(
                            "/actuator/health",
                            "/actuator/health/liveness",
                            "/actuator/health/readiness"
                    ).permitAll();
                    if (swaggerEnabled) {
                        authorize.requestMatchers(
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs",
                                "/v3/api-docs.yaml",
                                "/v3/api-docs/**"
                        ).permitAll();
                    }
                    authorize.anyRequest().denyAll();
                });

        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AppProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.cors().allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration(properties.apiBasePath() + "/**", configuration);
        return source;
    }
}
