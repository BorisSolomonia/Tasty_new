package ge.tastyerp.payment.audit;

import ge.tastyerp.common.dto.auditlayer.AuditCategoryDto;
import ge.tastyerp.common.dto.auditlayer.AuditChangeLogDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingSplitDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingStatus;
import ge.tastyerp.common.dto.auditlayer.AuditSourceRowDto;
import ge.tastyerp.common.dto.auditlayer.AuditSourceType;
import ge.tastyerp.common.dto.auditlayer.AuditSubgroupDto;
import ge.tastyerp.common.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The universal individual mapping engine (BOR-89 §6).
 *
 * <p>Everything the audit layer knows that the source data does not is expressed
 * as a mapping. The engine's whole job is to keep three promises:</p>
 * <ol>
 *   <li>a source row is never modified;</li>
 *   <li>allocations can never exceed the source amount (§13);</li>
 *   <li>the unresolved remainder is derived, never stored, so it cannot silently
 *       disagree with the splits it is supposed to complement.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditMappingService {

    /** Tetri-level tolerance. Below this, a remainder is rounding, not a gap. */
    private static final BigDecimal EPSILON = new BigDecimal("0.005");

    private final AuditLayerRepository repository;

    // ==================== categories ====================

    /** Built-in categories first, then user-created ones. */
    public List<AuditCategoryDto> getCategories() {
        List<AuditCategoryDto> all = new ArrayList<>(AuditCategories.builtIns());
        all.addAll(repository.findCustomCategories());
        return all;
    }

    public Map<String, AuditCategoryDto> categoriesByCode() {
        Map<String, AuditCategoryDto> byCode = new LinkedHashMap<>();
        for (AuditCategoryDto category : getCategories()) {
            byCode.put(category.getCode(), category);
        }
        return byCode;
    }

    public AuditCategoryDto saveCategory(AuditCategoryDto category, String operator) {
        if (category.getCode() == null || category.getCode().isBlank()) {
            throw new ValidationException("code", "Category code is required");
        }
        if (category.getLabel() == null || category.getLabel().isBlank()) {
            throw new ValidationException("label", "Category label is required");
        }
        String code = category.getCode().trim().toUpperCase();
        boolean builtIn = AuditCategories.builtIns().stream()
                .anyMatch(c -> c.getCode().equals(code));
        if (builtIn) {
            throw new ValidationException("code",
                    "'" + code + "' is a built-in category and cannot be redefined");
        }
        category.setCode(code);
        category.setBuiltIn(false);
        repository.saveCategory(category);
        log(operator, "CATEGORY", code, "category", null, category.getLabel(), null);
        return category;
    }

    public void deleteCategory(String code, String operator) {
        boolean builtIn = AuditCategories.builtIns().stream()
                .anyMatch(c -> c.getCode().equals(code));
        if (builtIn) {
            throw new ValidationException("code", "Built-in categories cannot be deleted");
        }
        repository.deleteCategory(code);
        log(operator, "CATEGORY", code, "category", code, null, "deleted");
    }

    // ==================== subgroups (level 2, BOR-92) ====================

    /** Built-in subgroups first, then user-created ones. */
    public List<AuditSubgroupDto> getSubgroups() {
        List<AuditSubgroupDto> all = new ArrayList<>(AuditSubgroups.builtIns());
        all.addAll(repository.findCustomSubgroups());
        return all;
    }

    public Map<String, AuditSubgroupDto> subgroupsByCode() {
        Map<String, AuditSubgroupDto> byCode = new LinkedHashMap<>();
        for (AuditSubgroupDto s : getSubgroups()) {
            byCode.put(s.getCode(), s);
        }
        return byCode;
    }

    public AuditSubgroupDto saveSubgroup(AuditSubgroupDto subgroup, String operator) {
        if (subgroup.getCode() == null || subgroup.getCode().isBlank()) {
            throw new ValidationException("code", "Subgroup code is required");
        }
        if (subgroup.getLabel() == null || subgroup.getLabel().isBlank()) {
            throw new ValidationException("label", "Subgroup label is required");
        }
        String code = subgroup.getCode().trim().toUpperCase().replaceAll("[^A-Z0-9_]", "_");
        if (AuditSubgroups.isBuiltIn(code) || AuditSubgroups.NONE.equals(code)) {
            throw new ValidationException("code",
                    "'" + code + "' is a built-in subgroup and cannot be redefined");
        }
        subgroup.setCode(code);
        subgroup.setBuiltIn(false);
        repository.saveSubgroup(subgroup);
        log(operator, "SUBGROUP", code, "subgroup", null, subgroup.getLabel(), null);
        return subgroup;
    }

    public void deleteSubgroup(String code, String operator) {
        if (AuditSubgroups.isBuiltIn(code)) {
            throw new ValidationException("code", "Built-in subgroups cannot be deleted");
        }
        // A status that is still on a live split must not vanish: the split
        // would keep a code nobody can label, and the outflow tree would show
        // an unexplained bucket. Say which mappings hold it instead.
        long inUse = repository.findAllMappings().stream()
                .filter(m -> m.getSplits() != null)
                .filter(m -> m.getSplits().stream().anyMatch(s -> code.equals(s.getSubgroupCode())))
                .count();
        if (inUse > 0) {
            throw new ValidationException("code",
                    "Subgroup " + code + " is used by " + inUse + " mapping(s); re-map them first");
        }
        repository.deleteSubgroup(code);
        log(operator, "SUBGROUP", code, "subgroup", code, null, "deleted");
    }

    // ==================== mappings ====================

    public AuditMappingDto getMapping(AuditSourceType type, String sourceRowId) {
        return withDerived(repository.findMapping(type, sourceRowId));
    }

    /** All live mappings, indexed by {@code TYPE__sourceRowId}, for aggregation. */
    public Map<String, AuditMappingDto> loadMappingIndex() {
        return repository.findAllMappings().stream()
                .map(this::withDerived)
                .collect(Collectors.toMap(
                        m -> AuditLayerRepository.mappingId(m.getSourceType(), m.getSourceRowId()),
                        m -> m,
                        (a, b) -> a,
                        LinkedHashMap::new));
    }

    /**
     * Creates or replaces the mapping for one source row.
     *
     * @throws ValidationException when the splits over-allocate the source row,
     *         reference an unknown category, or carry a non-positive amount
     */
    public AuditMappingDto saveMapping(AuditMappingDto incoming, String operator) {
        requireOperator(operator);
        if (incoming.getSourceType() == null) {
            throw new ValidationException("sourceType", "Source type is required");
        }
        if (incoming.getSourceRowId() == null || incoming.getSourceRowId().isBlank()) {
            throw new ValidationException("sourceRowId", "Source row id is required");
        }
        BigDecimal sourceAmount = abs(incoming.getSourceAmount());
        if (sourceAmount == null) {
            throw new ValidationException("sourceAmount", "Source amount is required");
        }

        List<AuditMappingSplitDto> splits = incoming.getSplits() == null
                ? List.of() : incoming.getSplits();
        validateSplits(splits, sourceAmount);

        AuditMappingDto existing = repository.findMapping(
                incoming.getSourceType(), incoming.getSourceRowId());
        LocalDateTime now = LocalDateTime.now();

        AuditMappingDto saved = AuditMappingDto.builder()
                .id(AuditLayerRepository.mappingId(incoming.getSourceType(), incoming.getSourceRowId()))
                .sourceType(incoming.getSourceType())
                .sourceRowId(incoming.getSourceRowId())
                .sourceAmount(sourceAmount)
                .status(resolveStatus(incoming, existing, splits, sourceAmount))
                .splits(new ArrayList<>(splits))
                .linkedSourceRows(incoming.getLinkedSourceRows() == null
                        ? new ArrayList<>() : new ArrayList<>(incoming.getLinkedSourceRows()))
                .suggestionReason(incoming.getSuggestionReason())
                .confidence(incoming.getConfidence())
                .note(incoming.getNote())
                .createdBy(existing == null ? operator : existing.getCreatedBy())
                .updatedBy(operator)
                .createdAt(existing == null ? now : existing.getCreatedAt())
                .updatedAt(now)
                .build();

        repository.saveMapping(saved);
        log(operator, "MAPPING", saved.getId(), "splits",
                existing == null ? null : describe(existing),
                describe(saved), incoming.getNote());
        return withDerived(saved);
    }

    /**
     * Voids a mapping. The record is kept with {@link AuditMappingStatus#VOIDED}
     * rather than deleted, so §6's "delete while retaining history" holds and the
     * row returns to the unmapped queue without losing what it used to say.
     */
    public void voidMapping(String mappingId, String operator, String reason) {
        requireOperator(operator);
        int separator = mappingId == null ? -1 : mappingId.indexOf("__");
        if (separator <= 0) {
            throw new ValidationException("id", "Malformed mapping id: " + mappingId);
        }
        AuditSourceType type;
        try {
            type = AuditSourceType.valueOf(mappingId.substring(0, separator));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("id", "Unknown source type in mapping id: " + mappingId);
        }
        String sourceRowId = mappingId.substring(separator + 2);
        AuditMappingDto existing = repository.findMapping(type, sourceRowId);
        if (existing == null) {
            throw new ValidationException("id", "No mapping exists for " + mappingId);
        }
        // Describe BEFORE mutating: the log's whole value is what the mapping used
        // to say, and effectiveSplits() returns nothing once the status is VOIDED.
        String before = describe(existing);
        existing.setStatus(AuditMappingStatus.VOIDED);
        existing.setUpdatedBy(operator);
        existing.setUpdatedAt(LocalDateTime.now());
        repository.saveMapping(existing);
        log(operator, "MAPPING", mappingId, "status", before, "VOIDED", reason);
    }

    /**
     * Runs the suggestion engine over unmapped rows and stores what it proposes.
     *
     * <p>Only rows with no live mapping are touched — a suggestion must never
     * overwrite a decision a person already made. Suggestions land as
     * {@link AuditMappingStatus#SUGGESTED} and contribute nothing to any total
     * until accepted; only rules resting on the bank's own assertions are stored
     * as {@link AuditMappingStatus#AUTO_MAPPED}.</p>
     *
     * @return counts by resulting status
     */
    public Map<String, Integer> applySuggestions(List<AuditSourceRowDto> rows,
                                                 AuditSuggestionEngine engine,
                                                 String operator) {
        return applySuggestions(rows, engine, AuditSupplierRegistry.empty(), operator);
    }

    public Map<String, Integer> applySuggestions(List<AuditSourceRowDto> rows,
                                                 AuditSuggestionEngine engine,
                                                 AuditSupplierRegistry suppliers,
                                                 String operator) {
        return applySuggestions(rows, engine, suppliers, operator, false);
    }

    /**
     * @param replaceAutomatic when true, mappings the engine itself made
     *        (AUTO_MAPPED / SUGGESTED) are re-decided against the current rules.
     *        Rules improve — the supplier check went from a name pattern to an
     *        RS.ge document lookup — and a classification made by a superseded
     *        rule should not outlive it. Decisions a <em>person</em> made
     *        (MANUALLY_MAPPED, OVERRIDDEN) are never touched, whatever this says.
     */
    public Map<String, Integer> applySuggestions(List<AuditSourceRowDto> rows,
                                                 AuditSuggestionEngine engine,
                                                 AuditSupplierRegistry suppliers,
                                                 String operator,
                                                 boolean replaceAutomatic) {
        requireOperator(operator);
        // Everything the per-row path would re-fetch is loaded once. Saving each
        // proposal individually cost four sequential Firestore round trips per
        // row — on a 9,333-row statement that is tens of thousands of calls.
        Map<String, AuditMappingDto> existing = loadMappingIndex();
        Map<String, AuditCategoryDto> categories = categoriesByCode();
        LocalDateTime now = LocalDateTime.now();

        List<AuditMappingDto> toWrite = new ArrayList<>();
        List<AuditChangeLogDto> logEntries = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();

        for (AuditSourceRowDto row : rows) {
            String key = AuditLayerRepository.mappingId(row.getSourceType(), row.getSourceRowId());
            AuditMappingDto current = existing.get(key);
            if (current != null && current.getStatus() != AuditMappingStatus.VOIDED) {
                boolean madeByEngine = current.getStatus() == AuditMappingStatus.AUTO_MAPPED
                        || current.getStatus() == AuditMappingStatus.SUGGESTED;
                // A decision a person made is never overwritten, full stop.
                if (!replaceAutomatic || !madeByEngine) {
                    counts.merge("skippedAlreadyMapped", 1, Integer::sum);
                    continue;
                }
                counts.merge("replacedAutomatic", 1, Integer::sum);
            }
            AuditMappingDto proposal = engine.suggest(row, suppliers);
            if (proposal == null) {
                counts.merge("noRuleMatched", 1, Integer::sum);
                continue;
            }
            BigDecimal sourceAmount = abs(proposal.getSourceAmount());
            try {
                validateSplits(proposal.getSplits(), sourceAmount, categories);
            } catch (ValidationException e) {
                log.warn("Skipping suggestion for {}: {}", key, e.getMessage());
                counts.merge("rejected", 1, Integer::sum);
                continue;
            }
            AuditMappingDto saved = AuditMappingDto.builder()
                    .id(key)
                    .sourceType(proposal.getSourceType())
                    .sourceRowId(proposal.getSourceRowId())
                    .sourceAmount(sourceAmount)
                    .status(proposal.getStatus())
                    .splits(new ArrayList<>(proposal.getSplits()))
                    .linkedSourceRows(new ArrayList<>())
                    .suggestionReason(proposal.getSuggestionReason())
                    .confidence(proposal.getConfidence())
                    .createdBy(operator)
                    .updatedBy(operator)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            toWrite.add(saved);
            logEntries.add(AuditChangeLogDto.builder()
                    .entityType("MAPPING")
                    .entityId(key)
                    .field("splits")
                    .oldValue(null)
                    .newValue(describe(saved))
                    .changedBy(operator)
                    .changedAt(now)
                    .reason(proposal.getSuggestionReason())
                    .build());
            counts.merge(proposal.getStatus().name(), 1, Integer::sum);
        }

        int written = repository.saveMappingsBatch(toWrite, logEntries);
        log.info("Applied {} suggestions across {} rows: {}", written, rows.size(), counts);
        return counts;
    }

    public List<AuditChangeLogDto> getHistory(AuditSourceType type, String sourceRowId) {
        return repository.findChangeLog(AuditLayerRepository.mappingId(type, sourceRowId), 200);
    }

    public List<AuditChangeLogDto> getChangeLog(String entityId, int limit) {
        return repository.findChangeLog(entityId, limit);
    }

    /** Appends a log entry on behalf of another audit service. */
    public void log(String operator, String entityType, String entityId, String field,
                    String oldValue, String newValue, String reason) {
        repository.appendChangeLog(AuditChangeLogDto.builder()
                .entityType(entityType)
                .entityId(entityId)
                .field(field)
                .oldValue(oldValue)
                .newValue(newValue)
                .changedBy(operator)
                .changedAt(LocalDateTime.now())
                .reason(reason)
                .build());
    }

    // ==================== derivation ====================

    /**
     * @return {@code |sourceAmount| − Σ|split amounts|}, floored at zero. Always
     *         computed; never read from storage.
     */
    public static BigDecimal unresolvedOf(AuditMappingDto mapping) {
        if (mapping == null || mapping.getSourceAmount() == null) {
            return BigDecimal.ZERO;
        }
        if (mapping.getStatus() == AuditMappingStatus.VOIDED
                || mapping.getStatus() == AuditMappingStatus.SUGGESTED) {
            return abs(mapping.getSourceAmount());
        }
        BigDecimal remainder = abs(mapping.getSourceAmount()).subtract(splitTotal(mapping.getSplits()));
        return remainder.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : remainder;
    }

    /** Splits that count toward totals. A suggestion or a void contributes nothing. */
    public static List<AuditMappingSplitDto> effectiveSplits(AuditMappingDto mapping) {
        if (mapping == null
                || mapping.getStatus() == AuditMappingStatus.VOIDED
                || mapping.getStatus() == AuditMappingStatus.SUGGESTED
                || mapping.getSplits() == null) {
            return List.of();
        }
        return mapping.getSplits();
    }

    public static BigDecimal splitTotal(List<AuditMappingSplitDto> splits) {
        if (splits == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (AuditMappingSplitDto split : splits) {
            if (split.getAmount() != null) {
                total = total.add(split.getAmount().abs());
            }
        }
        return total;
    }

    private AuditMappingDto withDerived(AuditMappingDto mapping) {
        if (mapping == null) {
            return null;
        }
        mapping.setUnresolvedAmount(unresolvedOf(mapping).setScale(2, RoundingMode.HALF_UP));
        return mapping;
    }

    private void validateSplits(List<AuditMappingSplitDto> splits, BigDecimal sourceAmount) {
        validateSplits(splits, sourceAmount, categoriesByCode());
    }

    private void validateSplits(List<AuditMappingSplitDto> splits, BigDecimal sourceAmount,
                                Map<String, AuditCategoryDto> categories) {
        for (AuditMappingSplitDto split : splits) {
            if (split.getCategoryCode() == null || split.getCategoryCode().isBlank()) {
                throw new ValidationException("splits", "Every split needs a category");
            }
            if (!categories.containsKey(split.getCategoryCode())) {
                throw new ValidationException("splits",
                        "Unknown category '" + split.getCategoryCode() + "'");
            }
            if (split.getAmount() == null || split.getAmount().signum() <= 0) {
                throw new ValidationException("splits",
                        "Split amount must be greater than zero for category "
                                + split.getCategoryCode());
            }
            // Level 2 (BOR-92): a subgroup, when given, must exist — an unknown
            // code would silently vanish from the overview tree.
            if (split.getSubgroupCode() != null && !split.getSubgroupCode().isBlank()
                    && !AuditSubgroups.isBuiltIn(split.getSubgroupCode())
                    && repository.findCustomSubgroups().stream()
                            .noneMatch(sg -> sg.getCode().equals(split.getSubgroupCode()))) {
                throw new ValidationException("splits",
                        "Unknown subgroup '" + split.getSubgroupCode() + "'");
            }
        }
        BigDecimal total = splitTotal(splits);
        if (total.subtract(sourceAmount).compareTo(EPSILON) > 0) {
            // §13: never allow an allocation above the source value.
            throw new ValidationException("splits", String.format(
                    "Splits total %s exceeds the source amount %s. "
                            + "A source row cannot be allocated above 100%%.",
                    total.setScale(2, RoundingMode.HALF_UP),
                    sourceAmount.setScale(2, RoundingMode.HALF_UP)));
        }
    }

    /**
     * Status is derived from coverage unless the caller is explicitly recording a
     * suggestion or an override, which are statements about <em>who decided</em>
     * rather than about how much is covered.
     */
    private AuditMappingStatus resolveStatus(AuditMappingDto incoming,
                                             AuditMappingDto existing,
                                             List<AuditMappingSplitDto> splits,
                                             BigDecimal sourceAmount) {
        if (incoming.getStatus() == AuditMappingStatus.SUGGESTED) {
            return AuditMappingStatus.SUGGESTED;
        }
        if (splits.isEmpty()) {
            return AuditMappingStatus.UNMAPPED;
        }
        BigDecimal remainder = sourceAmount.subtract(splitTotal(splits));
        if (remainder.abs().compareTo(EPSILON) > 0) {
            return AuditMappingStatus.PARTIALLY_MAPPED;
        }
        boolean replacingAutomatic = existing != null
                && (existing.getStatus() == AuditMappingStatus.AUTO_MAPPED
                    || existing.getStatus() == AuditMappingStatus.SUGGESTED);
        if (incoming.getStatus() == AuditMappingStatus.AUTO_MAPPED) {
            return AuditMappingStatus.AUTO_MAPPED;
        }
        return replacingAutomatic ? AuditMappingStatus.OVERRIDDEN : AuditMappingStatus.MANUALLY_MAPPED;
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            // The log is only worth keeping if it names someone. This is a
            // self-declared name, not an authenticated identity (decision D-4).
            throw new ValidationException("operator",
                    "An operator name is required so the change can be logged");
        }
    }

    private static String describe(AuditMappingDto mapping) {
        if (mapping == null) {
            return null;
        }
        String splits = effectiveSplits(mapping).stream()
                .map(s -> s.getCategoryCode() + "=" + s.getAmount()
                        + (s.getCounterpartyName() == null ? "" : "/" + s.getCounterpartyName()))
                .collect(Collectors.joining(", "));
        return mapping.getStatus() + " [" + splits + "]";
    }

    private static BigDecimal abs(BigDecimal value) {
        return value == null ? null : value.abs();
    }
}
