package dev.opspilot.exceptions;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.opspilot.exceptions.BusinessConflictException;
import dev.opspilot.exceptions.GlobalExceptionHandler;
import dev.opspilot.exceptions.InvalidBusinessOperationException;
import dev.opspilot.exceptions.ResourceNotFoundException;
import dev.opspilot.security.filters.RequestIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class GlobalExceptionHandlerTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final Logger handlerLogger =
            (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        logAppender.start();
        handlerLogger.addAppender(logAppender);
        mockMvc = standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    @AfterEach
    void tearDown() {
        handlerLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Test
    void invalidRequestReturnsSafeValidationResponse() throws Exception {
        MvcResult result = mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"invalid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Invalid request data"))
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(header().exists(RequestIdFilter.REQUEST_ID_HEADER))
                .andReturn();

        assertTraceMatchesHeader(result);
    }

    @Test
    void semanticApplicationExceptionsUseTheirExpectedStatuses() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("BUSINESS_CONFLICT"));

        mockMvc.perform(get("/test/unprocessable"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.code").value("INVALID_BUSINESS_OPERATION"));
    }

    @Test
    void unexpectedExceptionIsLoggedWithTraceAndDoesNotLeakInternals() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        String traceId = result.getResponse().getHeader(RequestIdFilter.REQUEST_ID_HEADER);
        assertThat(responseBody)
                .doesNotContain("IllegalStateException")
                .doesNotContain("password")
                .doesNotContain("SELECT")
                .doesNotContain("/tmp")
                .doesNotContain("dev.opspilot");

        assertThat(logAppender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getMDCPropertyMap()).containsEntry(RequestIdFilter.TRACE_ID_MDC_KEY, traceId);
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getThrowableProxy().getMessage())
                    .doesNotContain("password", "SELECT", "/tmp");
        });
    }

    @Test
    void consecutiveRequestsReceiveDifferentTraceIds() throws Exception {
        MvcResult first = mockMvc.perform(get("/test/not-found")).andReturn();
        MvcResult second = mockMvc.perform(get("/test/not-found")).andReturn();

        assertThat(first.getResponse().getHeader(RequestIdFilter.REQUEST_ID_HEADER))
                .isNotEqualTo(second.getResponse().getHeader(RequestIdFilter.REQUEST_ID_HEADER));
    }

    private void assertTraceMatchesHeader(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("traceId").stringValue())
                .isEqualTo(result.getResponse().getHeader(RequestIdFilter.REQUEST_ID_HEADER));
    }

    private record ValidationRequest(@NotBlank @Email String email) {
    }

    @RestController
    private static final class TestController {

        @PostMapping("/test/validation")
        void validate(@Valid @RequestBody ValidationRequest request) {
        }

        @GetMapping("/test/not-found")
        void notFound() {
            throw new ResourceNotFoundException("Requested record was not found");
        }

        @GetMapping("/test/conflict")
        void conflict() {
            throw new BusinessConflictException("The request conflicts with current state");
        }

        @GetMapping("/test/unprocessable")
        void unprocessable() {
            throw new InvalidBusinessOperationException("The operation is not allowed");
        }

        @GetMapping("/test/unexpected")
        void unexpected() {
            throw new IllegalStateException(
                    "password=secret SELECT * FROM confidential at /tmp/private-file"
            );
        }
    }
}
