package dev.opspilot.dtos.iam;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @Positive long employeeId,
        @NotBlank @Size(max = 50) String username,
        @Email @Size(max = 255) String email,
        @Size(max = 20) String phone
) {
}
