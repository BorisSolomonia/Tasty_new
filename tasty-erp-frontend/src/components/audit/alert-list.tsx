/**
 * Fired audit rules — one line each.
 *
 * Every alert still shows its rule id, its formula in words, the named inputs
 * the formula was evaluated on and the subjects it named. That is the ticket's
 * "transparent rules" clause: a red number that cannot be checked is not an
 * audit finding, it is a rumour.
 *
 * What changed is where that arithmetic lives. Printed open, nine alerts came
 * to ~1,570px — the panel that exists to say *what is wrong* spent its height
 * on the working rather than the findings, and the ninth problem was three
 * screens below the first. So a row is now the headline only — severity, flow,
 * rule id, title, amount, affected rows — and the whole of the working is one
 * click away on that row. Nothing was deleted; the arithmetic is the point.
 *
 * Each row owns its own open state, so opening one leaves the others as they
 * were. That also lets a reader put two rules side by side, which the previous
 * single-selection "case anatomy" panel could not do.
 *
 * The expansion carries what that panel used to: formula, inputs, amount and
 * quantity, the flows the rule reaches through its own subjects, and the
 * evidence button. The panel itself is gone rather than kept beside a list that
 * now says the same thing — two renderings of one alert is the duplication this
 * page was consolidated to remove.
 */
import * as React from 'react'
import { ArrowUpRight, ChevronRight } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/cn'
import type { AuditAlert } from '@/lib/audit-api'
import { isKnownDrilldownKey, type DrilldownKey } from './drilldown-keys'
import { SeverityBadge } from './status-badge'
import { sectionForDrilldownKey } from './evidence'
import {
  alertsByFlow,
  alertsTouching,
  subjectsOf,
  FLOW_KEYS,
  type FlowKey,
} from './cross-flow'
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
  emptyMessage = 'No rules fired for this period.',
  className,
}: {
  alerts: AuditAlert[] | null | undefined
  limit?: number
  emptyMessage?: string
  className?: string
}) {
  const sorted = sortAlerts(alerts)
  const shown = typeof limit === 'number' ? sorted.slice(0, limit) : sorted
  // Cross-flow links are drawn against every alert of the period, not only the
  // ones a `limit` left on screen.
  const all = React.useMemo(() => alerts ?? [], [alerts])

  if (shown.length === 0) {
    return <p className="py-4 text-sm text-muted-foreground">{emptyMessage}</p>
  }

  return (
    <div className={cn('divide-y divide-border', className)}>
      {shown.map((alert, index) => (
        <AlertRow key={`${alert.ruleId ?? 'rule'}-${index}`} alert={alert} allAlerts={all} />
      ))}
      {typeof limit === 'number' && sorted.length > shown.length ? (
        <p className="pt-2 text-xs text-muted-foreground">
          {fmtCount(sorted.length - shown.length)} further alerts not shown here.
        </p>
      ) : null}
    </div>
  )
}

function AlertRow({ alert, allAlerts }: { alert: AuditAlert; allAlerts: AuditAlert[] }) {
  const [open, setOpen] = React.useState(false)
  const bodyId = React.useId()
  const { showEvidence } = useAudit()
  // The alert's own key, never a hand-picked wider one (BOR-91). The guard is
  // kept because a key this build does not know would fail server-side as
  // "Unknown drill-down key" — better to say so on the button.
  const evidence = alertEvidenceKey(alert)

  const headline = hasValue(alert.amount)
    ? fmtGel(alert.amount)
    : hasValue(alert.quantityKg)
      ? fmtKgSigned(alert.quantityKg)
      : ''

  const openEvidence = () => {
    if (!evidence.key) return
    showEvidence({
      key: evidence.key,
      subject: alert.subjects?.length === 1 ? alert.subjects[0] : undefined,
      label: alert.title ?? alert.ruleId ?? undefined,
    })
  }

  return (
    <div className={cn(open && 'bg-accent/30')}>
      <div className="flex items-center gap-2 py-1">
        <button
          type="button"
          aria-expanded={open}
          aria-controls={bodyId}
          onClick={() => setOpen((current) => !current)}
          className="flex min-w-0 flex-1 items-center gap-2 text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <ChevronRight
            className={cn(
              'h-3 w-3 shrink-0 text-muted-foreground transition-transform',
              open && 'rotate-90'
            )}
          />
          <SeverityBadge severity={alert.severity} className="px-1.5 py-0 text-[10px]" />
          <span className="shrink-0 rounded bg-muted px-1 text-[10px] font-medium text-muted-foreground">
            {flowLabel(alert.flow)}
          </span>
          <span
            className="hidden shrink-0 font-mono text-[10px] text-muted-foreground lg:inline"
            title={alert.ruleId ?? 'UNNAMED_RULE'}
          >
            {alert.ruleId ?? 'UNNAMED_RULE'}
          </span>
          <span className="min-w-0 flex-1 truncate text-xs font-medium" title={alert.title ?? ''}>
            {alert.title ?? 'Untitled rule'}
          </span>
          {headline ? (
            <span className="shrink-0 text-xs font-semibold tabular-nums">{headline}</span>
          ) : null}
          <span className="w-24 shrink-0 text-right text-[10px] tabular-nums text-muted-foreground">
            {fmtCount(alert.affectedRowCount)} {alert.affectedRowCount === 1 ? 'row' : 'rows'}
          </span>
        </button>
        <Button
          type="button"
          size="sm"
          variant="ghost"
          className="h-6 w-6 shrink-0 p-0"
          disabled={!evidence.key}
          aria-label={`Show evidence for ${alert.title ?? alert.ruleId ?? 'this rule'}`}
          title={evidence.blockedReason ?? 'Show this rule’s own source rows'}
          onClick={openEvidence}
        >
          <ArrowUpRight className="h-3.5 w-3.5" />
        </Button>
      </div>

      {open ? (
        <div id={bodyId} className="pb-2 pl-5 pr-1">
          <CaseDetail alert={alert} allAlerts={allAlerts} onShowEvidence={openEvidence} />
        </div>
      ) : null}
    </div>
  )
}

/**
 * The arithmetic behind one alert — what the standalone "case anatomy" panel
 * used to render for whichever alert was selected.
 */
function CaseDetail({
  alert,
  allAlerts,
  onShowEvidence,
}: {
  alert: AuditAlert
  allAlerts: AuditAlert[]
  onShowEvidence: () => void
}) {
  const subjects = subjectsOf([alert])
  const grouped = alertsByFlow(allAlerts)
  const ownFlow = (alert.flow ?? '').toUpperCase() as FlowKey
  const touched = FLOW_KEYS.map((flow) => {
    if (flow === ownFlow) return { flow, alerts: [alert], own: true }
    return { flow, alerts: alertsTouching(grouped[flow], subjects), own: false }
  })
  const inputs = Object.entries(alert.inputs ?? {})
  const evidence = alertEvidenceKey(alert)

  return (
    <div className="grid grid-cols-1 gap-2 lg:grid-cols-2">
      <div className="rounded-md border border-border bg-muted/40 p-2">
        <div className="text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">
          Formula
        </div>
        <p className="mt-0.5 font-mono text-[11px] leading-snug">
          {alert.formula ?? 'The rule sent no formula.'}
        </p>

        {inputs.length > 0 ? (
          <table className="mt-1.5 w-full text-[11px]">
            <tbody>
              {inputs.map(([name, value]) => (
                <tr key={name} className="border-t border-border/70">
                  <td className="py-0.5 pr-2 text-muted-foreground">{name}</td>
                  <td className="py-0.5 text-right font-medium tabular-nums">
                    {formatInput(value)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <p className="mt-1 text-[11px] text-muted-foreground">The rule sent no named inputs.</p>
        )}

        <div className="mt-1.5 flex gap-6 border-t border-border/70 pt-1.5 text-[11px]">
          <span>
            <span className="text-muted-foreground">Amount </span>
            <span className="font-semibold tabular-nums">{fmtGel(alert.amount)}</span>
          </span>
          <span>
            <span className="text-muted-foreground">Quantity </span>
            <span className="font-semibold tabular-nums">
              {hasValue(alert.quantityKg) ? fmtKgSigned(alert.quantityKg) : '—'}
            </span>
          </span>
        </div>
      </div>

      <div className="rounded-md border border-border p-2">
        <div className="text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">
          Flows affected
        </div>
        <div className="mt-0.5">
          {touched.map(({ flow, alerts, own }) => (
            <div key={flow} className="flex items-center justify-between gap-2 text-[11px]">
              <span className="flex items-center gap-1.5">
                {flowLabel(flow)}
                {own ? (
                  <Badge variant="secondary" className="px-1 py-0 text-[9px]">
                    origin
                  </Badge>
                ) : null}
              </span>
              <span className={alerts.length > 0 ? 'font-medium' : 'text-muted-foreground'}>
                {alerts.length > 0 ? `${fmtCount(alerts.length)} linked rules` : 'no linked rule'}
              </span>
            </div>
          ))}
        </div>

        {subjects.length === 0 ? (
          <p className="mt-1 text-[10px] leading-snug text-muted-foreground">
            This rule names no subject, so cross-flow links cannot be established for it.
          </p>
        ) : (
          // Every subject, never a truncated "+64" — they are the link this
          // panel asserts. Capped in height rather than in content, because one
          // rule naming seventy products would otherwise own the whole row.
          <div className="mt-1 max-h-16 overflow-y-auto text-[10px] leading-snug text-muted-foreground">
            Linked through {fmtCount(subjects.length)}{' '}
            {subjects.length === 1 ? 'subject' : 'subjects'}: {subjects.join(', ')}
          </div>
        )}

        <div className="mt-1.5 flex flex-wrap items-center gap-2 border-t border-border/70 pt-1.5">
          <Button
            type="button"
            size="sm"
            variant="outline"
            className="h-6 px-2 text-[11px]"
            disabled={!evidence.key}
            title={evidence.blockedReason}
            onClick={onShowEvidence}
          >
            Show evidence ({fmtCount(alert.affectedRowCount)} rows)
            <ArrowUpRight className="ml-1 h-3 w-3" />
          </Button>
          <p className="min-w-0 flex-1 text-[10px] leading-snug text-muted-foreground">
            {evidence.key === null ? (
              evidence.blockedReason
            ) : (
              <>
                Scrolls to the {sectionForDrilldownKey(evidence.key)} section and opens{' '}
                <span className="font-mono">{evidence.key}</span> there — this rule&apos;s own rows,
                not a wider set.
              </>
            )}
          </p>
        </div>
      </div>
    </div>
  )
}
