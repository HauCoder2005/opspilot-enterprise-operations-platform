package dev.opspilot.configs;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * JWT HS256 cho access token (KAN-76). Khoá lấy từ {@code JWT_SECRET}; quản lý khoá production (KMS, xoay khoá,
 * thuật toán bất đối xứng) là OPEN DECISION, chưa áp dụng.
 *
 * <p>Decoder chỉ chấp nhận HS256 (chặn {@code alg=none}/đổi thuật toán), kiểm tra chữ ký, {@code exp}/{@code nbf}
 * (lệch giờ tối đa 30 giây), {@code iss} và sự có mặt của {@code sub}, {@code org_id}, {@code tv}, {@code jti}, {@code iat}.
 */
@Configuration(proxyBeanMethods = false)
public class JwtConfig {

    public static final String CLAIM_ORG_ID = "org_id";
    public static final String CLAIM_TOKEN_VERSION = "tv";

    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    @Bean
    SecretKey jwtSigningKey(AuthTokenProperties properties) {
        return new SecretKeySpec(properties.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey, AuthTokenProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(CLOCK_SKEW),
                new JwtIssuerValidator(properties.issuer()),
                new JwtClaimValidator<Object>("sub", Objects::nonNull),
                new JwtClaimValidator<Object>("jti", Objects::nonNull),
                new JwtClaimValidator<Object>("iat", Objects::nonNull),
                new JwtClaimValidator<Object>("exp", Objects::nonNull),
                new JwtClaimValidator<Object>(CLAIM_ORG_ID, Objects::nonNull),
                new JwtClaimValidator<Object>(CLAIM_TOKEN_VERSION, Objects::nonNull));
        decoder.setJwtValidator(validator);
        return decoder;
    }
}
