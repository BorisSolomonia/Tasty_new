/**
 * Fired audit rules.
 *
 * Every alert shows its rule id, its formula in words and the named inputs the
 * formula was evaluated on. That is the ticket's "transparent rules" clause: a
 * red number that cannot be checked is not an audit finding, it is a rumour.
 */
import { ArrowUpRight } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/cn'
import type { AuditAlert } from '@/lib/audit-api'
import { isKnownDrilldownKey, type DrilldownKey } from './drilldown-keys'
import { SeverityBadge } from './status-badge'
import {
  fmtCount,
  fmtGel,
  fmtKgSigned,
  flowLabel,
  hasValue,
  severityRank,
  formatInput,
} from './format'
import { useAudit } from './audit-context'

/**
 * The rows behind one alert.
 *
 * An alert expands into its own `drilldownKey` and nothing else. Substituting a
 * broader key — "every bank row" for a rule about four withdrawals — would show
 * the reader a different set under the alert's title, which is the one thing a
 * drill-down must never do. So an unknown key disables the button and says why
 * rather than falling back to something wider.
 */
export function alertEvidenceKey(alert: AuditAlert): {
  key: DrilldownKey | null
  blockedReason: string | undefined
} {
  if (!alert.drilldownKey) {
    return { key: null, blockedReason: 'This rule returned no drill-down key.' }
  }
  if (!isKnownDrilldownKey(alert.drilldownKey)) {
    return {
      key: null,
      blockedReason: `The backend sent the drill-down key "${alert.drilldownKey}", which this build does not know how to open.`,
    }
  }
  return { key: alert.drilldownKey, blockedReason: undefined }
}

export function sortAlerts(alerts: AuditAlert[] | null | undefined): AuditAlert[] {
  return [...(alerts ?? [])].sort((a, b) => {
    const bySeverity = severityRank(a.severity) - severityRank(b.severity)
    if (bySeverity !== 0) return bySeverity
    return Math.abs(b.amount ?? 0) - Math.abs(a.amount ?? 0)
  })
}

export function AlertList({
  alerts,
  limit,
  onSelect,
  selectedRuleId,
  emptyMessage = 'No rules fired for this period.',
  className,
}: {
  alerts: AuditAlert[] | null | undefined
  limit?: number
  onSelect?: (alert: AuditAlert) => void
  selectedRuleId?: string | null
  emptyMessage?: string
  className?: string
}) {
  const sorted = sortAlerts(alerts)
  const shown = typeof limit === 'number' ? sorted.slice(0, limit) : sorted

  if (shown.length === 0) {
    return <p className="py-4 text-sm text-muted-foreground">{emptyMessage}</p>
  }

  return (
    <div className={cn('divide-y divide-border', className)}>
      {shown.map((alert, index) => (
        <AlertRow
          key={`${alert.ruleId ?? 'rule'}-${index}`}
          alert={alert}
          onSelect={onSelect}
          selected={Boolean(selectedRuleId && alert.ruleId === selectedRuleId)}
        />
      ))}
      {typeof limit === 'number' && sorted.length > shown.length ? (
        <p className="pt-2 text-xs text-muted-foreground">
          {fmtCount(sorted.length - shown.length)} further alerts not shown here.
        </p>
      ) : null}
    </div>
  )
}

function AlertRow({
  alert,
  onSelect,
  selected,
}: {
  alert: AuditAlert
  onSelect?: (alert: AuditAlert) => void
  selected: boolean
}) {
  const { openDrilldown } = useAudit()
  const inputs = Object.entries(alert.inputs ?? {})
  // The alert's own key, never a hand-picked wider one (BOR-91). The guard is
  // kept because a key this build does not know would fail server-side as
  // "Unknown drill-down key" — better to say so on the button.
  const evidence = alertEvidenceKey(alert)

  const headline = hasValue(alert.amount)
    ? fmtGel(alert.amount)
    : hasValue(alert.quantityKg)
      ? fmtKgSigned(alert.quantityKg)
      : ''

  return (
    <div
      className={cn(
        'py-3',
        onSelect && 'cursor-pointer',
        selected && 'bg-accent/50'
      )}
      onClick={onSelect ? () => onSelect(alert) : undefined}
    >
      <div className="flex flex-wrap items-center gap-2">
        <SeverityBadge severity={alert.severity} />
        <span className="rounded bg-muted px-1.5 py-0.5 text-[11px] font-medium text-muted-foreground">
          {flowLabel(alert.flow)}
        </span>
        <span className="font-mono text-[11px] text-muted-foreground">{alert.ruleId ?? 'UNNAMED_RULE'}</span>
        {headline ? <span className="ml-auto text-sm font-semibold tabular-nums">{headline}</span> : null}
      </div>

      <p className="mt-1.5 text-sm font-medium leading-snug">{alert.title ?? 'Untitled rule'}</p>

      {alert.formula ? (
        <p className="mt-1 font-mono text-xs leading-snug text-muted-foreground">{alert.formula}</p>
      ) : null}

      {inputs.length > 0 ? (
        <div className="mt-1.5 flex flex-wrap gap-1.5">
          {inputs.map(([name, value]) => (
            <span
              key={name}
              className="rounded border border-border bg-muted/60 px-1.5 py-0.5 text-[11px] tabular-nums"
            >
              <span className="text-muted-foreground">{name}</span> {formatInput(value)}
            </span>
          ))}
        </div>
      ) : null}

      <div className="mt-2 flex flex-wrap items-center gap-2">
        <span className="text-xs text-muted-foreground">
          {fmtCount(alert.affectedRowCount)} source rows
        </span>
        {alert.subjects && alert.subjects.length > 0 ? (
          <span className="truncate text-xs text-muted-foreground">
            · {alert.subjects.slice(0, 4).join(', ')}
            {alert.subjects.length > 4 ? ` +${alert.subjects.length - 4}` : ''}
          </span>
        ) : null}
        <Button
          type="button"
          size="sm"
          variant="outline"
          className="ml-auto h-7"
          disabled={!evidence.key}
          title={evidence.blockedReason}
          onClick={(event) => {
            event.stopPropagation()
            if (!evidence.key) return
            openDrilldown({
              key: evidence.key,
              subject: alert.subjects?.length === 1 ? alert.subjects[0] : undefined,
              label: alert.title ?? alert.ruleId ?? undefined,
            })
          }}
        >
          Open evidence
          <ArrowUpRight className="ml-1 h-3.5 w-3.5" />
        </Button>
      </div>
    </div>
  )
}
