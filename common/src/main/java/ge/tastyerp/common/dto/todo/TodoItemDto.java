package ge.tastyerp.common.dto.todo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One item of the shared to-do list on the payments page. Shared means
 * shared: every user sees the same list, anyone can add, tick, or delete.
 * {@code createdBy} / {@code doneBy} are self-declared names (there is no
 * authentication), recorded so a teammate can see who wrote what.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TodoItemDto {
    private String id;
    private String text;
    private boolean done;
    private String createdBy;
    private LocalDateTime createdAt;
    private String doneBy;
    private LocalDateTime doneAt;
}
