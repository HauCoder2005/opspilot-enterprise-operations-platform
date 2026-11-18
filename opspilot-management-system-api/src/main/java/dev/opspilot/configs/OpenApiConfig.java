package dev.opspilot.configs;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    OpenAPI opsPilotOpenApi(AppProperties properties) {
        return new OpenAPI()
                .info(new Info()
                        .title(properties.name())
                        .version("v1")
                        .description("REST API nền tảng của OpsPilot dành cho doanh nghiệp."))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Access token từ POST /auth/login hoặc /auth/refresh (hết hạn sau 15 phút)")));
    }
}
