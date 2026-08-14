/**
 * Provenance for a mapping nobody typed (BOR-91).
 *
 * A saved rule maps matching rows on its own, which is only acceptable while
 * the reason stays readable on the row itself. This badge resolves
 * `mapping.appliedByRuleId` against the saved-rule list and states, in words,
 * which rule made the claim — falling back to the bare id when the rule list is
 * unavailable, because "mapped by a rule this session cannot name" is still
 * more honest than showing nothing.
 */
import { Wand2 } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/cn'
import { CRITERION_LABEL, type AuditMapping } from '@/lib/audit-api'
import { useMappingRules } from '@/hooks/use-audit-flows'

export function RuleBadge({
  mapping,
  className,
}: {
  mapping: AuditMapping | null | undefined
  className?: string
}) {
  const ruleId = mapping?.appliedByRuleId ?? null
  const rulesQuery = useMappingRules(Boolean(ruleId))
  if (!ruleId) return null

  const rule = (rulesQuery.data ?? []).find((candidate) => candidate.id === ruleId) ?? null
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
}
