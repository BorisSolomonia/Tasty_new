package ge.tastyerp.payment.repository;

import com.google.cloud.firestore.*;
import ge.tastyerp.common.dto.audit.AuditExceptionDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

/**
 * Firestore repository for the {@code audit_exceptions} collection (BOR-74).
 *
 * Document id = exception id (generated). Stores both system-generated and
 * manually created reconciliation exceptions.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class AuditExceptionRepository {

    private static final String COLLECTION = "audit_exceptions";

    private final Firestore firestore;

    public List<AuditExceptionDto> findAll() {
        try {
            QuerySnapshot snapshot = firestore.collection(COLLECTION).get().get();
            return snapshot.getDocuments().stream().map(this::toDto).toList();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching audit exceptions: {}", e.getMessage());
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        }
    }

    public AuditExceptionDto save(AuditExceptionDto dto) {
        try {
            if (dto.getId() == null || dto.getId().isBlank()) {
                dto.setId(UUID.randomUUID().toString());
            }
            if (dto.getCreatedAt() == null) {
                dto.setCreatedAt(LocalDateTime.now());
            }
            firestore.collection(COLLECTION).document(dto.getId()).set(toMap(dto)).get();
            log.debug("Saved audit exception {}", dto.getId());
            return dto;
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error saving audit exception: {}", e.getMessage());
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to save audit exception", e);
        }
    }

    public void delete(String id) {
        try {
            firestore.collection(COLLECTION).document(id).delete().get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error deleting audit exception {}: {}", id, e.getMessage());
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to delete audit exception", e);
        }
    }

    // ==================== MAPPING ====================

    private Map<String, Object> toMap(AuditExceptionDto dto) {
        Map<String, Object> data = new HashMap<>();
        data.put("type", dto.getType());
        data.put("description", dto.getDescription());
        data.put("referenceId", dto.getReferenceId());
        data.put("customerId", dto.getCustomerId());
        data.put("amount", dto.getAmount() != null ? dto.getAmount().doubleValue() : null);
        data.put("date", toTimestamp(dto.getDate()));
        data.put("status", dto.getStatus() != null ? dto.getStatus() : "OPEN");
        data.put("manual", dto.isManual());
        data.put("createdAt", toTimestampDt(dto.getCreatedAt()));
        data.put("createdBy", dto.getCreatedBy());
        return data;
    }

    private AuditExceptionDto toDto(DocumentSnapshot doc) {
        Double amount = doc.getDouble("amount");
        return AuditExceptionDto.builder()
                .id(doc.getId())
                .type(doc.getString("type"))
                .description(doc.getString("description"))
                .referenceId(doc.getString("referenceId"))
                .customerId(doc.getString("customerId"))
                .amount(amount != null ? BigDecimal.valueOf(amount) : null)
                .date(toLocalDate(doc.get("date")))
                .status(doc.getString("status"))
                .manual(Boolean.TRUE.equals(doc.getBoolean("manual")))
                .createdAt(toLocalDateTime(doc.get("createdAt")))
                .createdBy(doc.getString("createdBy"))
                .build();
    }

    private com.google.cloud.Timestamp toTimestamp(LocalDate date) {
        if (date == null) return null;
        return com.google.cloud.Timestamp.of(
                java.util.Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant()));
    }

    private com.google.cloud.Timestamp toTimestampDt(LocalDateTime dt) {
        if (dt == null) return null;
        return com.google.cloud.Timestamp.of(
                java.util.Date.from(dt.atZone(ZoneId.systemDefault()).toInstant()));
    }

    private LocalDate toLocalDate(Object value) {
        if (value instanceof com.google.cloud.Timestamp ts) {
            return ts.toDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        }
        return null;
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof com.google.cloud.Timestamp ts) {
            return ts.toDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
        }
        return null;
    }
}
