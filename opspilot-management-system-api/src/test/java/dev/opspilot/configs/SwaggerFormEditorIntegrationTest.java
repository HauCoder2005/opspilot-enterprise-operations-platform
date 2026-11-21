package dev.opspilot.auth;

import dev.opspilot.support.ApiClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

class SwaggerFormEditorIntegrationTest extends AbstractAuthIntegrationTest {

    @Test
    void swaggerUiLoadsAndRegistersSchemaDrivenFormPlugin() {
        ApiClient.Response index = api.get("/swagger-ui/index.html");
        ApiClient.Response initializer = api.get("/swagger-ui/swagger-initializer.js");
        ApiClient.Response plugin = api.get("/swagger-ui/opspilot-form-editor.js");

        assertThat(index.status()).isEqualTo(200);
        assertThat(index.rawBody()).contains("opspilot-form-editor.js");
        assertThat(initializer.status()).isEqualTo(200);
        assertThat(initializer.rawBody()).contains("OpsPilotFormEditorPlugin");
        assertThat(plugin.status()).isEqualTo(200);
        assertThat(plugin.rawBody())
                .contains("RequestBodyEditor")
                .contains("SchemaAwareRequestBody")
                .contains("schemaAwareEditors")
                .contains("JSON.stringify")
                .contains("type = \"password\"")
                .contains("React.createElement(\"select\"")
                .contains("Advanced JSON editor");
    }

    @Test
    void openApiSchemasExposeFieldsValidationEnumsAndJsonContracts() {
        ApiClient.Response response = api.get("/v3/api-docs");
        JsonNode document = ApiClient.JSON.readTree(response.rawBody());
        JsonNode schemas = document.path("components").path("schemas");
        JsonNode login = schemas.path("LoginRequest");

        assertThat(response.status()).isEqualTo(200);
        assertThat(login.path("required").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("orgCode", "identifier", "password", "clientType");
        assertThat(login.path("properties").path("password").path("format").asString()).isEqualTo("password");
        assertThat(login.path("properties").path("clientType").path("enum").valueStream()
                .map(JsonNode::asString).toList()).containsExactly("WEB", "MOBILE");
        assertThat(schemas.path("CreateUserRequest").path("properties").propertyNames())
                .contains("employeeId", "username", "email", "phone");
        assertThat(schemas.path("AssignRoleRequest").path("properties").propertyNames())
                .contains("roleId", "scopeUnitId", "includeDescendants", "validFrom", "validTo", "note");
        assertThat(document.path("paths").path("/api/v1/auth/login").path("post")
                .path("requestBody").path("content").has("application/json")).isTrue();
        assertThat(document.path("paths").path("/api/v1/iam/users").path("get").path("parameters").isArray()).isTrue();
        assertThat(document.path("components").path("securitySchemes").path("bearerAuth")
                .path("scheme").asString()).isEqualTo("bearer");
    }
}
