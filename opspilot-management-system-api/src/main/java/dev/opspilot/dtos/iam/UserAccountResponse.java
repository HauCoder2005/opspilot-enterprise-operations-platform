package dev.opspilot.dtos.iam;

import dev.opspilot.enums.iam.UserStatus;

import java.time.Instant;

public record UserAccountResponse(
        long userId,
        String username,
        String email,
        String phone,
        UserStatus status,
        boolean mustChangePassword,
        Instant createdAt
) {
}
