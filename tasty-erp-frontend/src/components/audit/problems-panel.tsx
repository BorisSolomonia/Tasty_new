/**
 * Everything the audit found, in one place (BOR-91).
 *
 * This merges what used to be three separate screens: the top-cases list, the
 * case anatomy, and the risk summary. They were three renderings of one array —
 * `flows.alerts` — and keeping them apart meant the same rule appeared three
 * times with three different affordances.
 *
 * The arrangement here is: the list — one line per rule, each carrying its own
 * arithmetic behind an expander — and then the summaries that answer "where is
 * this concentrated?" — by rule, by month, and by subject across flows.
 *
 * The case anatomy used to be a fourth thing: a panel beside the list showing
 * the selected alert's formula, inputs, subjects and evidence button. Now that
 * a row expands into exactly that, the panel was a second rendering of one
 * alert, so it lives inside the row instead (see `alert-list.tsx`).
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
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/cn'
import { useAudit } from './audit-context'
import { CollapsiblePanel } from './collapsible-panel'
import { SectionCard, FormulaNote } from './metric'
import { AlertList } from './alert-list'
import { SourceRowTable } from './source-row-table'
import {
  crossFlowClusters,
  flowWeights,
  isUnresolved,
  monthBuckets,
  FLOW_KEYS,
  type FlowKey,
} from './cross-flow'
import { fmtCount, fmtGel, flowLabel, severityWeight, toneClass } from './format'

export function ProblemsPanel() {
  const { flows, flowsQuery, sourceRows, sourceRowsQuery } = useAudit()
  const [selectedMonth, setSelectedMonth] = React.useState<string | null>(null)

  const alerts = flows?.alerts

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
      {/*
        Three figures and their formula, on two lines rather than in three
        cards. The cards were 86px of chrome around numbers that read as well
        inline, and the honesty note under them is the point — it says the
        weight is computed here and is not a score out of anything.
      */}
      <Card className="flex flex-wrap items-center gap-x-5 gap-y-1 px-3 py-1.5">
        <span className="text-[10px] font-bold uppercase tracking-wider text-muted-foreground">
          Severity weight
        </span>
        {weights.map((entry) => (
          <span key={entry.flow} className="flex items-baseline gap-1.5">
            <span className="text-[11px] text-muted-foreground">{flowLabel(entry.flow)}</span>
            <span
              className={cn(
                'text-sm font-bold tabular-nums',
                toneClass[entry.critical > 0 ? 'bad' : entry.count > 0 ? 'warn' : 'good']
              )}
            >
              {fmtCount(entry.weight)}
            </span>
            <span className="text-[10px] tabular-nums text-muted-foreground">
              {fmtCount(entry.count)} rules · {fmtCount(entry.critical)} critical
            </span>
          </span>
        ))}
        <p className="w-full text-[10px] leading-snug text-muted-foreground">
          CRITICAL×4 + HIGH×3 + MEDIUM×2 + LOW×1, summed over the rules that fired in this period.
          Computed here from the alert list, not sent by the backend, and deliberately not scaled to
          100 — there is no ceiling to scale against.
        </p>
      </Card>

      <SectionCard
        title="Cases"
        subtitle="Every rule that fired, ranked by severity then amount. One line each — open a row for its formula, inputs and subjects."
      >
        {flowsQuery.isLoading ? (
          <Skeleton className="h-40 w-full" />
        ) : (
          <AlertList
            alerts={alerts}
            emptyMessage="No rule fired for this period. That is a result, not a guarantee — check the data warnings above."
          />
        )}
      </SectionCard>

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
