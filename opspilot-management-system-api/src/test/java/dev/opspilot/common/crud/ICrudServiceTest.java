package dev.opspilot.common.crud;

import dev.opspilot.common.crud.ICrudService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;

class ICrudServiceTest {

    @Test
    void definesOnlyAnInterfaceContractWithoutDefaultPersistenceBehavior() {
        assertThat(ICrudService.class.isInterface()).isTrue();
        assertThat(ICrudService.class.getDeclaredMethods()).hasSize(5)
                .allMatch(method -> Modifier.isAbstract(method.getModifiers()));
        assertThat(ICrudService.class.getDeclaredFields()).isEmpty();
        assertThat(TestCrudService.class.getSuperclass()).isEqualTo(Object.class);
    }

    @Test
    void concreteGenericImplementationProvidesAllCrudOperations() {
        TestCrudService service = new TestCrudService();

        DummyResponse created = service.create(new DummyCreate("created"));
        DummyResponse updated = service.update(created.id(), new DummyUpdate("updated"));
        Page<DummyResponse> listed = service.list(Pageable.ofSize(10));

        assertThat(created).isEqualTo(new DummyResponse(1L, "created"));
        assertThat(service.getById(1L)).isEqualTo(new DummyResponse(1L, "updated"));
        assertThat(updated).isEqualTo(new DummyResponse(1L, "updated"));
        assertThat(listed.getContent()).containsExactly(new DummyResponse(1L, "updated"));

        service.delete(1L);
        assertThat(service.responses).isEmpty();
    }

    private record DummyCreate(String name) {
    }

    private record DummyUpdate(String name) {
    }

    private record DummyResponse(Long id, String name) {
    }

    private static final class TestCrudService
            implements ICrudService<Long, DummyCreate, DummyUpdate, DummyResponse> {

        private final List<DummyResponse> responses = new ArrayList<>();

        @Override
        public DummyResponse create(DummyCreate request) {
            DummyResponse response = new DummyResponse(1L, request.name());
            responses.add(response);
            return response;
        }

        @Override
        public DummyResponse getById(Long id) {
            return responses.stream()
                    .filter(response -> response.id().equals(id))
                    .findFirst()
                    .orElseThrow(NoSuchElementException::new);
        }

        @Override
        public Page<DummyResponse> list(Pageable pageable) {
            return new PageImpl<>(List.copyOf(responses), pageable, responses.size());
        }

        @Override
        public DummyResponse update(Long id, DummyUpdate request) {
            DummyResponse current = getById(id);
            DummyResponse updated = new DummyResponse(id, request.name());
            responses.set(responses.indexOf(current), updated);
            return updated;
        }

        @Override
        public void delete(Long id) {
            responses.remove(getById(id));
        }
    }
}
