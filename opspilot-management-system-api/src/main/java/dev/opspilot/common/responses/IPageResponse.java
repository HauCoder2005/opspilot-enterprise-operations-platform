package dev.opspilot.common.responses;

import dev.opspilot.dtos.ApiResponse;
import dev.opspilot.dtos.PageMeta;
import dev.opspilot.enums.ApiHttpStatus;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Objects;

/**
 * Stateless paginated response helpers for REST controllers.
 */
public interface IPageResponse extends IResponse {

    default <T> ResponseEntity<ApiResponse<List<T>, PageMeta>> pageResponse(Page<T> page) {
        return pageResponse(page, ApiHttpStatus.OK);
    }

    default <T> ResponseEntity<ApiResponse<List<T>, PageMeta>> pageResponse(
            Page<T> page,
            ApiHttpStatus status
    ) {
        Objects.requireNonNull(page, "page must not be null");
        return response(page.getContent(), PageMeta.from(page), status);
    }
}
