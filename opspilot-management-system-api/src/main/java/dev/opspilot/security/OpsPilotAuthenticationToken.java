package dev.opspilot.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/** Authentication cho request mang access token hợp lệ. Không có authority: phân quyền nằm ở policy service. */
public class OpsPilotAuthenticationToken extends AbstractAuthenticationToken {

    private final OpsPilotPrincipal principal;
    private final transient Jwt jwt;

    public OpsPilotAuthenticationToken(OpsPilotPrincipal principal, Jwt jwt) {
        super(List.of());
        this.principal = principal;
        this.jwt = jwt;
        setAuthenticated(true);
    }

    @Override
    public OpsPilotPrincipal getPrincipal() {
        return principal;
    }

    /** Không trả raw token để tránh vô tình log/serialize. */
    @Override
    public Object getCredentials() {
        return null;
    }

    public Jwt jwt() {
        return jwt;
    }

    @Override
    public String getName() {
        return principal.getName();
    }
}
