package dev.opspilot.controllers.system;

import dev.opspilot.configs.AppProperties;
import dev.opspilot.dtos.system.PingResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SystemControllerTest {

    @Test
    void returnsConfiguredServiceName() {
        AppProperties properties = properties(true, 60, 20);
        SystemController controller = new SystemController(properties);

        assertThat(controller.ping()).isEqualTo(new PingResponse("opspilot-api", "UP"));
    }

    private static AppProperties properties(boolean enabled, int requestsPerMinute, int burstCapacity) {
        return new AppProperties(
                "opspilot-api",
                "/api/v1",
                new AppProperties.RateLimit(enabled, requestsPerMinute, burstCapacity, 100, 15),
                new AppProperties.Cors(List.of("http://localhost:3000"))
        );
    }
}
