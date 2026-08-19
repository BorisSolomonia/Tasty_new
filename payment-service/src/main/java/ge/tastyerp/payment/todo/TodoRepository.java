package ge.tastyerp.payment.todo;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import ge.tastyerp.common.dto.todo.TodoItemDto;
import ge.tastyerp.common.util.FutureResults;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Firestore access for the shared payments to-do list. Data access only. */
@Repository
@RequiredArgsConstructor
public class TodoRepository {

    static final String COLLECTION = "payment_todos";

    private final Firestore firestore;

    public List<TodoItemDto> findAll() {
        QuerySnapshot snapshot = FutureResults.await(
                firestore.collection(COLLECTION).get(), "load payment to-do list");
        List<TodoItemDto> items = new ArrayList<>();
        for (QueryDocumentSnapshot doc : snapshot.getDocuments()) {
            items.add(toDto(doc.getId(), doc.getData()));
        }
        return items;
    }

    public Optional<TodoItemDto> findById(String id) {
        DocumentSnapshot doc = FutureResults.await(
                firestore.collection(COLLECTION).document(id).get(), "load to-do item");
        if (!doc.exists() || doc.getData() == null) {
            return Optional.empty();
        }
        return Optional.of(toDto(doc.getId(), doc.getData()));
    }

    public void save(TodoItemDto item) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("text", item.getText());
        data.put("done", item.isDone());
        data.put("createdBy", item.getCreatedBy());
        data.put("createdAt", item.getCreatedAt() == null ? null : item.getCreatedAt().toString());
        data.put("doneBy", item.getDoneBy());
        data.put("doneAt", item.getDoneAt() == null ? null : item.getDoneAt().toString());
        FutureResults.await(firestore.collection(COLLECTION).document(item.getId()).set(data), "save to-do item");
    }

    public void delete(String id) {
        FutureResults.await(firestore.collection(COLLECTION).document(id).delete(), "delete to-do item");
    }

    private static TodoItemDto toDto(String id, Map<String, Object> d) {
        return TodoItemDto.builder()
                .id(id)
                .text(str(d.get("text")))
                .done(Boolean.TRUE.equals(d.get("done")))
                .createdBy(str(d.get("createdBy")))
                .createdAt(time(d.get("createdAt")))
                .doneBy(str(d.get("doneBy")))
                .doneAt(time(d.get("doneAt")))
                .build();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static LocalDateTime time(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(String.valueOf(o));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
