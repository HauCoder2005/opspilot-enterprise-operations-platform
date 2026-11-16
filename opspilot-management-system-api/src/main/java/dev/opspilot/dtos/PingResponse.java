package dev.opspilot.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Trạng thái công khai tối thiểu của dịch vụ API")
public record PingResponse(
        @Schema(description = "Tên dịch vụ", example = "opspilot-api") String service,
        @Schema(description = "Trạng thái dịch vụ", example = "UP", allowableValues = "UP") String status
) {
}
