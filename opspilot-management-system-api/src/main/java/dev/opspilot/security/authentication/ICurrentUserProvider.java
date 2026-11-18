package dev.opspilot.security.authentication;

import java.util.Optional;

/** Truy cập danh tính đã xác thực của request hiện tại cho service layer, thay vì đọc SecurityContext rải rác. */
public interface ICurrentUserProvider {

    Optional<OpsPilotPrincipal> currentUser();

    /** @throws org.springframework.security.authentication.AuthenticationCredentialsNotFoundException nếu chưa xác thực */
    OpsPilotPrincipal requireCurrentUser();
}
