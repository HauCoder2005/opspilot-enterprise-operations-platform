package dev.opspilot.controllers;

import dev.opspilot.dtos.responses.PasswordResetResponse;
import dev.opspilot.security.ICurrentUserProvider;
import dev.opspilot.services.iam.AdminPasswordResetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${app.api-base-path:/api/v1}/iam/users")
@Tag(name = "IAM - Users", description = "Quản trị tài khoản (phân quyền theo iam.user.manage + phạm vi đơn vị)")
@SecurityRequirement(name = "bearerAuth")
public class IamUserPasswordController {

    private final AdminPasswordResetService resetService;
    private final ICurrentUserProvider currentUser;

    public IamUserPasswordController(AdminPasswordResetService resetService, ICurrentUserProvider currentUser) {
        this.resetService = resetService;
        this.currentUser = currentUser;
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "adminResetPassword", summary = "Reset mật khẩu người dùng (quản trị viên)",
            description = "Quyền do policy service KAN-81 quyết định; khi chưa có thì trả 503 POLICY_SERVICE_UNAVAILABLE.")
    public ResponseEntity<PasswordResetResponse> resetPassword(@PathVariable("id") long id, HttpServletRequest request) {
        AdminPasswordResetService.ResetResult result =
                resetService.resetPassword(currentUser.requireCurrentUser(), id, AuthController.clientInfo(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new PasswordResetResponse(result.userId(), result.temporaryPassword(), true));
    }
}
