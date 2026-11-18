package dev.opspilot.security;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class SecurityContextCurrentUserProvider implements ICurrentUserProvider {

    @Override
    public Optional<OpsPilotPrincipal> currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof OpsPilotPrincipal principal) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }

    @Override
    public OpsPilotPrincipal requireCurrentUser() {
        return currentUser().orElseThrow(() -> new AuthenticationCredentialsNotFoundException("No authenticated user"));
    }
}
