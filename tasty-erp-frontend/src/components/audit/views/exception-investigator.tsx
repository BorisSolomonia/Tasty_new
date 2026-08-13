/**
 * 03 — Exception Investigator.
 *
 * The same three flows, organised around cases rather than totals. Selecting a
 * case shows its anatomy: the formula, the inputs it was evaluated on, and the
 * other flows that name the same subjects.
 */
import * as React from 'react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import type { AuditAlert } from '@/lib/audit-api'
import { useAudit } from '../audit-context'
import { ThreeFlowStrip } from '../three-flow-strip'
import { MetricCard, SectionCard } from '../metric'
import { AlertList, sortAlerts } from '../alert-list'
import { SeverityBadge } from '../status-badge'
import { alertsByFlow, alertsTouching, subjectsOf, FLOW_KEYS, type FlowKey } from '../cross-flow'
import { fmtCount, fmtGel, fmtKgSigned, flowLabel, formatInput, hasValue } from '../format'

export function ExceptionInvestigator() {
  const { flows } = useAudit()
  const [selected, setSelected] = React.useState<AuditAlert | null>(null)

  const grouped = alertsByFlow(flows?.alerts)
  const queue = sortAlerts(flows?.alerts)

  const active = selected ?? queue[0] ?? null

  return (
    <div className="space-y-4">
      <ThreeFlowStrip />

      <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
        {FLOW_KEYS.map((flow) => (
          <MetricCard
            key={flow}
            label={`${flowLabel(flow)} cases`}
            value={fmtCount(grouped[flow].length)}
            tone={grouped[flow].length > 0 ? 'bad' : 'good'}
            description={`${fmtCount(
              grouped[flow].filter((a) => (a.severity ?? '').toUpperCase() === 'CRITICAL').length
            )} critical · ${fmtCount(
              grouped[flow].reduce((sum, alert) => sum + alert.affectedRowCount, 0)
            )} source rows`}
          />
        ))}
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)]">
        <SectionCard
          title="Case queue"
          subtitle="Every rule that fired, ranked by severity then amount. Select one to open its anatomy."
        >
          <AlertList
            alerts={flows?.alerts}
            onSelect={setSelected}
            selectedRuleId={active?.ruleId ?? null}
            emptyMessage="No rule fired for this period. That is a result, not a guarantee — check the data warnings above."
          />
        </SectionCard>

        <CaseAnatomy alert={active} allAlerts={flows?.alerts ?? []} />
      </div>
    </div>
  )
}

function CaseAnatomy({
  alert,
  allAlerts,
}: {
  alert: AuditAlert | null
  allAlerts: AuditAlert[]
}) {
  const { openDrilldown } = useAudit()

  if (!alert) {
    return (
      <SectionCard title="Case anatomy" subtitle="Select a case to see how it was computed.">
        <p className="text-sm text-muted-foreground">No case selected.</p>
      </SectionCard>
    )
  }

  const subjects = subjectsOf([alert])
  const grouped = alertsByFlow(allAlerts)
  const ownFlow = (alert.flow ?? '').toUpperCase() as FlowKey
  const touched = FLOW_KEYS.map((flow) => {
    if (flow === ownFlow) return { flow, alerts: [alert], own: true }
    return { flow, alerts: alertsTouching(grouped[flow], subjects), own: false }
  })
  const inputs = Object.entries(alert.inputs ?? {})

  return (
    <SectionCard
      title="Case anatomy"
      subtitle="The rule, the arithmetic and the flows it reaches."
      actions={<SeverityBadge severity={alert.severity} />}
    >
      <div className="space-y-3">
        <div>
          <div className="font-mono text-[11px] text-muted-foreground">
            {alert.ruleId ?? 'UNNAMED_RULE'}
          </div>
          <p className="mt-0.5 text-sm font-medium">{alert.title ?? 'Untitled rule'}</p>
        </div>

        <div className="rounded-md border border-border bg-muted/40 p-3">
          <div className="text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
            Formula
          </div>
          <p className="mt-1 font-mono text-xs">{alert.formula ?? 'The rule sent no formula.'}</p>

          {inputs.length > 0 ? (
            <table className="mt-2 w-full text-xs">
              <tbody>
                {inputs.map(([name, value]) => (
                  <tr key={name} className="border-t border-border/70">
                    <td className="py-1 pr-2 text-muted-foreground">{name}</td>
                    <td className="py-1 text-right font-medium tabular-nums">{formatInput(value)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            <p className="mt-2 text-xs text-muted-foreground">The rule sent no named inputs.</p>
          )}
        </div>

        <div className="grid grid-cols-2 gap-3 text-sm">
          <div>
            <div className="text-[11px] uppercase tracking-wide text-muted-foreground">Amount</div>
            <div className="font-semibold tabular-nums">{fmtGel(alert.amount)}</div>
          </div>
          <div>
            <div className="text-[11px] uppercase tracking-wide text-muted-foreground">Quantity</div>
            <div className="font-semibold tabular-nums">
              {hasValue(alert.quantityKg) ? fmtKgSigned(alert.quantityKg) : '—'}
            </div>
          </div>
        </div>

        <div>
          <div className="text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
            Flows affected
          </div>
          <div className="mt-1 space-y-1">
            {touched.map(({ flow, alerts, own }) => (
              <div key={flow} className="flex items-center justify-between gap-2 text-xs">
                <span className="flex items-center gap-2">
                  {flowLabel(flow)}
                  {own ? <Badge variant="secondary">origin</Badge> : null}
                </span>
                <span className={alerts.length > 0 ? 'font-medium' : 'text-muted-foreground'}>
                  {alerts.length > 0 ? `${fmtCount(alerts.length)} linked rules` : 'no linked rule'}
                </span>
              </div>
            ))}
          </div>
          {subjects.length === 0 ? (
            <p className="mt-1 text-[11px] text-muted-foreground">
              This rule names no subject, so cross-flow links cannot be established for it.
            </p>
          ) : (
            <p className="mt-1 text-[11px] text-muted-foreground">
              Linked through: {subjects.slice(0, 6).join(', ')}
              {subjects.length > 6 ? ` +${subjects.length - 6}` : ''}
            </p>
          )}
        </div>

        <Button
          type="button"
          className="w-full"
          disabled={!alert.drilldownKey}
          title={alert.drilldownKey ? undefined : 'This rule returned no drill-down key.'}
          onClick={() =>
            alert.drilldownKey &&
            openDrilldown({
              key: alert.drilldownKey,
              subject: subjects.length === 1 ? subjects[0] : undefined,
              label: alert.title ?? undefined,
            })
          }
        >
          Open source evidence ({fmtCount(alert.affectedRowCount)} rows)
        </Button>
      </div>
    </SectionCard>
  )
}
