package dev.opspilot;

import dev.opspilot.enums.ApiHttpStatus;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class ApiHttpStatusTest {

    @Test
    void coversEveryApplicableRegisteredStatusWithOfficialReasonPhrase() {
        assertThat(ApiHttpStatus.values())
                .extracting(ApiHttpStatus::getCode, ApiHttpStatus::getReasonPhrase)
                .containsExactly(
                        tuple(100, "Continue"),
                        tuple(101, "Switching Protocols"),
                        tuple(102, "Processing"),
                        tuple(103, "Early Hints"),
                        tuple(200, "OK"),
                        tuple(201, "Created"),
                        tuple(202, "Accepted"),
                        tuple(203, "Non-Authoritative Information"),
                        tuple(204, "No Content"),
                        tuple(205, "Reset Content"),
                        tuple(206, "Partial Content"),
                        tuple(207, "Multi-Status"),
                        tuple(208, "Already Reported"),
                        tuple(226, "IM Used"),
                        tuple(300, "Multiple Choices"),
                        tuple(301, "Moved Permanently"),
                        tuple(302, "Found"),
                        tuple(303, "See Other"),
                        tuple(304, "Not Modified"),
                        tuple(305, "Use Proxy"),
                        tuple(307, "Temporary Redirect"),
                        tuple(308, "Permanent Redirect"),
                        tuple(400, "Bad Request"),
                        tuple(401, "Unauthorized"),
                        tuple(402, "Payment Required"),
                        tuple(403, "Forbidden"),
                        tuple(404, "Not Found"),
                        tuple(405, "Method Not Allowed"),
                        tuple(406, "Not Acceptable"),
                        tuple(407, "Proxy Authentication Required"),
                        tuple(408, "Request Timeout"),
                        tuple(409, "Conflict"),
                        tuple(410, "Gone"),
                        tuple(411, "Length Required"),
                        tuple(412, "Precondition Failed"),
                        tuple(413, "Content Too Large"),
                        tuple(414, "URI Too Long"),
                        tuple(415, "Unsupported Media Type"),
                        tuple(416, "Range Not Satisfiable"),
                        tuple(417, "Expectation Failed"),
                        tuple(421, "Misdirected Request"),
                        tuple(422, "Unprocessable Content"),
                        tuple(423, "Locked"),
                        tuple(424, "Failed Dependency"),
                        tuple(425, "Too Early"),
                        tuple(426, "Upgrade Required"),
                        tuple(428, "Precondition Required"),
                        tuple(429, "Too Many Requests"),
                        tuple(431, "Request Header Fields Too Large"),
                        tuple(451, "Unavailable For Legal Reasons"),
                        tuple(500, "Internal Server Error"),
                        tuple(501, "Not Implemented"),
                        tuple(502, "Bad Gateway"),
                        tuple(503, "Service Unavailable"),
                        tuple(504, "Gateway Timeout"),
                        tuple(505, "HTTP Version Not Supported"),
                        tuple(506, "Variant Also Negotiates"),
                        tuple(507, "Insufficient Storage"),
                        tuple(508, "Loop Detected"),
                        tuple(511, "Network Authentication Required")
                );
    }

    @Test
    void codesAreUniqueAndLookupIsPredictable() {
        assertThat(Arrays.stream(ApiHttpStatus.values()).map(ApiHttpStatus::getCode))
                .doesNotHaveDuplicates();
        assertThat(ApiHttpStatus.fromCode(202)).isEqualTo(ApiHttpStatus.ACCEPTED);
        assertThat(ApiHttpStatus.fromCode(404)).isEqualTo(ApiHttpStatus.NOT_FOUND);

        assertThatThrownBy(() -> ApiHttpStatus.fromCode(999))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999");
    }

    @Test
    void convertsToSpringStatusAndIdentifiesCategory() {
        assertThat(ApiHttpStatus.ACCEPTED.toHttpStatusCode().value()).isEqualTo(202);
        assertThat(ApiHttpStatus.NOT_FOUND.toHttpStatusCode().value()).isEqualTo(404);
        assertThat(ApiHttpStatus.CONTINUE.category()).isEqualTo(ApiHttpStatus.Category.INFORMATIONAL);
        assertThat(ApiHttpStatus.OK.category()).isEqualTo(ApiHttpStatus.Category.SUCCESS);
        assertThat(ApiHttpStatus.NOT_MODIFIED.category()).isEqualTo(ApiHttpStatus.Category.REDIRECTION);
        assertThat(ApiHttpStatus.BAD_REQUEST.category()).isEqualTo(ApiHttpStatus.Category.CLIENT_ERROR);
        assertThat(ApiHttpStatus.INTERNAL_SERVER_ERROR.category())
                .isEqualTo(ApiHttpStatus.Category.SERVER_ERROR);
    }

    @Test
    void excludesExpiredUnusedObsoleteAndUnassignedCodes() {
        assertThatThrownBy(() -> ApiHttpStatus.fromCode(104)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiHttpStatus.fromCode(306)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiHttpStatus.fromCode(418)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiHttpStatus.fromCode(427)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiHttpStatus.fromCode(510)).isInstanceOf(IllegalArgumentException.class);
    }
}
