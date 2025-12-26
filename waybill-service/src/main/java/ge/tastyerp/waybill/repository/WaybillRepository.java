package ge.tastyerp.waybill.repository;

import com.google.cloud.firestore.*;
import ge.tastyerp.common.util.AmountUtils;
import ge.tastyerp.common.dto.waybill.WaybillDto;
import ge.tastyerp.common.dto.waybill.WaybillType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 * Repository for waybills stored in Firebase.
 * Data access only - NO business logic here.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class WaybillRepository {

    private static final String COLLECTION = "waybills";

    private final Firestore firestore;

    public List<WaybillDto> findByFilters(WaybillType type, String customerId, LocalDate startDate, LocalDate endDate, Boolean afterCutoffOnly) {
        try {
            Query query = firestore.collection(COLLECTION);

            // Backwards-compat: old documents have no "type" field (assume SALE).
            // Only enforce Firestore-side filtering for PURCHASE; SALE is handled in-memory.
            if (type == WaybillType.PURCHASE) {
                query = query.whereEqualTo("type", WaybillType.PURCHASE.name());
            }

            if (customerId != null && !customerId.isBlank()) {
                query = query.whereEqualTo("customerId", customerId);
            }

            if (Boolean.TRUE.equals(afterCutoffOnly)) {
                query = query.whereEqualTo("isAfterCutoff", true);
            }

            // NOTE: For PURCHASE waybills with date filters, we need a composite index on (type, date).
            // Until the index is created, we filter dates in-memory to avoid empty results.
            boolean needsInMemoryDateFilter = (type == WaybillType.PURCHASE) && (startDate != null || endDate != null);

            if (!needsInMemoryDateFilter) {
                // Apply date filters in Firestore query (works for SALE or when no date filter)
                if (startDate != null) {
                    query = query.whereGreaterThanOrEqualTo("date",
                            com.google.cloud.Timestamp.of(java.util.Date.from(
                                    startDate.atStartOfDay(ZoneId.systemDefault()).toInstant())));
                }

                if (endDate != null) {
                    query = query.whereLessThan("date",
                            com.google.cloud.Timestamp.of(java.util.Date.from(
                                    endDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant())));
                }
            }

            QuerySnapshot snapshot = query.get().get();
            var stream = snapshot.getDocuments().stream()
                    .map(this::documentToDto)
                    .filter(w -> type == null || type == WaybillType.PURCHASE || w.getType() != WaybillType.PURCHASE);

            // Apply in-memory date filtering for PURCHASE waybills
            if (needsInMemoryDateFilter) {
                if (startDate != null) {
                    stream = stream.filter(w -> w.getDate() != null && !w.getDate().isBefore(startDate));
                }
                if (endDate != null) {
                    stream = stream.filter(w -> w.getDate() != null && !w.getDate().isAfter(endDate));
                }
            }

            return stream.toList();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching waybills by filters: {}", e.getMessage());
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        }
    }

    public List<WaybillDto> findAll() {
        try {
            QuerySnapshot snapshot = firestore.collection(COLLECTION).get().get();
            return snapshot.getDocuments().stream()
                    .map(this::documentToDto)
                    .toList();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching waybills: {}", e.getMessage());
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        }
    }

    public Optional<WaybillDto> findById(String id) {
        try {
            DocumentSnapshot doc = firestore.collection(COLLECTION).document(id).get().get();
            if (!doc.exists()) return Optional.empty();
            return Optional.of(documentToDto(doc));
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching waybill {}: {}", id, e.getMessage());
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    public List<WaybillDto> findByCustomerId(String customerId) {
        try {
            QuerySnapshot snapshot = firestore.collection(COLLECTION)
                    .whereEqualTo("customerId", customerId)
                    .get().get();
            return snapshot.getDocuments().stream()
                    .map(this::documentToDto)
                    .toList();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching waybills for customer {}: {}", customerId, e.getMessage());
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        }
    }

    public WaybillDto save(WaybillDto waybill) {
        try {
            Map<String, Object> data = dtoToMap(waybill);
            String docId = waybill.getWaybillId() != null ? waybill.getWaybillId() : UUID.randomUUID().toString();

            firestore.collection(COLLECTION).document(docId).set(data).get();
            waybill.setId(docId);

            return waybill;
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error saving waybill: {}", e.getMessage());
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to save waybill", e);
        }
    }

    public void saveAll(List<WaybillDto> waybills) {
        if (waybills == null || waybills.isEmpty()) {
            return;
        }

        try {
            WriteBatch batch = firestore.batch();
            for (WaybillDto waybill : waybills) {
                Map<String, Object> data = dtoToMap(waybill);
                String docId = waybill.getWaybillId() != null ? waybill.getWaybillId() : UUID.randomUUID().toString();
                DocumentReference docRef = firestore.collection(COLLECTION).document(docId);
                batch.set(docRef, data);
                waybill.setId(docId);
            }

            batch.commit().get();
            log.debug("Batch saved {} waybills", waybills.size());
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error batch saving waybills: {}", e.getMessage());
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to batch save waybills", e);
        }
    }

    private WaybillDto documentToDto(DocumentSnapshot doc) {
        LocalDate date = null;
        Object dateObj = doc.get("date");
        if (dateObj instanceof com.google.cloud.Timestamp) {
            date = ((com.google.cloud.Timestamp) dateObj).toDate()
                    .toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        }

        Object amountObj = doc.get("amount");
        BigDecimal amount = AmountUtils.parseAmount(amountObj);

        Object statusObj = doc.get("status");
        Integer status = null;
        if (statusObj instanceof Number) {
            status = ((Number) statusObj).intValue();
        } else if (statusObj != null) {
            try {
                status = Integer.parseInt(statusObj.toString().trim());
            } catch (NumberFormatException ignored) {
                status = null;
            }
        }

        WaybillType type = WaybillType.SALE;
        String typeStr = doc.getString("type");
        if (typeStr != null && !typeStr.isBlank()) {
            try {
                type = WaybillType.valueOf(typeStr.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                type = WaybillType.SALE;
            }
        }

        return WaybillDto.builder()
                .id(doc.getId())
                .waybillId(doc.getString("waybillId"))
                .type(type)
                .customerId(doc.getString("customerId"))
                .customerName(doc.getString("customerName"))
                .buyerTin(doc.getString("buyerTin"))
                .buyerName(doc.getString("buyerName"))
                .date(date)
                .amount(amount)
                .status(status)
                .isAfterCutoff(Boolean.TRUE.equals(doc.getBoolean("isAfterCutoff")))
                .sellerTin(doc.getString("sellerTin"))
                .sellerName(doc.getString("sellerName"))
                .build();
    }

    private Map<String, Object> dtoToMap(WaybillDto dto) {
        Map<String, Object> data = new HashMap<>();
        data.put("waybillId", dto.getWaybillId());
        data.put("type", dto.getType() != null ? dto.getType().name() : WaybillType.SALE.name());
        data.put("customerId", dto.getCustomerId());
        data.put("customerName", dto.getCustomerName());
        data.put("buyerTin", dto.getBuyerTin());
        data.put("buyerName", dto.getBuyerName());
        data.put("amount", dto.getAmount() != null ? dto.getAmount().doubleValue() : 0.0);
        data.put("status", dto.getStatus());
        data.put("isAfterCutoff", dto.isAfterCutoff());
        data.put("sellerTin", dto.getSellerTin());
        data.put("sellerName", dto.getSellerName());

        if (dto.getDate() != null) {
            data.put("date", com.google.cloud.Timestamp.of(
                    java.util.Date.from(dto.getDate().atStartOfDay(ZoneId.systemDefault()).toInstant())));
        }

        data.put("updatedAt", com.google.cloud.Timestamp.now());
        return data;
    }
}
