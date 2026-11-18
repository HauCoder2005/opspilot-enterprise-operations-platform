package dev.opspilot.services.auth;

import dev.opspilot.enums.ClientType;
import org.springframework.stereotype.Service;

/**
 * Implementation chính thức của {@link IAuthTokenIssuer}: access token JWT (KAN-76) + refresh token family mới (KAN-77),
 * trong giao dịch ghi nhận đăng nhập thành công.
 */
@Service
public class AuthTokenIssuer implements IAuthTokenIssuer {

    private final AccessTokenService accessTokens;
    private final RefreshTokenService refreshTokens;

    public AuthTokenIssuer(AccessTokenService accessTokens, RefreshTokenService refreshTokens) {
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
    }

    @Override
    public IssuedTokens issue(TokenSubject subject, ClientType clientType, ClientInfo clientInfo) {
        RefreshTokenService.NewRefreshToken refresh =
                refreshTokens.createFamily(subject.organizationId(), subject.userId(), clientType, clientInfo);
        AccessTokenService.AccessToken access = accessTokens.issue(subject);
        return new IssuedTokens(access.value(), access.expiresAt(), refresh.rawToken(), refresh.expiresAt());
    }
}
