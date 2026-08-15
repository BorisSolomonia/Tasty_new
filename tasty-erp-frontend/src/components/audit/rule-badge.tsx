/**
 * Provenance for a mapping nobody typed (BOR-91).
 *
 * A saved rule maps matching rows on its own, which is only acceptable while
 * the reason stays readable on the row itself. This badge resolves
 * `mapping.appliedByRuleId` against the saved-rule list and states, in words,
 * which rule made the claim — falling back to the bare id when the rule list is
 * unavailable, because "mapped by a rule this session cannot name" is still
 * more honest than showing nothing.
 *
 * Data access lives in `useMappingRuleIndex`, called ONCE by the table, not in
 * the badge. Before BOR-82 (finding FE-3) every row mounted its own query
 * observer on the rules key and did a linear `.find()` per render, so a
 * 1000-row feed created 1000 subscriptions that all woke on every invalidation
 * and re-scanned the rule list — a visible freeze after each save.
 */
import * as React from 'react'
import { Wand2 } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/cn'
import { CRITERION_LABEL, type AuditMapping, type AuditMappingRule } from '@/lib/audit-api'
import { useMappingRules } from '@/hooks/use-audit-flows'

export type MappingRuleIndex = ReadonlyMap<string, AuditMappingRule>

/**
 * The saved rules indexed by id, memoised on the query result. `null` while
 * the list is unavailable (loading or the endpoint did not answer), so callers
 * can tell "unknown rule" apart from "no rules".
 */
export function useMappingRuleIndex(enabled = true): MappingRuleIndex | null {
  const rulesQuery = useMappingRules(enabled)
  const rules = rulesQuery.data
  return React.useMemo(() => {
    if (!rules) return null
    const index = new Map<string, AuditMappingRule>()
    for (const rule of rules) {
      if (rule.id) index.set(rule.id, rule)
    }
    return index
  }, [rules])
}

export const RuleBadge = React.memo(function RuleBadge({
  mapping,
  rulesById,
  className,
}: {
  mapping: AuditMapping | null | undefined
  /** From `useMappingRuleIndex()`; null when the rule list could not be loaded. */
  rulesById: MappingRuleIndex | null
  className?: string
}) {
  const ruleId = mapping?.appliedByRuleId ?? null
  if (!ruleId) return null

  const rule = rulesById?.get(ruleId) ?? null
  const criterion = rule?.criterion ? CRITERION_LABEL[rule.criterion] : null
  const label = rule?.note?.trim() || criterion || `rule ${ruleId.slice(0, 8)}`

  return (
    <Badge
      variant="secondary"
      className={cn('max-w-[16rem] gap-1 whitespace-nowrap font-medium', className)}
      title={
        rule
          ? `Mapped by a saved rule: ${criterion ?? 'criterion not stated'}. Created by ${
              rule.createdBy ?? 'an unnamed operator'
            }. Revoking that rule un-maps this row.`
          : `Mapped by saved rule ${ruleId}. The rule list could not be loaded, so its criterion cannot be shown here.`
      }
    >
      <Wand2 className="h-3 w-3 shrink-0" />
      <span className="truncate">by rule: {label}</span>
    </Badge>
  )
})
