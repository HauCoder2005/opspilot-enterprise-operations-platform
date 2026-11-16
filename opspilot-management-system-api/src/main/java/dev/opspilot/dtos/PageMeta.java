package dev.opspilot.dtos;

import org.springframework.data.domain.Page;

/**
 * Pagination metadata derived directly from a Spring Data page.
 */
public record PageMeta(
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious
) {

    public static PageMeta from(Page<?> source) {
        return new PageMeta(
                source.getNumber(),
                source.getSize(),
                source.getTotalElements(),
                source.getTotalPages(),
                source.hasNext(),
                source.hasPrevious()
        );
    }
}
