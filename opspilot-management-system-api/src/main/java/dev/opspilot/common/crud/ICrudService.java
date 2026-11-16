package dev.opspilot.common.crud;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Defines reusable CRUD operations for simple reference and catalog resources.
 * Implementations remain responsible for visibility, authorization, validation,
 * transaction boundaries, and deletion policy.
 *
 * @param <ID> resource identifier type
 * @param <C> create request type
 * @param <U> update request type
 * @param <R> response type
 */
public interface ICrudService<ID, C, U, R> {

    R create(C request);

    R getById(ID id);

    Page<R> list(Pageable pageable);

    R update(ID id, U request);

    void delete(ID id);
}
