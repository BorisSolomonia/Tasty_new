package ge.tastyerp.payment.todo;

import ge.tastyerp.common.dto.ApiResponse;
import ge.tastyerp.common.dto.todo.TodoItemDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Shared to-do list on the payments page. No business logic here. */
@RestController
@RequestMapping("/api/payments/todos")
@RequiredArgsConstructor
@Tag(name = "Payments to-do list", description = "Shared checklist at the bottom of the payments page")
public class TodoController {

    private final TodoService todoService;

    @GetMapping
    @Operation(summary = "Every item, open first")
    public ResponseEntity<ApiResponse<List<TodoItemDto>>> list() {
        return ResponseEntity.ok(ApiResponse.success(todoService.list()));
    }

    @PostMapping
    @Operation(summary = "Add an item")
    public ResponseEntity<ApiResponse<TodoItemDto>> add(@RequestBody Map<String, String> body) {
        TodoItemDto created = todoService.add(body.get("text"), body.get("author"));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(created, "Added"));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Tick or untick an item")
    public ResponseEntity<ApiResponse<TodoItemDto>> setDone(
            @PathVariable String id,
            @RequestParam boolean done,
            @RequestParam(required = false) String by) {
        return ResponseEntity.ok(ApiResponse.success(todoService.setDone(id, done, by)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete an item (the UI confirms first)")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String id) {
        todoService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Deleted"));
    }
}
