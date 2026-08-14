/**
 * Everything the audit found, in one place (BOR-91).
 *
 * This merges what used to be three separate screens: the top-cases list, the
 * case anatomy, and the risk summary. They were three renderings of one array —
 * `flows.alerts` — and keeping them apart meant the same rule appeared three
 * times with three different affordances.
 *
 * The arrangement here is: the list, the anatomy of whichever case is selected,
 * and then the two summaries that answer "where is this concentrated?" — by
 * rule, by month, and by subject across flows.
 *
 * Every "show evidence" affordance uses the alert's own `drilldownKey`. The
 * broad keys (`cash.bankRows`, `documentation.rows`) are reachable only from
 * the explicit "browse all" buttons in the Cash and Documentation sections —
 * never from a problem, because expanding a four-row finding into every
 * statement row changes the scope under the reader.
 *
 * Evidence no longer opens in a dialog: it scrolls to the section that browses
 * that kind of row and renders there (see `evidence.ts`), so the reader lands
 * somewhere they can carry on working instead of on a layer over the page.
 */
import * as React from 'react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/cn'
import type { AuditAlert } from '@/lib/audit-api'
import { useAudit } from './audit-context'
import { CollapsiblePanel } from './collapsible-panel'
import { MetricCard, SectionCard, FormulaNote } from './metric'
import { AlertList, alertEvidenceKey, sortAlerts } from './alert-list'
import { sectionForDrilldownKey } from './evidence'
import { SeverityBadge } from './status-badge'
import { SourceRowTable } from './source-row-table'
import {
  alertsByFlow,
  alertsTouching,
  crossFlowClusters,
  flowWeights,
  isUnresolved,
  monthBuckets,
  subjectsOf,
  FLOW_KEYS,
  type FlowKey,
} from './cross-flow'
import {
  fmtCount,
  fmtGel,
  fmtKgSigned,
  flowLabel,
  formatInput,
  hasValue,
  severityWeight,
} from './format'

export function ProblemsPanel() {
  const { flows, flowsQuery, sourceRows, sourceRowsQuery } = useAudit()
  const [selected, setSelected] = React.useState<AuditAlert | null>(null)
  const [selectedMonth, setSelectedMonth] = React.useState<string | null>(null)

  const alerts = flows?.alerts
  const queue = sortAlerts(alerts)
  const active = selected ?? queue[0] ?? null

  const weights = flowWeights(alerts)
  const buckets = React.useMemo(() => monthBuckets(sourceRows), [sourceRows])
  const maxUnresolved = buckets.reduce((max, bucket) => Math.max(max, bucket.unresolved), 0)
  // Named on the closed header, so shutting the grid does not hide the finding.
  const worstMonth = buckets.reduce<(typeof buckets)[number] | null>(
    (worst, bucket) => (worst === null || bucket.unresolved > worst.unresolved ? bucket : worst),
    null
  )
  const clusters = React.useMemo(() => crossFlowClusters(alerts).slice(0, 12), [alerts])

  const byRule = React.useMemo(() => {
    const map = new Map<
      string,
      { ruleId: string; flow: string | null; count: number; amount: number; weight: number }
    >()
    for (const alert of alerts ?? []) {
      const ruleId = alert.ruleId ?? 'UNNAMED_RULE'
      const entry = map.get(ruleId) ?? { ruleId, flow: alert.flow, count: 0, amount: 0, weight: 0 }
      entry.count += 1
      entry.amount += Math.abs(alert.amount ?? 0)
      entry.weight += severityWeight(alert.severity)
      map.set(ruleId, entry)
    }
    return [...map.values()].sort((a, b) => b.weight - a.weight || b.amount - a.amount)
  }, [alerts])

  const monthRows = React.useMemo(
    () =>
      selectedMonth
        ? sourceRows.filter((row) => (row.date ?? '').startsWith(selectedMonth) && isUnresolved(row))
        : [],
    [sourceRows, selectedMonth]
  )

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 gap-2 md:grid-cols-3">
        {weights.map((entry) => (
          <MetricCard
            key={entry.flow}
            label={`${flowLabel(entry.flow)} severity weight`}
            value={fmtCount(entry.weight)}
            tone={entry.critical > 0 ? 'bad' : entry.count > 0 ? 'warn' : 'good'}
            description={`${fmtCount(entry.count)} rules · ${fmtCount(entry.critical)} critical`}
          />
        ))}
      </div>

      <p className="rounded-md border border-border bg-muted/40 p-2 text-xs text-muted-foreground">
        Severity weight = CRITICAL×4 + HIGH×3 + MEDIUM×2 + LOW×1, summed over the rules that fired in
        this period. It is computed here from the alert list, not sent by the backend, and it is
        deliberately not scaled to 100 — there is no ceiling to scale against.
      </p>

      <div className="grid grid-cols-1 gap-3 xl:grid-cols-[minmax(0,1.35fr)_minmax(0,1fr)]">
        <SectionCard
          title="Cases"
          subtitle="Every rule that fired, ranked by severity then amount. Select one to open its anatomy."
        >
          {flowsQuery.isLoading ? (
            <Skeleton className="h-64 w-full" />
          ) : (
            <AlertList
              alerts={alerts}
              onSelect={setSelected}
              selectedRuleId={active?.ruleId ?? null}
              emptyMessage="No rule fired for this period. That is a result, not a guarantee — check the data warnings above."
            />
          )}
        </SectionCard>

        <CaseAnatomy alert={active} allAlerts={alerts ?? []} />
      </div>

      <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
        <CollapsiblePanel
          title="Monthly concentration of unresolved rows"
          summary={
            worstMonth
              ? `${fmtCount(buckets.length)} months · worst ${worstMonth.month} with ${fmtCount(
                  worstMonth.unresolved
                )} unresolved`
              : `${fmtCount(buckets.length)} months`
          }
          subtitle="Counted from the dates on the shared source-row feed. Select a month to list its rows."
        >
          {sourceRowsQuery.isLoading ? (
            <Skeleton className="h-32 w-full" />
          ) : buckets.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              The shared row feed returned no dated rows, so no month grid can be built.
            </p>
          ) : (
            <>
              <div className="grid grid-cols-3 gap-1.5 sm:grid-cols-4 lg:grid-cols-6">
                {buckets.map((bucket) => {
                  const ratio = maxUnresolved > 0 ? bucket.unresolved / maxUnresolved : 0
                  return (
                    <button
                      key={bucket.month}
                      type="button"
                      onClick={() =>
                        setSelectedMonth((current) => (current === bucket.month ? null : bucket.month))
                      }
                      title={`${bucket.month}: ${bucket.unresolved} unresolved of ${bucket.total} rows`}
                      className={cn(
                        'rounded-md border p-1.5 text-left text-[11px] transition-colors',
                        selectedMonth === bucket.month ? 'border-primary' : 'border-border',
                        ratio === 0 && 'bg-muted',
                        ratio > 0 && ratio <= 0.33 && 'bg-warning/20',
                        ratio > 0.33 && ratio <= 0.66 && 'bg-destructive/25',
                        ratio > 0.66 && 'bg-destructive/50'
                      )}
                    >
                      <div className="font-semibold">{bucket.month}</div>
                      <div className="tabular-nums">{fmtCount(bucket.unresolved)} unresolved</div>
                      <div className="tabular-nums text-muted-foreground">
                        of {fmtCount(bucket.total)}
                      </div>
                    </button>
                  )
                })}
              </div>
              <FormulaNote>
                Unresolved = a row whose mapping status is UNMAPPED, PARTIALLY_MAPPED or SUGGESTED —
                a suggestion nobody accepted still counts as unresolved. Shading is relative to the
                worst month shown, not to an absolute scale.
              </FormulaNote>
            </>
          )}
        </CollapsiblePanel>

        <CollapsiblePanel
          title="Risk by rule"
          summary={`${fmtCount(byRule.length)} rules · ${fmtGel(
            byRule.reduce((sum, entry) => sum + entry.amount, 0)
          )}`}
          subtitle="Every rule that fired, grouped by rule id and ranked by severity weight."
        >
          {byRule.length === 0 ? (
            <p className="text-sm text-muted-foreground">No rule fired for this period.</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-[11px]">
                <thead>
                  <tr className="border-b border-border text-left text-muted-foreground">
                    <th className="py-1 pr-2 font-semibold">Rule</th>
                    <th className="py-1 pr-2 font-semibold">Flow</th>
                    <th className="py-1 pr-2 text-right font-semibold">Fired</th>
                    <th className="py-1 pr-2 text-right font-semibold">Σ amount</th>
                    <th className="py-1 text-right font-semibold">Weight</th>
                  </tr>
                </thead>
                <tbody>
                  {byRule.map((entry) => (
                    <tr key={entry.ruleId} className="border-b border-border/70">
                      <td className="py-1 pr-2 font-mono text-[11px]">{entry.ruleId}</td>
                      <td className="py-1 pr-2">{flowLabel(entry.flow)}</td>
                      <td className="py-1 pr-2 text-right tabular-nums">{fmtCount(entry.count)}</td>
                      <td className="py-1 pr-2 text-right tabular-nums">{fmtGel(entry.amount)}</td>
                      <td className="py-1 text-right font-semibold tabular-nums">
                        {fmtCount(entry.weight)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CollapsiblePanel>
      </div>

      {selectedMonth ? (
        <CollapsiblePanel
          key={selectedMonth}
          title={`Unresolved rows in ${selectedMonth}`}
          summary={`${fmtCount(monthRows.length)} rows`}
          subtitle="From the same feed every section reads."
          // Opened because the reader just asked for this month by clicking it.
          defaultOpen
          highlight
          actions={
            <button
              type="button"
              className="text-[11px] text-primary hover:underline"
              onClick={() => setSelectedMonth(null)}
            >
              Clear month
            </button>
          }
        >
          <SourceRowTable rows={monthRows} emptyMessage="No unresolved rows in this month." />
        </CollapsiblePanel>
      ) : null}

      <CollapsiblePanel
        title="Cross-flow clusters"
        summary={`${fmtCount(clusters.length)} subjects named in more than one flow`}
        subtitle="Subjects named by rules in more than one flow. The link is the rule's own subject list, not an inference."
      >
        {clusters.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            No rule named a product or counterparty, so no cluster can be formed.
          </p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-[11px]">
              <thead>
                <tr className="border-b border-border text-left text-muted-foreground">
                  <th className="py-1 pr-2 font-semibold">Subject</th>
                  {FLOW_KEYS.map((flow) => (
                    <th key={flow} className="py-1 pr-2 text-right font-semibold">
                      {flowLabel(flow)}
                    </th>
                  ))}
                  <th className="py-1 text-right font-semibold">Flows</th>
                </tr>
              </thead>
              <tbody>
                {clusters.map((cluster) => (
                  <tr key={cluster.subject} className="border-b border-border/70">
                    <td className="max-w-[16rem] py-1 pr-2">
                      <div className="truncate font-medium" title={cluster.subject}>
                        {cluster.subject}
                      </div>
                    </td>
                    {FLOW_KEYS.map((flow: FlowKey) => {
                      const flowAlerts = cluster.byFlow[flow]
                      const amount = flowAlerts.reduce(
                        (sum, alert) => sum + Math.abs(alert.amount ?? 0),
                        0
                      )
                      return (
                        <td key={flow} className="py-1 pr-2 text-right tabular-nums">
                          {flowAlerts.length === 0 ? (
                            <span className="text-muted-foreground">—</span>
                          ) : (
                            <span>
                              {fmtCount(flowAlerts.length)}
                              {amount > 0 ? ` · ${fmtGel(amount)}` : ''}
                            </span>
                          )}
                        </td>
                      )
                    })}
                    <td className="py-1 text-right font-semibold tabular-nums">
                      {cluster.flowCount}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </CollapsiblePanel>
    </div>
  )
}

function CaseAnatomy({ alert, allAlerts }: { alert: AuditAlert | null; allAlerts: AuditAlert[] }) {
  const { showEvidence } = useAudit()

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
  const evidence = alertEvidenceKey(alert)

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
          disabled={!evidence.key}
          title={evidence.blockedReason}
          onClick={() =>
            evidence.key &&
            showEvidence({
              key: evidence.key,
              subject: subjects.length === 1 ? subjects[0] : undefined,
              label: alert.title ?? undefined,
            })
          }
        >
          Show source evidence ({fmtCount(alert.affectedRowCount)} rows)
        </Button>
        {evidence.key === null ? (
          <p className="text-[11px] text-muted-foreground">{evidence.blockedReason}</p>
        ) : (
          <p className="text-[11px] text-muted-foreground">
            Scrolls to the {sectionForDrilldownKey(evidence.key)} section and opens{' '}
            <span className="font-mono">{evidence.key}</span> there — this rule&apos;s own rows, not
            a wider set.
          </p>
        )}
      </div>
    </SectionCard>
  )
}
