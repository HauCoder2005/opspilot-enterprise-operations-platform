package dev.opspilot.dtos.iam;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record AssignRoleRequest(
        @Positive long roleId,
        @Positive long scopeUnitId,
        boolean includeDescendants,
        Instant validFrom,
        @Future Instant validTo,
        @Size(max = 500) String note
) {
}
