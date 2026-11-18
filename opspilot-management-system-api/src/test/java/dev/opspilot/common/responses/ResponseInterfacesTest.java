package dev.opspilot.common.responses;

import dev.opspilot.common.responses.IPageResponse;
import dev.opspilot.common.responses.IResponse;
import dev.opspilot.dtos.ApiResponse;
import dev.opspilot.dtos.PageMeta;
import dev.opspilot.enums.ApiHttpStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResponseInterfacesTest {

    private final TestResponder responder = new TestResponder();
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void normalResponseDefaultsToOkAndOmitsAbsentMetadata() throws Exception {
        ResponseEntity<ApiResponse<Example, Void>> result =
                responder.response(new Example(1L, "Example"));

        JsonNode json = json(result);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(json.path("data").path("id").asLong()).isEqualTo(1L);
        assertThat(json.path("data").path("name").stringValue()).isEqualTo("Example");
        assertThat(json.path("meta").isMissingNode()).isTrue();
    }

    @Test
    void responseSerializesTypedMetadataAndUsesSelectedStatus() throws Exception {
        ResponseEntity<ApiResponse<Example, MessageMeta>> result = responder.response(
                new Example(1L, "Example"),
                new MessageMeta("Success"),
                ApiHttpStatus.CREATED
        );

        JsonNode json = json(result);
        assertThat(result.getStatusCode().value()).isEqualTo(201);
        assertThat(json.path("meta").path("message").stringValue()).isEqualTo("Success");
    }

    @Test
    void pageInterfaceInheritsNormalResponseHelpers() {
        assertThat(IPageResponse.class.getInterfaces()).containsExactly(IResponse.class);

        ResponseEntity<ApiResponse<Example, Void>> result =
                responder.response(new Example(2L, "Inherited"), ApiHttpStatus.ACCEPTED);

        assertThat(result.getStatusCode().value()).isEqualTo(202);
        assertThat(Objects.requireNonNull(result.getBody()).data().name()).isEqualTo("Inherited");
    }

    @Test
    void pageResponseUsesSpringPageContentAndMetadata() throws Exception {
        Page<Example> page = new PageImpl<>(
                List.of(new Example(1L, "A"), new Example(2L, "B")),
                PageRequest.of(0, 2),
                5
        );

        ResponseEntity<ApiResponse<List<Example>, PageMeta>> result = responder.pageResponse(page);
        JsonNode json = json(result);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(json.path("data").size()).isEqualTo(2);
        assertThat(json.path("meta").path("page").asInt()).isZero();
        assertThat(json.path("meta").path("size").asInt()).isEqualTo(2);
        assertThat(json.path("meta").path("totalElements").asLong()).isEqualTo(5L);
        assertThat(json.path("meta").path("totalPages").asInt()).isEqualTo(3);
        assertThat(json.path("meta").path("hasNext").asBoolean()).isTrue();
        assertThat(json.path("meta").path("hasPrevious").asBoolean()).isFalse();
    }

    @Test
    void emptyPageProducesEmptyDataAndValidMetadata() throws Exception {
        Page<Example> page = Page.empty(PageRequest.of(0, 20));

        JsonNode json = json(responder.pageResponse(page, ApiHttpStatus.OK));

        assertThat(json.path("data").isArray()).isTrue();
        assertThat(json.path("data")).isEmpty();
        assertThat(json.path("meta").path("page").asInt()).isZero();
        assertThat(json.path("meta").path("size").asInt()).isEqualTo(20);
        assertThat(json.path("meta").path("totalElements").asLong()).isZero();
        assertThat(json.path("meta").path("totalPages").asInt()).isZero();
    }

    @Test
    void alreadyWrappedResponseIsNotWrappedAgain() throws Exception {
        ApiResponse<Example, MessageMeta> body = new ApiResponse<>(
                new Example(1L, "Example"),
                new MessageMeta("Existing")
        );

        ResponseEntity<ApiResponse<Example, MessageMeta>> result = responder.response(body);
        JsonNode json = json(result);

        assertThat(result.getBody()).isSameAs(body);
        assertThat(json.path("data").path("data").isMissingNode()).isTrue();
        assertThat(json.path("meta").path("message").stringValue()).isEqualTo("Existing");
    }

    @Test
    void bodylessStatusesHaveNoBodyAndInvalidCombinationsAreRejected() {
        assertBodyless(ApiHttpStatus.NO_CONTENT, 204);
        assertBodyless(ApiHttpStatus.RESET_CONTENT, 205);
        assertBodyless(ApiHttpStatus.NOT_MODIFIED, 304);

        assertThatThrownBy(() -> responder.response("body", ApiHttpStatus.NO_CONTENT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> responder.response("body", ApiHttpStatus.RESET_CONTENT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> responder.response("body", ApiHttpStatus.NOT_MODIFIED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> responder.response(ApiHttpStatus.CONTINUE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> responder.response(ApiHttpStatus.MOVED_PERMANENTLY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> responder.response(ApiHttpStatus.USE_PROXY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertBodyless(ApiHttpStatus status, int expectedCode) {
        ResponseEntity<Void> result = responder.response(status);
        assertThat(result.getStatusCode().value()).isEqualTo(expectedCode);
        assertThat(result.getBody()).isNull();
    }

    private JsonNode json(ResponseEntity<?> response) throws Exception {
        return objectMapper.readTree(objectMapper.writeValueAsString(
                Objects.requireNonNull(response.getBody())
        ));
    }

    private record Example(Long id, String name) {
    }

    private record MessageMeta(String message) {
    }

    private static final class TestResponder implements IPageResponse {
    }
}
