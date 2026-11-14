package dev.opspilot.configs;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @NotBlank String name,
        @NotBlank String apiBasePath,
        @Valid RateLimit rateLimit,
        @Valid Cors cors,
        @Valid Security security
) {
    public record RateLimit(
            boolean enabled,
            @Min(1) int requestsPerMinute,
            @Min(1) int burstCapacity,
            @Min(100) @Max(1_000_000) long maxClients,
            @Min(1) int clientIdleMinutes
    ) {
        public Duration clientIdleDuration() {
            return Duration.ofMinutes(clientIdleMinutes);
        }
    }

    public record Cors(@NotEmpty List<@NotBlank String> allowedOrigins) {
    }

    public record Security(
            boolean jwtEnabled,
            @Min(1) int jwtAccessTtlMinutes,
            @Min(1) int jwtRefreshTtlDays
    ) {
    }
}
