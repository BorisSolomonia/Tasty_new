package ge.tastyerp.payment.audit;

import ge.tastyerp.common.dto.auditlayer.AuditChangeLogDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingRuleDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingStatus;
import ge.tastyerp.common.dto.auditlayer.AuditSourceType;
import ge.tastyerp.common.dto.auditlayer.MappingRuleCriterion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BOR-82 finding F-7 regression: revoking a rule must be one keyed query and one
 * batched write — never a scan of every mapping and one round trip per row.
 * The containment rule (only this rule's mappings, human decisions untouched)
 * is preserved because the keyed query is on {@code appliedByRuleId}.
 */
class AuditMappingRuleServiceRevokeTest {

    private final AuditLayerRepository repository = mock(AuditLayerRepository.class);
    private final AuditSourceRowService sourceRows = mock(AuditSourceRowService.class);
    private final AuditMappingService mappings = mock(AuditMappingService.class);
    private final AuditMappingRuleService service =
            new AuditMappingRuleService(repository, sourceRows, mappings);

    private static AuditMappingDto mapping(String rowId, String ruleId, AuditMappingStatus status) {
        return AuditMappingDto.builder()
                .id(AuditLayerRepository.mappingId(AuditSourceType.BANK, rowId))
                .sourceType(AuditSourceType.BANK)
                .sourceRowId(rowId)
                .status(status)
                .appliedByRuleId(ruleId)
                .splits(List.of())
                .linkedSourceRows(List.of())
                .build();
    }

    @Test
    @DisplayName("revokeRule queries by rule id and voids in one batch")
    @SuppressWarnings("unchecked")
    void revokeUsesKeyedQueryAndBatch() {
        AuditMappingRuleDto rule = AuditMappingRuleDto.builder()
                .id("rule-1").criterion(MappingRuleCriterion.COUNTERPARTY)
                .categoryCode("SUPPLIER").active(true).build();
        when(repository.findMappingRules()).thenReturn(List.of(rule));
        when(repository.findMappingsByRuleId("rule-1")).thenReturn(List.of(
                mapping("r1", "rule-1", AuditMappingStatus.AUTO_MAPPED),
                mapping("r2", "rule-1", AuditMappingStatus.AUTO_MAPPED),
                mapping("r3", "rule-1", AuditMappingStatus.VOIDED)));   // already withdrawn
        when(repository.saveMappingsBatch(anyList(), anyList()))
                .thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

        int undone = service.revokeRule("rule-1", "boris", "wrong category");

        assertEquals(2, undone);
        verify(repository, never()).findAllMappings();
        verify(repository, never()).saveMapping(any());

        ArgumentCaptor<List<AuditMappingDto>> voided = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<AuditChangeLogDto>> logs = ArgumentCaptor.forClass(List.class);
        verify(repository).saveMappingsBatch(voided.capture(), logs.capture());
        assertEquals(2, voided.getValue().size());
        assertTrue(voided.getValue().stream().allMatch(m -> m.getStatus() == AuditMappingStatus.VOIDED));
        assertTrue(voided.getValue().stream().allMatch(m -> "boris".equals(m.getUpdatedBy())));
        assertEquals(2, logs.getValue().size(), "every withdrawn mapping gets its own history entry");

        ArgumentCaptor<AuditMappingRuleDto> saved = ArgumentCaptor.forClass(AuditMappingRuleDto.class);
        verify(repository).saveMappingRule(saved.capture());
        assertTrue(!saved.getValue().isActive(), "the rule itself is deactivated");
        verify(mappings).log(eq("boris"), eq("MAPPING_RULE"), eq("rule-1"), eq("revoked"),
                any(), any(), eq("wrong category"));
    }
}
