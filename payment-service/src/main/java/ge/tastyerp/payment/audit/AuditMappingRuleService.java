package ge.tastyerp.payment.audit;

import ge.tastyerp.common.dto.auditlayer.AuditMappingDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingRuleDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingSplitDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingStatus;
import ge.tastyerp.common.dto.auditlayer.AuditSourceRowDto;
import ge.tastyerp.common.dto.auditlayer.AuditSourceType;
import ge.tastyerp.common.dto.auditlayer.MappingRuleCriterion;
import ge.tastyerp.common.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reusable mappings and the scope choice behind them (BOR-91).
 *
 * <p>The rule this service exists to enforce: <b>a classification never spreads
 * beyond the row it was made on unless a person said so</b>, having first been
 * shown exactly what it would catch. {@link #preview} produces that evidence;
 * {@link #saveRule} acts on it.</p>
 *
 * <p>A rule never overwrites a decision a person made. It applies to rows that
 * are unmapped or were mapped by automation, and it records its own id on every
 * mapping it creates so the reason stays readable — and so revoking it can undo
 * exactly what it did, and nothing else.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditMappingRuleService {

    /** How many example rows a preview returns. Enough to judge, not to scroll. */
    private static final int SAMPLE_SIZE = 12;

    private final AuditLayerRepository repository;
    private final AuditSourceRowService sourceRowService;
    private final AuditMappingService mappingService;

    // ==================== preview ====================

    /**
     * What each applicable criterion would catch, so the user can choose with
     * their eyes open. Ordered narrowest-first, because the narrow option is the
     * safer default to land on.
     */
    public List<AuditMappingRuleDto.Preview> preview(String sourceRowId,
                                                     LocalDate startDate, LocalDate endDate) {
        Map<String, AuditMappingDto> mappings = mappingService.loadMappingIndex();
        List<AuditSourceRowDto> rows = sourceRowService.loadBankRows(startDate, endDate, mappings);
        AuditSourceRowDto subject = rows.stream()
                .filter(r -> r.getSourceRowId().equals(sourceRowId))
                .findFirst()
                .orElseThrow(() -> new ValidationException("sourceRowId",
                        "No bank row " + sourceRowId + " in " + startDate + " – " + endDate));

        List<AuditMappingRuleDto.Preview> previews = new ArrayList<>();
        for (MappingRuleCriterion criterion : List.of(
                MappingRuleCriterion.COUNTERPARTY_AND_DESCRIPTION,
                MappingRuleCriterion.COUNTERPARTY,
                MappingRuleCriterion.DESCRIPTION,
                MappingRuleCriterion.TRANSACTION_TYPE)) {
            if (!AuditMappingRuleMatcher.isApplicable(criterion, subject)) {
                continue;
            }
            AuditMappingRuleDto candidate = AuditMappingRuleMatcher.ruleFrom(criterion, subject);
            int count = 0;
            int humanMapped = 0;
            BigDecimal amount = BigDecimal.ZERO;
            List<AuditSourceRowDto> sample = new ArrayList<>();
            for (AuditSourceRowDto row : rows) {
                if (!AuditMappingRuleMatcher.matches(candidate, row)) {
                    continue;
                }
                count++;
                amount = amount.add(row.getAmount() == null ? BigDecimal.ZERO : row.getAmount().abs());
                if (isHumanDecision(row)) {
                    humanMapped++;
                }
                if (sample.size() < SAMPLE_SIZE) {
                    sample.add(row);
                }
            }
            previews.add(AuditMappingRuleDto.Preview.builder()
                    .criterion(criterion)
                    .explanation(AuditMappingRuleMatcher.explain(criterion, subject))
                    .matchCount(count)
                    .matchAmount(amount)
                    .alreadyMappedByPersonCount(humanMapped)
                    .sample(sample)
                    .build());
        }
        return previews;
    }

    // ==================== rules ====================

    public List<AuditMappingRuleDto> getRules() {
        return repository.findMappingRules();
    }

    /**
     * Saves a rule and applies it to everything currently matching.
     *
     * @param startDate/@param endDate the window to apply over now; the rule
     *        itself is not bounded by them — later imports are picked up when
     *        suggestions run
     */
    public AuditMappingRuleDto saveRule(AuditMappingRuleDto rule, LocalDate startDate,
                                        LocalDate endDate, String operator) {
        requireOperator(operator);
        if (rule.getCriterion() == null) {
            throw new ValidationException("criterion", "A similarity criterion is required");
        }
        if (rule.getCategoryCode() == null || rule.getCategoryCode().isBlank()) {
            throw new ValidationException("categoryCode", "A category is required");
        }
        if (!mappingService.categoriesByCode().containsKey(rule.getCategoryCode())) {
            throw new ValidationException("categoryCode",
                    "Unknown category '" + rule.getCategoryCode() + "'");
        }
        LocalDateTime now = LocalDateTime.now();
        boolean isNew = rule.getId() == null || rule.getId().isBlank();
        if (isNew) {
            rule.setId(UUID.randomUUID().toString());
            rule.setCreatedBy(operator);
            rule.setCreatedAt(now);
        }
        rule.setActive(true);
        rule.setUpdatedBy(operator);
        rule.setUpdatedAt(now);
        repository.saveMappingRule(rule);

        Applied applied = apply(rule, startDate, endDate, operator);
        rule.setAppliedCount(applied.count());
        rule.setAppliedAmount(applied.amount());
        repository.saveMappingRule(rule);

        mappingService.log(operator, "MAPPING_RULE", rule.getId(),
                isNew ? "created" : "updated", null,
                rule.getCriterion() + " -> " + rule.getCategoryCode()
                        + " (" + applied.count() + " rows)", rule.getNote());
        log.info("Rule {} ({}) mapped {} rows totalling {}", rule.getId(),
                rule.getCriterion(), applied.count(), applied.amount());
        return rule;
    }

    /**
     * Withdraws a rule and un-maps exactly what it created.
     *
     * <p>Only mappings carrying this rule's id are touched, so a row somebody
     * later classified by hand keeps their decision. That containment is what
     * makes rules safe to create freely.</p>
     */
    public int revokeRule(String ruleId, String operator, String reason) {
        requireOperator(operator);
        AuditMappingRuleDto rule = getRules().stream()
                .filter(r -> ruleId.equals(r.getId()))
                .findFirst()
                .orElseThrow(() -> new ValidationException("id", "No mapping rule " + ruleId));

        // Keyed query + one batched write, instead of scanning every mapping ever
        // created and issuing one blocking round trip per row (BOR-82 finding
        // F-7: revoking a 5,000-row rule was 5,000 sequential writes — minutes,
        // and a half-revoked rule if the request timed out midway).
        LocalDateTime now = LocalDateTime.now();
        List<AuditMappingDto> toVoid = new ArrayList<>();
        List<ge.tastyerp.common.dto.auditlayer.AuditChangeLogDto> logs = new ArrayList<>();
        for (AuditMappingDto mapping : repository.findMappingsByRuleId(ruleId)) {
            if (mapping.getStatus() == AuditMappingStatus.VOIDED) {
                continue;
            }
            mapping.setStatus(AuditMappingStatus.VOIDED);
            mapping.setUpdatedBy(operator);
            mapping.setUpdatedAt(now);
            toVoid.add(mapping);
            logs.add(ge.tastyerp.common.dto.auditlayer.AuditChangeLogDto.builder()
                    .entityType("MAPPING")
                    .entityId(mapping.getId())
                    .field("status")
                    .oldValue(String.valueOf(AuditMappingStatus.AUTO_MAPPED))
                    .newValue("VOIDED by revoking rule " + ruleId)
                    .changedBy(operator)
                    .changedAt(now)
                    .reason(reason)
                    .build());
        }
        int undone = repository.saveMappingsBatch(toVoid, logs);
        rule.setActive(false);
        rule.setUpdatedBy(operator);
        rule.setUpdatedAt(LocalDateTime.now());
        repository.saveMappingRule(rule);

        mappingService.log(operator, "MAPPING_RULE", ruleId, "revoked",
                rule.getCriterion() + " -> " + rule.getCategoryCode(),
                null, reason == null ? "un-mapped " + undone + " rows" : reason);
        log.info("Rule {} revoked; {} mappings withdrawn", ruleId, undone);
        return undone;
    }

    /**
     * Applies every active rule to the rows in a window. Called after a rule is
     * saved and again when suggestions run, so a statement imported later is
     * classified by the rules already agreed.
     */
    public Applied applyAllRules(LocalDate startDate, LocalDate endDate, String operator) {
        int count = 0;
        BigDecimal amount = BigDecimal.ZERO;
        for (AuditMappingRuleDto rule : getRules()) {
            if (!rule.isActive()) {
                continue;
            }
            Applied applied = apply(rule, startDate, endDate, operator);
            count += applied.count();
            amount = amount.add(applied.amount());
        }
        return new Applied(count, amount);
    }

    private Applied apply(AuditMappingRuleDto rule, LocalDate startDate,
                          LocalDate endDate, String operator) {
        Map<String, AuditMappingDto> existing = mappingService.loadMappingIndex();
        List<AuditSourceRowDto> rows = sourceRowService.loadBankRows(startDate, endDate, existing);
        LocalDateTime now = LocalDateTime.now();

        List<AuditMappingDto> toWrite = new ArrayList<>();
        List<ge.tastyerp.common.dto.auditlayer.AuditChangeLogDto> logs = new ArrayList<>();
        BigDecimal amount = BigDecimal.ZERO;

        for (AuditSourceRowDto row : rows) {
            if (!AuditMappingRuleMatcher.matches(rule, row)) {
                continue;
            }
            if (isHumanDecision(row)) {
                // A person already decided this row. A rule never overrules them.
                continue;
            }
            BigDecimal rowAmount = row.getAmount() == null ? BigDecimal.ZERO : row.getAmount().abs();
            String id = AuditLayerRepository.mappingId(AuditSourceType.BANK, row.getSourceRowId());
            AuditMappingDto mapping = AuditMappingDto.builder()
                    .id(id)
                    .sourceType(AuditSourceType.BANK)
                    .sourceRowId(row.getSourceRowId())
                    .sourceAmount(rowAmount)
                    .status(AuditMappingStatus.AUTO_MAPPED)
                    .splits(List.of(AuditMappingSplitDto.builder()
                            .categoryCode(rule.getCategoryCode())
                            .counterpartyName(rule.getMappedCounterpartyName() != null
                                    ? rule.getMappedCounterpartyName() : row.getCounterpartyName())
                            .counterpartyTin(rule.getMappedCounterpartyTin() != null
                                    ? rule.getMappedCounterpartyTin() : row.getResolvedCounterpartyTin())
                            .amount(rowAmount)
                            .build()))
                    .linkedSourceRows(new ArrayList<>())
                    .appliedByRuleId(rule.getId())
                    .suggestionReason("Reusable rule: "
                            + AuditMappingRuleMatcher.explain(rule.getCriterion(), row))
                    .confidence(100)
                    .note(rule.getNote())
                    .createdBy(operator)
                    .updatedBy(operator)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            toWrite.add(mapping);
            logs.add(ge.tastyerp.common.dto.auditlayer.AuditChangeLogDto.builder()
                    .entityType("MAPPING")
                    .entityId(id)
                    .field("splits")
                    .newValue("AUTO_MAPPED by rule " + rule.getId() + " -> " + rule.getCategoryCode())
                    .changedBy(operator)
                    .changedAt(now)
                    .reason(rule.getNote())
                    .build());
            amount = amount.add(rowAmount);
        }
        repository.saveMappingsBatch(toWrite, logs);
        return new Applied(toWrite.size(), amount);
    }

    /** True when a person, not automation, decided this row's mapping. */
    private static boolean isHumanDecision(AuditSourceRowDto row) {
        AuditMappingStatus status = row.getStatus();
        return status == AuditMappingStatus.MANUALLY_MAPPED
                || status == AuditMappingStatus.OVERRIDDEN
                || status == AuditMappingStatus.PARTIALLY_MAPPED;
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new ValidationException("operator",
                    "An operator name is required so the change can be logged");
        }
    }

    /** How much a rule application did. */
    public record Applied(int count, BigDecimal amount) {
    }
}
