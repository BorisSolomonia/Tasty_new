package ge.tastyerp.payment.todo;

import ge.tastyerp.common.dto.todo.TodoItemDto;
import ge.tastyerp.common.exception.ResourceNotFoundException;
import ge.tastyerp.common.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The shared payments to-do list: what it refuses, how it orders, what a tick records. */
class TodoServiceTest {

    private final TodoRepository repository = mock(TodoRepository.class);
    private final TodoService service = new TodoService(repository);

    private static TodoItemDto item(String id, String text, boolean done, LocalDateTime created, LocalDateTime doneAt) {
        return TodoItemDto.builder().id(id).text(text).done(done).createdAt(created).doneAt(doneAt).build();
    }

    @Test
    void addTrimsRecordsTheAuthorAndRefusesBlankOrOverlongText() {
        TodoItemDto created = service.add("  call ვაჟა პაპა about the check  ", "  Boris ");
        assertEquals("call ვაჟა პაპა about the check", created.getText());
        assertEquals("Boris", created.getCreatedBy());
        assertFalse(created.isDone());
        assertNotNull(created.getId());
        assertNotNull(created.getCreatedAt());
        verify(repository).save(created);

        assertThrows(ValidationException.class, () -> service.add("   ", "b"));
        assertThrows(ValidationException.class, () -> service.add("x".repeat(TodoService.MAX_TEXT + 1), "b"));
    }

    @Test
    void listPutsOpenItemsFirstNewestOnTopThenDoneItemsMostRecentlyDoneOnTop() {
        LocalDateTime t = LocalDateTime.of(2026, 8, 19, 10, 0);
        when(repository.findAll()).thenReturn(new ArrayList<>(List.of(
                item("d-old", "done early", true, t.minusDays(3), t.minusDays(2)),
                item("o-old", "open old", false, t.minusDays(2), null),
                item("d-new", "done late", true, t.minusDays(3), t.minusHours(1)),
                item("o-new", "open new", false, t, null))));
        List<String> order = service.list().stream().map(TodoItemDto::getId).toList();
        assertEquals(List.of("o-new", "o-old", "d-new", "d-old"), order);
    }

    @Test
    void tickingRecordsWhoAndWhenUntickingClearsIt() {
        when(repository.findById("a")).thenReturn(Optional.of(item("a", "x", false, LocalDateTime.now(), null)));
        TodoItemDto ticked = service.setDone("a", true, "Nino");
        assertTrue(ticked.isDone());
        assertEquals("Nino", ticked.getDoneBy());
        assertNotNull(ticked.getDoneAt());

        when(repository.findById("a")).thenReturn(Optional.of(ticked));
        TodoItemDto unticked = service.setDone("a", false, "Nino");
        assertFalse(unticked.isDone());
        assertNull(unticked.getDoneBy());
        assertNull(unticked.getDoneAt());
        ArgumentCaptor<TodoItemDto> saved = ArgumentCaptor.forClass(TodoItemDto.class);
        verify(repository, org.mockito.Mockito.times(2)).save(saved.capture());
    }

    @Test
    void deleteRemovesAnExistingItemAndRefusesAnUnknownOne() {
        when(repository.findById("a")).thenReturn(Optional.of(item("a", "x", false, LocalDateTime.now(), null)));
        service.delete("a");
        verify(repository).delete("a");

        when(repository.findById("nope")).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.delete("nope"));
        verify(repository, never()).delete("nope");
        verify(repository, never()).save(any());
    }
}
