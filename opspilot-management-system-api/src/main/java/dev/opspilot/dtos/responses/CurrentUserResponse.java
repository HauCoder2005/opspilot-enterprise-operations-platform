package dev.opspilot.dtos.responses;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Danh tính của access token hiện tại")
public record CurrentUserResponse(long userId, long organizationId, String username, String displayName,
                                  boolean mustChangePassword) {
}
