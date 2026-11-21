package dev.opspilot.controllers.iam;

import dev.opspilot.controllers.ClientRequests;

import dev.opspilot.common.responses.IPageResponse;
import dev.opspilot.dtos.ApiResponse;
import dev.opspilot.dtos.PageMeta;
import dev.opspilot.dtos.iam.AssignRoleRequest;
import dev.opspilot.dtos.iam.CreateUserRequest;
import dev.opspilot.dtos.iam.CreateUserResponse;
import dev.opspilot.dtos.iam.PasswordResetResponse;
import dev.opspilot.dtos.iam.RoleAssignmentResponse;
import dev.opspilot.dtos.iam.UserAccountResponse;
import dev.opspilot.enums.iam.UserStatus;
import dev.opspilot.security.authentication.ICurrentUserProvider;
import dev.opspilot.services.iam.AdminPasswordResetService;
import dev.opspilot.services.iam.RoleAssignmentService;
import dev.opspilot.services.iam.UserProvisioningService;
import dev.opspilot.services.iam.UserAccountQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@RestController
@RequestMapping("${app.api-base-path:/api/v1}/iam/users")
@Tag(name = "IAM - Users", description = "Quản trị tài khoản (phân quyền theo iam.user.manage + phạm vi đơn vị)")
@SecurityRequirement(name = "bearerAuth")
@Validated
public class IamUserPasswordController implements IPageResponse {

    private final AdminPasswordResetService resetService;
    private final UserProvisioningService provisioningService;
    private final RoleAssignmentService roleAssignmentService;
    private final UserAccountQueryService accountQueryService;
    private final ICurrentUserProvider currentUser;

    public IamUserPasswordController(AdminPasswordResetService resetService,
                                     UserProvisioningService provisioningService,
                                     RoleAssignmentService roleAssignmentService,
                                     UserAccountQueryService accountQueryService,
                                     ICurrentUserProvider currentUser) {
        this.resetService = resetService;
        this.provisioningService = provisioningService;
        this.roleAssignmentService = roleAssignmentService;
        this.accountQueryService = accountQueryService;
        this.currentUser = currentUser;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "listIamUsers", summary = "Danh sách tài khoản theo phạm vi được cấp")
    public ResponseEntity<ApiResponse<List<UserAccountResponse>, PageMeta>> listUsers(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(required = false) @Min(1) Long unitId) {
        return pageResponse(accountQueryService.find(
                currentUser.requireCurrentUser(), page, size, q, status, unitId));
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "createIamUser", summary = "Cấp tài khoản cho nhân viên")
    public ResponseEntity<ApiResponse<CreateUserResponse, Void>> createUser(
            @Valid @RequestBody CreateUserRequest body,
            HttpServletRequest request) {
        var created = provisioningService.create(
                currentUser.requireCurrentUser(), body, ClientRequests.clientInfo(request));
        var response = new CreateUserResponse(created.userId(), created.employeeId(), created.username(),
                created.temporaryPassword(), true);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(new ApiResponse<>(response, null));
    }

    @PostMapping("/{id}/role-assignments")
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "assignUserRole", summary = "Gán vai trò có phạm vi cho tài khoản")
    public ResponseEntity<ApiResponse<RoleAssignmentResponse, Void>> assignRole(
            @PathVariable("id") long id,
            @Valid @RequestBody AssignRoleRequest body,
            HttpServletRequest request) {
        var assigned = roleAssignmentService.assign(
                currentUser.requireCurrentUser(), id, body, ClientRequests.clientInfo(request));
        var response = new RoleAssignmentResponse(assigned.assignmentId(), assigned.userId(), assigned.roleId(),
                assigned.scopeUnitId(), assigned.includeDescendants(), assigned.validFrom(), assigned.validTo());
        return ResponseEntity.status(201).body(new ApiResponse<>(response, null));
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "adminResetPassword", summary = "Reset mật khẩu người dùng (quản trị viên)",
            description = "Quyền do policy service KAN-81 quyết định; khi chưa có thì trả 503 POLICY_SERVICE_UNAVAILABLE.")
    public ResponseEntity<ApiResponse<PasswordResetResponse, Void>> resetPassword(@PathVariable("id") long id, HttpServletRequest request) {
        AdminPasswordResetService.ResetResult result =
                resetService.resetPassword(currentUser.requireCurrentUser(), id, ClientRequests.clientInfo(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ApiResponse<>(new PasswordResetResponse(result.userId(), result.temporaryPassword(), true), null));
    }
}
