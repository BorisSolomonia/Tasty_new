package ge.tastyerp.payment.todo;

import ge.tastyerp.common.dto.todo.TodoItemDto;
import ge.tastyerp.common.exception.ResourceNotFoundException;
import ge.tastyerp.common.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The shared to-do list on the payments page: open items first (newest on
 * top), then done items (most recently done on top). Anyone may add, tick or
 * delete; the UI asks before a delete, the service just does it.
 */
@Service
@RequiredArgsConstructor
public class TodoService {

    static final int MAX_TEXT = 500;

    private final TodoRepository repository;

    public List<TodoItemDto> list() {
        List<TodoItemDto> items = repository.findAll();
        items.sort(Comparator
                .comparing(TodoItemDto::isDone)
                .thenComparing((TodoItemDto t) -> t.isDone() ? nz(t.getDoneAt()) : nz(t.getCreatedAt()),
                        Comparator.reverseOrder()));
        return items;
    }

    public TodoItemDto add(String text, String author) {
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) {
            throw new ValidationException("text", "Write what needs doing");
        }
        if (clean.length() > MAX_TEXT) {
            throw new ValidationException("text", "Keep it under " + MAX_TEXT + " characters");
        }
        TodoItemDto item = TodoItemDto.builder()
                .id(UUID.randomUUID().toString())
                .text(clean)
                .done(false)
                .createdBy(blankToNull(author))
                .createdAt(LocalDateTime.now())
                .build();
        repository.save(item);
        return item;
    }

    public TodoItemDto setDone(String id, boolean done, String by) {
        TodoItemDto item = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("To-do item", id));
        item.setDone(done);
        item.setDoneBy(done ? blankToNull(by) : null);
        item.setDoneAt(done ? LocalDateTime.now() : null);
        repository.save(item);
        return item;
    }

    public void delete(String id) {
        repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("To-do item", id));
        repository.delete(id);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static LocalDateTime nz(LocalDateTime t) {
        return t == null ? LocalDateTime.MIN : t;
    }
}
