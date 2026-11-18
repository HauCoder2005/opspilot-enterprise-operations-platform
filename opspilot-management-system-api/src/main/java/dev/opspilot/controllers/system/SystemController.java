package dev.opspilot.controllers.system;

import dev.opspilot.configs.AppProperties;
import dev.opspilot.dtos.system.PingResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${app.api-base-path:/api/v1}/system")
@Tag(name = "System", description = "Các API nền tảng và kiểm tra khả dụng công khai")
public class SystemController {

    private final AppProperties properties;

    public SystemController(AppProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/ping")
    @Operation(
            operationId = "pingSystem",
            summary = "Kiểm tra API đang hoạt động",
            description = "Trả về trạng thái tối thiểu của dịch vụ. API công khai, không kiểm tra cơ sở dữ liệu và chịu rate limit theo IP nguồn."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Dịch vụ API đang hoạt động",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = PingResponse.class),
                            examples = @ExampleObject(value = "{\"service\":\"opspilot-api\",\"status\":\"UP\"}")
                    )
            ),
            @ApiResponse(
                    responseCode = "429",
                    description = "Client đã vượt quá giới hạn yêu cầu; xem header Retry-After",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = "{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,\"detail\":\"Bạn đã gửi quá nhiều yêu cầu. Vui lòng thử lại sau.\"}")
                    )
            )
    })
    public PingResponse ping() {
        return new PingResponse(properties.name(), "UP");
    }
}
