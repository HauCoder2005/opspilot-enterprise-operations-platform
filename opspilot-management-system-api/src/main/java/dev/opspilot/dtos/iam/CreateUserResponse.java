package dev.opspilot.dtos.iam;

public record CreateUserResponse(long userId, long employeeId, String username, String temporaryPassword,
                                 boolean mustChangePassword) {
    @Override
    public String toString() {
        return "CreateUserResponse[userId=" + userId + ", employeeId=" + employeeId + ", username=" + username
                + ", temporaryPassword=***, mustChangePassword=" + mustChangePassword + "]";
    }
}
