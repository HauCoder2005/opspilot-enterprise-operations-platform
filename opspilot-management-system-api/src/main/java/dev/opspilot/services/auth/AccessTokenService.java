package dev.opspilot.services.auth;

import dev.opspilot.configs.AuthTokenProperties;
import dev.opspilot.configs.JwtConfig;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Phát hành access token JWT (KAN-76). Claims: {@code iss, sub, org_id, tv, iat, exp, jti}. Không nhúng permission.
 */
@Service
public class AccessTokenService {

    private final JwtEncoder jwtEncoder;
    private final AuthTokenProperties properties;
    public AccessTokenService(JwtEncoder jwtEncoder, AuthTokenProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    public AccessToken issue(IAuthTokenIssuer.TokenSubject subject) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(Long.toString(subject.userId()))
                .claim(JwtConfig.CLAIM_ORG_ID, subject.organizationId())
                .claim(JwtConfig.CLAIM_TOKEN_VERSION, subject.tokenVersion())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String value = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(value, expiresAt);
    }

    public record AccessToken(String value, Instant expiresAt) {
        @Override
        public String toString() {
            return "AccessToken[value=***, expiresAt=" + expiresAt + "]";
        }
    }
}
