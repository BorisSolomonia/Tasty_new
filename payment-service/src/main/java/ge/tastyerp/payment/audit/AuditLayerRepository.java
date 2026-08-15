package ge.tastyerp.payment.audit;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.WriteBatch;
import ge.tastyerp.common.dto.auditlayer.AuditCategoryDto;
import ge.tastyerp.common.dto.auditlayer.AuditChangeLogDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingRuleDto;
import ge.tastyerp.common.dto.auditlayer.MappingRuleCriterion;
import ge.tastyerp.common.dto.auditlayer.AuditMappingSplitDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingStatus;
import ge.tastyerp.common.dto.auditlayer.AuditSourceType;
import ge.tastyerp.common.dto.auditlayer.CheckEvidenceDto;
import ge.tastyerp.common.dto.auditlayer.CounterpartyAliasDto;
import ge.tastyerp.common.dto.auditlayer.RealInventoryOverrideDto;
import ge.tastyerp.common.dto.auditlayer.RealSupplierDebtDto;
import ge.tastyerp.common.util.FutureResults;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Firestore access for every audit-layer collection (BOR-89 §14).
 *
 * <p>Collections owned here — all new, none shared with the operational ERP:</p>
 * <ul>
 *   <li>{@code audit_mappings} — one doc per mapped source row</li>
 *   <li>{@code audit_categories} — user-created categories only; built-ins are code</li>
 *   <li>{@code audit_real_inventory} — manually confirmed on-hand stock</li>
 *   <li>{@code audit_supplier_debt} — manually maintained real supplier debt</li>
 *   <li>{@code audit_check_evidence} — checks / payment evidence</li>
 *   <li>{@code audit_change_log} — append-only history</li>
 * </ul>
 *
 * <p><b>Source collections are never written here.</b> {@code payments},
 * {@code waybills} and {@code bankTransactions} are read-only to the audit layer,
 * which is what makes BOR-89 §13's immutability rule structural rather than a
 * convention someone has to remember.</p>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class AuditLayerRepository {

    static final String COLLECTION_MAPPINGS = "audit_mappings";
    static final String COLLECTION_CATEGORIES = "audit_categories";
    static final String COLLECTION_REAL_INVENTORY = "audit_real_inventory";
    static final String COLLECTION_SUPPLIER_DEBT = "audit_supplier_debt";
    static final String COLLECTION_CHECK_EVIDENCE = "audit_check_evidence";
    static final String COLLECTION_CHANGE_LOG = "audit_change_log";
    static final String COLLECTION_COUNTERPARTY_ALIAS = "audit_counterparty_alias";
    static final String COLLECTION_MAPPING_RULES = "audit_mapping_rules";

    /** Firestore hard limit on operations per WriteBatch. */
    private static final int FIRESTORE_BATCH_LIMIT = 500;

    private final Firestore firestore;

    // ==================== mappings ====================

    /**
     * A mapping's document id is derived from the source row it classifies, so a
     * row can never accumulate two competing live mappings (alert rule 18 exists
     * for conflicting mappings; this makes the common case impossible).
     */
    public static String mappingId(AuditSourceType type, String sourceRowId) {
        return type.name() + "__" + sourceRowId;
    }

    public List<AuditMappingDto> findAllMappings() {
        List<AuditMappingDto> result = new ArrayList<>();
        QuerySnapshot snapshot = FutureResults.await(
                firestore.collection(COLLECTION_MAPPINGS).get(), "load audit mappings");
        for (QueryDocumentSnapshot doc : snapshot.getDocuments()) {
            result.add(toMapping(doc));
        }
        return result;
    }

    /**
     * Only the mappings a given rule produced — a keyed query instead of a scan
     * of the whole collection (BOR-82 finding F-7). Used to revoke a rule.
     */
    public List<AuditMappingDto> findMappingsByRuleId(String ruleId) {
        List<AuditMappingDto> result = new ArrayList<>();
        QuerySnapshot snapshot = FutureResults.await(
                firestore.collection(COLLECTION_MAPPINGS)
                        .whereEqualTo("appliedByRuleId", ruleId).get(),
                "load mappings applied by rule " + ruleId);
        for (QueryDocumentSnapshot doc : snapshot.getDocuments()) {
            result.add(toMapping(doc));
        }
        return result;
    }

    public AuditMappingDto findMapping(AuditSourceType type, String sourceRowId) {
        DocumentSnapshot doc = FutureResults.await(
                firestore.collection(COLLECTION_MAPPINGS)
                        .document(mappingId(type, sourceRowId)).get(),
                "read audit mapping " + sourceRowId);
        return doc.exists() ? toMapping(doc) : null;
    }

    public void saveMapping(AuditMappingDto mapping) {
        String id = mappingId(mapping.getSourceType(), mapping.getSourceRowId());
        write(COLLECTION_MAPPINGS, id, mappingToMap(mapping));
    }

    private Map<String, Object> mappingToMap(AuditMappingDto mapping) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sourceType", mapping.getSourceType().name());
        data.put("sourceRowId", mapping.getSourceRowId());
        data.put("sourceAmount", toDouble(mapping.getSourceAmount()));
        data.put("status", mapping.getStatus().name());
        data.put("splits", splitsToMaps(mapping.getSplits()));
        data.put("linkedSourceRows", mapping.getLinkedSourceRows());
        data.put("suggestionReason", mapping.getSuggestionReason());
        data.put("appliedByRuleId", mapping.getAppliedByRuleId());
        data.put("confidence", mapping.getConfidence());
        data.put("note", mapping.getNote());
        data.put("createdBy", mapping.getCreatedBy());
        data.put("updatedBy", mapping.getUpdatedBy());
        data.put("createdAt", mapping.getCreatedAt() == null ? null : mapping.getCreatedAt().toString());
        data.put("updatedAt", mapping.getUpdatedAt() == null ? null : mapping.getUpdatedAt().toString());
        return data;
    }

    /**
     * Writes many mappings and their log entries in Firestore batches.
     *
     * <p>Saving one at a time cost four sequential round trips per row, which over
     * a 9,333-row statement is tens of thousands of calls and minutes of wall
     * clock. Bulk classification is a normal operation on a multi-year statement,
     * so it gets a bulk path.</p>
     *
     * @return the number of mappings written
     */
    public int saveMappingsBatch(List<AuditMappingDto> mappings, List<AuditChangeLogDto> logEntries) {
        int written = writeBatched(COLLECTION_MAPPINGS, mappings,
                m -> mappingId(m.getSourceType(), m.getSourceRowId()), this::mappingToMap);
        writeBatched(COLLECTION_CHANGE_LOG, logEntries, e -> null, this::changeLogToMap);
        return written;
    }

    private <T> int writeBatched(String collection, List<T> items,
                                 java.util.function.Function<T, String> idOf,
                                 java.util.function.Function<T, Map<String, Object>> bodyOf) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        int written = 0;
        for (int i = 0; i < items.size(); i += FIRESTORE_BATCH_LIMIT) {
            WriteBatch batch = firestore.batch();
            List<T> chunk = items.subList(i, Math.min(i + FIRESTORE_BATCH_LIMIT, items.size()));
            for (T item : chunk) {
                String id = idOf.apply(item);
                var ref = id == null
                        ? firestore.collection(collection).document()
                        : firestore.collection(collection).document(id);
                batch.set(ref, bodyOf.apply(item));
            }
            FutureResults.await(batch.commit(), "batch-write " + collection);
            written += chunk.size();
        }
        return written;
    }

    // ==================== categories ====================

    /** User-created categories only. Built-ins live in {@link AuditCategories}. */
    public List<AuditCategoryDto> findCustomCategories() {
        List<AuditCategoryDto> result = new ArrayList<>();
        QuerySnapshot snapshot = FutureResults.await(
                firestore.collection(COLLECTION_CATEGORIES).get(), "load audit categories");
        for (QueryDocumentSnapshot doc : snapshot.getDocuments()) {
            result.add(AuditCategoryDto.builder()
                    .code(doc.getId())
                    .label(doc.getString("label"))
                    .description(doc.getString("description"))
                    .builtIn(false)
                    .supplierSettlement(bool(doc, "supplierSettlement"))
                    .customerReceipt(bool(doc, "customerReceipt"))
                    .nonSupplierExpense(bool(doc, "nonSupplierExpense"))
                    .cashReturn(bool(doc, "cashReturn"))
                    .paperOnly(bool(doc, "paperOnly"))
                    .unresolved(bool(doc, "unresolved"))
                    .build());
        }
        return result;
    }

    public void saveCategory(AuditCategoryDto category) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("label", category.getLabel());
        data.put("description", category.getDescription());
        data.put("supplierSettlement", category.isSupplierSettlement());
        data.put("customerReceipt", category.isCustomerReceipt());
        data.put("nonSupplierExpense", category.isNonSupplierExpense());
        data.put("cashReturn", category.isCashReturn());
        data.put("paperOnly", category.isPaperOnly());
        data.put("unresolved", category.isUnresolved());
        write(COLLECTION_CATEGORIES, category.getCode(), data);
    }

    public void deleteCategory(String code) {
        delete(COLLECTION_CATEGORIES, code);
    }

    // ==================== mapping rules ====================

    public List<AuditMappingRuleDto> findMappingRules() {
        List<AuditMappingRuleDto> result = new ArrayList<>();
        QuerySnapshot snapshot = FutureResults.await(
                firestore.collection(COLLECTION_MAPPING_RULES).get(), "load mapping rules");
        for (QueryDocumentSnapshot doc : snapshot.getDocuments()) {
            Long applied = doc.getLong("appliedCount");
            result.add(AuditMappingRuleDto.builder()
                    .id(doc.getId())
                    .criterion(doc.getString("criterion") == null ? null
                            : MappingRuleCriterion.valueOf(doc.getString("criterion")))
                    .direction(doc.getString("direction"))
                    .counterpartyTin(doc.getString("counterpartyTin"))
                    .counterpartyName(doc.getString("counterpartyName"))
                    .description(doc.getString("description"))
                    .transactionType(doc.getString("transactionType"))
                    .categoryCode(doc.getString("categoryCode"))
                    .mappedCounterpartyName(doc.getString("mappedCounterpartyName"))
                    .mappedCounterpartyTin(doc.getString("mappedCounterpartyTin"))
                    .note(doc.getString("note"))
                    .active(Boolean.TRUE.equals(doc.getBoolean("active")))
                    .appliedCount(applied == null ? 0 : applied.intValue())
                    .appliedAmount(decimal(doc, "appliedAmount"))
                    .createdBy(doc.getString("createdBy"))
                    .createdAt(parseDateTime(doc.getString("createdAt")))
                    .updatedBy(doc.getString("updatedBy"))
                    .updatedAt(parseDateTime(doc.getString("updatedAt")))
                    .build());
        }
        return result;
    }

    public void saveMappingRule(AuditMappingRuleDto rule) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("criterion", rule.getCriterion() == null ? null : rule.getCriterion().name());
        data.put("direction", rule.getDirection());
        data.put("counterpartyTin", rule.getCounterpartyTin());
        data.put("counterpartyName", rule.getCounterpartyName());
        data.put("description", rule.getDescription());
        data.put("transactionType", rule.getTransactionType());
        data.put("categoryCode", rule.getCategoryCode());
        data.put("mappedCounterpartyName", rule.getMappedCounterpartyName());
        data.put("mappedCounterpartyTin", rule.getMappedCounterpartyTin());
        data.put("note", rule.getNote());
        data.put("active", rule.isActive());
        data.put("appliedCount", rule.getAppliedCount());
        data.put("appliedAmount", rule.getAppliedAmount() == null ? null : rule.getAppliedAmount().doubleValue());
        data.put("createdBy", rule.getCreatedBy());
        data.put("createdAt", rule.getCreatedAt() == null ? null : rule.getCreatedAt().toString());
        data.put("updatedBy", rule.getUpdatedBy());
        data.put("updatedAt", rule.getUpdatedAt() == null ? null : rule.getUpdatedAt().toString());
        write(COLLECTION_MAPPING_RULES, rule.getId(), data);
    }

    // ==================== counterparty aliases ====================

    public List<CounterpartyAliasDto> findCounterpartyAliases() {
        List<CounterpartyAliasDto> result = new ArrayList<>();
        QuerySnapshot snapshot = FutureResults.await(
                firestore.collection(COLLECTION_COUNTERPARTY_ALIAS).get(), "load counterparty aliases");
        for (QueryDocumentSnapshot doc : snapshot.getDocuments()) {
            result.add(CounterpartyAliasDto.builder()
                    .id(doc.getId())
                    .rawName(doc.getString("rawName"))
                    .normalizedName(doc.getString("normalizedName"))
                    .counterpartyTin(doc.getString("counterpartyTin"))
                    .note(doc.getString("note"))
                    .createdBy(doc.getString("createdBy"))
                    .createdAt(parseDateTime(doc.getString("createdAt")))
                    .build());
        }
        return result;
    }

    public void saveCounterpartyAlias(CounterpartyAliasDto alias) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rawName", alias.getRawName());
        data.put("normalizedName", alias.getNormalizedName());
        data.put("counterpartyTin", alias.getCounterpartyTin());
        data.put("note", alias.getNote());
        data.put("createdBy", alias.getCreatedBy());
        data.put("createdAt", alias.getCreatedAt() == null ? null : alias.getCreatedAt().toString());
        write(COLLECTION_COUNTERPARTY_ALIAS, alias.getId(), data);
    }

    public void deleteCounterpartyAlias(String id) {
        delete(COLLECTION_COUNTERPARTY_ALIAS, id);
    }

    // ==================== real inventory ====================

    public List<RealInventoryOverrideDto> findRealInventory() {
        List<RealInventoryOverrideDto> result = new ArrayList<>();
        QuerySnapshot snapshot = FutureResults.await(
                firestore.collection(COLLECTION_REAL_INVENTORY).get(), "load real inventory overrides");
        for (QueryDocumentSnapshot doc : snapshot.getDocuments()) {
            result.add(RealInventoryOverrideDto.builder()
                    .id(doc.getId())
                    .productName(doc.getString("productName"))
                    .asOfDate(parseDate(doc.getString("asOfDate")))
                    .realKg(decimal(doc, "realKg"))
                    .note(doc.getString("note"))
                    .updatedBy(doc.getString("updatedBy"))
                    .updatedAt(parseDateTime(doc.getString("updatedAt")))
                    .build());
        }
        return result;
    }

    public void saveRealInventory(RealInventoryOverrideDto dto) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productName", dto.getProductName());
        data.put("asOfDate", dto.getAsOfDate() == null ? null : dto.getAsOfDate().toString());
        data.put("realKg", toDouble(dto.getRealKg()));
        data.put("note", dto.getNote());
        data.put("updatedBy", dto.getUpdatedBy());
        data.put("updatedAt", dto.getUpdatedAt() == null ? null : dto.getUpdatedAt().toString());
        write(COLLECTION_REAL_INVENTORY, dto.getId(), data);
    }

    public void deleteRealInventory(String id) {
        delete(COLLECTION_REAL_INVENTORY, id);
    }

    // ==================== supplier debt ====================

    public List<RealSupplierDebtDto> findSupplierDebts() {
        List<RealSupplierDebtDto> result = new ArrayList<>();
        QuerySnapshot snapshot = FutureResults.await(
                firestore.collection(COLLECTION_SUPPLIER_DEBT).get(), "load supplier debts");
        for (QueryDocumentSnapshot doc : snapshot.getDocuments()) {
            result.add(RealSupplierDebtDto.builder()
                    .id(doc.getId())
                    .supplierName(doc.getString("supplierName"))
                    .supplierTin(doc.getString("supplierTin"))
                    .outstandingAmount(decimal(doc, "outstandingAmount"))
                    .note(doc.getString("note"))
                    .updatedBy(doc.getString("updatedBy"))
                    .updatedAt(parseDateTime(doc.getString("updatedAt")))
                    .build());
        }
        return result;
    }

    public void saveSupplierDebt(RealSupplierDebtDto dto) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("supplierName", dto.getSupplierName());
        data.put("supplierTin", dto.getSupplierTin());
        data.put("outstandingAmount", toDouble(dto.getOutstandingAmount()));
        data.put("note", dto.getNote());
        data.put("updatedBy", dto.getUpdatedBy());
        data.put("updatedAt", dto.getUpdatedAt() == null ? null : dto.getUpdatedAt().toString());
        write(COLLECTION_SUPPLIER_DEBT, dto.getId(), data);
    }

    // ==================== check evidence ====================

    public List<CheckEvidenceDto> findCheckEvidence() {
        List<CheckEvidenceDto> result = new ArrayList<>();
        QuerySnapshot snapshot = FutureResults.await(
                firestore.collection(COLLECTION_CHECK_EVIDENCE).get(), "load check evidence");
        for (QueryDocumentSnapshot doc : snapshot.getDocuments()) {
            result.add(CheckEvidenceDto.builder()
                    .id(doc.getId())
                    .documentNumber(doc.getString("documentNumber"))
                    .counterpartyName(doc.getString("counterpartyName"))
                    .counterpartyTin(doc.getString("counterpartyTin"))
                    .date(parseDate(doc.getString("date")))
                    .amount(decimal(doc, "amount"))
                    .explanation(doc.getString("explanation"))
                    .classified(bool(doc, "classified"))
                    .note(doc.getString("note"))
                    .updatedBy(doc.getString("updatedBy"))
                    .updatedAt(parseDateTime(doc.getString("updatedAt")))
                    .build());
        }
        return result;
    }

    public void saveCheckEvidence(CheckEvidenceDto dto) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("documentNumber", dto.getDocumentNumber());
        data.put("counterpartyName", dto.getCounterpartyName());
        data.put("counterpartyTin", dto.getCounterpartyTin());
        data.put("date", dto.getDate() == null ? null : dto.getDate().toString());
        data.put("amount", toDouble(dto.getAmount()));
        data.put("explanation", dto.getExplanation());
        data.put("classified", dto.isClassified());
        data.put("note", dto.getNote());
        data.put("updatedBy", dto.getUpdatedBy());
        data.put("updatedAt", dto.getUpdatedAt() == null ? null : dto.getUpdatedAt().toString());
        write(COLLECTION_CHECK_EVIDENCE, dto.getId(), data);
    }

    public void deleteCheckEvidence(String id) {
        delete(COLLECTION_CHECK_EVIDENCE, id);
    }

    // ==================== change log (append-only) ====================

    /**
     * Appends one history entry. Never updates or deletes an existing one — that
     * is what lets a mapping be voided without losing what it used to say.
     */
    public void appendChangeLog(AuditChangeLogDto entry) {
        Map<String, Object> data = changeLogToMap(entry);
        FutureResults.await(firestore.collection(COLLECTION_CHANGE_LOG).document().set(data),
                "append audit change log");
    }

    private Map<String, Object> changeLogToMap(AuditChangeLogDto entry) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("entityType", entry.getEntityType());
        data.put("entityId", entry.getEntityId());
        data.put("field", entry.getField());
        data.put("oldValue", entry.getOldValue());
        data.put("newValue", entry.getNewValue());
        data.put("changedBy", entry.getChangedBy());
        data.put("changedAt", entry.getChangedAt().toString());
        data.put("reason", entry.getReason());
        return data;
    }

    /**
     * @param entityId null returns the newest entries of the whole log.
     *
     * <p>The whole-log path orders and limits <em>in the query</em>: {@code changedAt}
     * is stored as an ISO-8601 string, which sorts lexicographically in time order,
     * and a single-field order needs no composite index. Before this the entire
     * append-only log (one entry per mapping change, thousands per bulk
     * suggestion run) was read and sorted in memory on every call
     * (BOR-82 finding F-9). The per-entity path stays an equality filter with an
     * in-memory sort because that set is tiny.</p>
     */
    public List<AuditChangeLogDto> findChangeLog(String entityId, int limit) {
        List<AuditChangeLogDto> result = new ArrayList<>();
        boolean wholeLog = entityId == null || entityId.isBlank();
        com.google.cloud.firestore.Query query = wholeLog
                ? firestore.collection(COLLECTION_CHANGE_LOG)
                        .orderBy("changedAt", com.google.cloud.firestore.Query.Direction.DESCENDING)
                : firestore.collection(COLLECTION_CHANGE_LOG).whereEqualTo("entityId", entityId);
        if (wholeLog && limit > 0) {
            query = query.limit(limit);
        }
        QuerySnapshot snapshot = FutureResults.await(query.get(), "load audit change log");
        for (QueryDocumentSnapshot doc : snapshot.getDocuments()) {
            result.add(AuditChangeLogDto.builder()
                    .id(doc.getId())
                    .entityType(doc.getString("entityType"))
                    .entityId(doc.getString("entityId"))
                    .field(doc.getString("field"))
                    .oldValue(doc.getString("oldValue"))
                    .newValue(doc.getString("newValue"))
                    .changedBy(doc.getString("changedBy"))
                    .changedAt(parseDateTime(doc.getString("changedAt")))
                    .reason(doc.getString("reason"))
                    .build());
        }
        // Newest first (a no-op for the whole-log path, which is already ordered).
        result.sort((a, b) -> {
            if (a.getChangedAt() == null || b.getChangedAt() == null) {
                return 0;
            }
            return b.getChangedAt().compareTo(a.getChangedAt());
        });
        // Copy, not subList: a view would pin the whole backing list.
        return limit > 0 && result.size() > limit ? new ArrayList<>(result.subList(0, limit)) : result;
    }

    // ==================== helpers ====================

    private void write(String collection, String id, Map<String, Object> data) {
        FutureResults.await(firestore.collection(collection).document(id).set(data),
                "write " + collection + "/" + id);
    }

    private void delete(String collection, String id) {
        FutureResults.await(firestore.collection(collection).document(id).delete(),
                "delete " + collection + "/" + id);
    }

    @SuppressWarnings("unchecked")
    private AuditMappingDto toMapping(DocumentSnapshot doc) {
        List<AuditMappingSplitDto> splits = new ArrayList<>();
        Object raw = doc.get("splits");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> m = (Map<String, Object>) map;
                    splits.add(AuditMappingSplitDto.builder()
                            .categoryCode(str(m.get("categoryCode")))
                            .counterpartyName(str(m.get("counterpartyName")))
                            .counterpartyTin(str(m.get("counterpartyTin")))
                            .productName(str(m.get("productName")))
                            .amount(num(m.get("amount")))
                            .quantityKg(num(m.get("quantityKg")))
                            .note(str(m.get("note")))
                            .build());
                }
            }
        }
        List<String> linked = new ArrayList<>();
        if (doc.get("linkedSourceRows") instanceof List<?> list) {
            for (Object item : list) {
                linked.add(str(item));
            }
        }
        Long confidence = doc.getLong("confidence");
        return AuditMappingDto.builder()
                .id(doc.getId())
                .sourceType(AuditSourceType.valueOf(doc.getString("sourceType")))
                .sourceRowId(doc.getString("sourceRowId"))
                .sourceAmount(decimal(doc, "sourceAmount"))
                .status(AuditMappingStatus.valueOf(doc.getString("status")))
                .splits(splits)
                .linkedSourceRows(linked)
                .suggestionReason(doc.getString("suggestionReason"))
                .appliedByRuleId(doc.getString("appliedByRuleId"))
                .confidence(confidence == null ? null : confidence.intValue())
                .note(doc.getString("note"))
                .createdBy(doc.getString("createdBy"))
                .updatedBy(doc.getString("updatedBy"))
                .createdAt(parseDateTime(doc.getString("createdAt")))
                .updatedAt(parseDateTime(doc.getString("updatedAt")))
                .build();
    }

    private static List<Map<String, Object>> splitsToMaps(List<AuditMappingSplitDto> splits) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (splits == null) {
            return result;
        }
        for (AuditMappingSplitDto split : splits) {
            Map<String, Object> m = new HashMap<>();
            m.put("categoryCode", split.getCategoryCode());
            m.put("counterpartyName", split.getCounterpartyName());
            m.put("counterpartyTin", split.getCounterpartyTin());
            m.put("productName", split.getProductName());
            m.put("amount", toDouble(split.getAmount()));
            m.put("quantityKg", toDouble(split.getQuantityKg()));
            m.put("note", split.getNote());
            result.add(m);
        }
        return result;
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static BigDecimal num(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal decimal(DocumentSnapshot doc, String field) {
        return num(doc.get(field));
    }

    private static boolean bool(DocumentSnapshot doc, String field) {
        return Boolean.TRUE.equals(doc.getBoolean(field));
    }

    private static LocalDate parseDate(String value) {
        try {
            return value == null || value.isBlank() ? null : LocalDate.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDateTime parseDateTime(String value) {
        try {
            return value == null || value.isBlank() ? null : LocalDateTime.parse(value);
        } catch (Exception e) {
            return null;
        }
    }
}
