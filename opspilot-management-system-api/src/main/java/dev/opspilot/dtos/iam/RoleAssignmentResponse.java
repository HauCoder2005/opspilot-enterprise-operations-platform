package dev.opspilot.dtos.iam;

import java.time.Instant;

public record RoleAssignmentResponse(long assignmentId, long userId, long roleId, long scopeUnitId,
                                     boolean includeDescendants, Instant validFrom, Instant validTo) {
}
