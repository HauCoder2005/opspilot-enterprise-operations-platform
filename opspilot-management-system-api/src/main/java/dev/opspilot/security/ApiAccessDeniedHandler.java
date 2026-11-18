package dev.opspilot.security;

import dev.opspilot.enums.ErrorCode;
import dev.opspilot.exceptions.ProblemResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** 403 problem+json: phân biệt thiếu CSRF token, bắt buộc đổi mật khẩu và từ chối quyền thông thường. */
@Component
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private final ProblemResponses problems;

    public ApiAccessDeniedHandler(ProblemResponses problems) {
        this.problems = problems;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        problems.write(request, response, codeFor(exception));
    }

    private static ErrorCode codeFor(AccessDeniedException exception) {
        if (exception instanceof CsrfException) {
            return ErrorCode.CSRF_TOKEN_INVALID;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof OpsPilotPrincipal principal
                && principal.mustChangePassword()) {
            return ErrorCode.PASSWORD_CHANGE_REQUIRED;
        }
        return ErrorCode.ACCESS_DENIED;
    }
}
