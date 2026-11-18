package dev.opspilot.controllers;

import dev.opspilot.common.responses.IResponse;
import dev.opspilot.dtos.ApiErrorResponse;
import dev.opspilot.dtos.ApiResponse;
import dev.opspilot.dtos.ChangePasswordRequest;
import dev.opspilot.dtos.LoginRequest;
import dev.opspilot.dtos.RefreshTokenRequest;
import dev.opspilot.dtos.CsrfTokenResponse;
import dev.opspilot.dtos.CurrentUserResponse;
import dev.opspilot.dtos.LoginResponse;
import dev.opspilot.enums.ClientType;
import dev.opspilot.enums.ErrorCode;
import dev.opspilot.exceptions.ApiException;
import dev.opspilot.security.ICurrentUserProvider;
import dev.opspilot.security.RefreshTokenCookies;
import dev.opspilot.security.filters.RequestIdFilter;
import dev.opspilot.services.auth.ClientInfo;
import dev.opspilot.services.auth.CurrentAccountService;
import dev.opspilot.services.auth.LoginService;
import dev.opspilot.services.auth.PasswordChangeService;
import dev.opspilot.services.auth.RefreshTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("${app.api-base-path:/api/v1}/auth")
@Tag(name = "Authentication", description = "Đăng nhập, phiên, refresh token và mật khẩu")
public class AuthController implements IResponse {

    private final LoginService loginService;
    private final RefreshTokenService refreshTokenService;
    private final PasswordChangeService passwordChangeService;
    private final CurrentAccountService currentAccountService;
    private final ICurrentUserProvider currentUser;
    private final RefreshTokenCookies refreshCookies;

    public AuthController(LoginService loginService, RefreshTokenService refreshTokenService,
                          PasswordChangeService passwordChangeService, CurrentAccountService currentAccountService,
                          ICurrentUserProvider currentUser, RefreshTokenCookies refreshCookies) {
        this.loginService = loginService;
        this.refreshTokenService = refreshTokenService;
        this.passwordChangeService = passwordChangeService;
        this.currentAccountService = currentAccountService;
        this.currentUser = currentUser;
        this.refreshCookies = refreshCookies;
    }

    @PostMapping("/login")
    @Operation(operationId = "login", summary = "Đăng nhập bằng mã doanh nghiệp + username/email/phone + mật khẩu",
            description = "WEB: refresh token đặt trong cookie HttpOnly; MOBILE: trả trong body.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Đăng nhập thành công",
                    content = @Content(schema = @Schema(implementation = LoginResponse.class),
                            mediaType = "application/json")),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR / INVALID_REQUEST", content = @Content(
                    mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "INVALID_CREDENTIALS (không tiết lộ tài khoản)", content = @Content(
                    mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCOUNT_LOCKED / ACCOUNT_DISABLED (chỉ sau khi mật khẩu đúng)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Vượt giới hạn yêu cầu", content = @Content(
                    mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<ApiResponse<LoginResponse, Void>> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest,
                                               HttpServletResponse httpResponse) {
        LoginResponse response = loginService.login(request, clientInfo(httpRequest));
        return tokenResponse(response, request.clientType(), httpResponse);
    }

    @PostMapping("/refresh")
    @Operation(operationId = "refreshToken", summary = "Xoay vòng refresh token và cấp access token mới",
            description = "WEB: cookie refresh token + header X-XSRF-TOKEN. MOBILE: body {refreshToken}.")
    public ResponseEntity<ApiResponse<LoginResponse, Void>> refresh(@RequestBody(required = false) @Valid RefreshTokenRequest body,
                                                 HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String rawToken = presentedRefreshToken(httpRequest, body);
        if (rawToken == null) {
            throw new ApiException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        RefreshTokenService.RefreshResult result;
        try {
            result = refreshTokenService.refresh(rawToken, clientInfo(httpRequest));
        } catch (ApiException rejected) {
            if (refreshCookies.isPresent(httpRequest)) {
                refreshCookies.clear(httpResponse);
            }
            throw rejected;
        }
        LoginResponse response = new LoginResponse("Bearer", result.tokens().accessToken(),
                result.tokens().accessTokenExpiresAt(), result.tokens().refreshToken(),
                result.tokens().refreshTokenExpiresAt(), result.mustChangePassword(),
                new LoginResponse.UserSummary(result.userId(), result.displayName()));
        return tokenResponse(response, result.clientType(), httpResponse);
    }

    @PostMapping("/logout")
    @Operation(operationId = "logout", summary = "Đăng xuất phiên hiện tại (thu hồi refresh token family)",
            description = "Luôn trả 204. WEB cần header X-XSRF-TOKEN khi gửi kèm cookie refresh token.")
    public ResponseEntity<Void> logout(@RequestBody(required = false) @Valid RefreshTokenRequest body,
                                       HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String rawToken = presentedRefreshToken(httpRequest, body);
        if (rawToken != null) {
            refreshTokenService.logout(rawToken, clientInfo(httpRequest));
        }
        refreshCookies.clear(httpResponse);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/logout-all")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(operationId = "logoutAllDevices",
            summary = "Đăng xuất mọi thiết bị: thu hồi mọi refresh token và vô hiệu mọi access token (tăng token_version)")
    public ResponseEntity<Void> logoutAll(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        var principal = currentUser.requireCurrentUser();
        refreshTokenService.logoutAll(principal.organizationId(), principal.userId(), clientInfo(httpRequest));
        refreshCookies.clear(httpResponse);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/csrf")
    @Operation(operationId = "csrfToken", summary = "Lấy CSRF token cho refresh/logout bằng cookie (WEB)")
    public ResponseEntity<ApiResponse<CsrfTokenResponse, Void>> csrf(CsrfToken csrfToken) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ApiResponse<>(new CsrfTokenResponse(csrfToken.getHeaderName(), csrfToken.getToken()), null));
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(operationId = "currentUser", summary = "Danh tính của access token hiện tại")
    public ResponseEntity<ApiResponse<CurrentUserResponse, Void>> me() {
        return response(currentAccountService.describe(currentUser.requireCurrentUser()));
    }

    @PostMapping("/change-password")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(operationId = "changePassword", summary = "Đổi mật khẩu của chính mình",
            description = "Thành công: 204, mọi phiên bị thu hồi (token_version tăng) — client đăng nhập lại.")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                               HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        passwordChangeService.changePassword(currentUser.requireCurrentUser(), request.currentPassword(),
                request.newPassword(), clientInfo(httpRequest));
        refreshCookies.clear(httpResponse);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private ResponseEntity<ApiResponse<LoginResponse, Void>> tokenResponse(LoginResponse response, ClientType clientType,
                                                        HttpServletResponse httpResponse) {
        LoginResponse body = response;
        if (clientType == ClientType.WEB) {
            refreshCookies.write(httpResponse, response.refreshToken(), expiryOrNow(response.refreshTokenExpiresAt()));
            body = new LoginResponse(response.tokenType(), response.accessToken(), response.accessTokenExpiresAt(),
                    null, response.refreshTokenExpiresAt(), response.mustChangePassword(), response.user());
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(new ApiResponse<>(body, null));
    }

    private String presentedRefreshToken(HttpServletRequest request, RefreshTokenRequest body) {
        return refreshCookies.read(request)
                .orElse(body == null || body.refreshToken() == null || body.refreshToken().isBlank() ? null : body.refreshToken());
    }

    private static Instant expiryOrNow(Instant value) {
        return value == null ? Instant.now() : value;
    }

    static ClientInfo clientInfo(HttpServletRequest request) {
        return new ClientInfo(request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT),
                RequestIdFilter.currentTraceId(request));
    }
}
