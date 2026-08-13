/**
 * 07 — Risk Heatmap.
 *
 * The same payload summarised by severity, period and flow.
 *
 * Two honesty notes that shape this view. First, the backend sends no risk
 * score, so none is displayed: what is shown is a severity weight with its
 * formula printed next to it, not a fabricated "92 / 100". Second, the payload
 * carries no month dimension, so the grid is built from the dates on the shared
 * source-row feed — real rows, counted — and says so.
 */
import * as React from 'react'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/cn'
import { useAudit } from '../audit-context'
import { ThreeFlowStrip } from '../three-flow-strip'
import { MetricCard, SectionCard, FormulaNote } from '../metric'
import { AlertList } from '../alert-list'
import { SourceRowTable } from '../source-row-table'
import { FeedCapNotice } from '../feed-cap-notice'
import {
  crossFlowClusters,
  flowWeights,
  isUnresolved,
  monthBuckets,
  FLOW_KEYS,
  type FlowKey,
} from '../cross-flow'
import { fmtCount, fmtGel, flowLabel, severityWeight } from '../format'

export function RiskHeatmap() {
  const { flows, sourceRows, sourceRowsQuery } = useAudit()
  const [selectedMonth, setSelectedMonth] = React.useState<string | null>(null)

  const weights = flowWeights(flows?.alerts)
  const buckets = React.useMemo(() => monthBuckets(sourceRows), [sourceRows])
  const maxUnresolved = buckets.reduce((max, bucket) => Math.max(max, bucket.unresolved), 0)

  const byRule = React.useMemo(() => {
    const map = new Map<
      string,
      { ruleId: string; flow: string | null; count: number; amount: number; weight: number }
    >()
    for (const alert of flows?.alerts ?? []) {
      const ruleId = alert.ruleId ?? 'UNNAMED_RULE'
      const entry = map.get(ruleId) ?? {
        ruleId,
        flow: alert.flow,
        count: 0,
        amount: 0,
        weight: 0,
      }
      entry.count += 1
      entry.amount += Math.abs(alert.amount ?? 0)
      entry.weight += severityWeight(alert.severity)
      map.set(ruleId, entry)
    }
    return [...map.values()].sort((a, b) => b.weight - a.weight || b.amount - a.amount)
  }, [flows])

  const clusters = React.useMemo(() => crossFlowClusters(flows?.alerts).slice(0, 12), [flows])

  const monthRows = React.useMemo(
    () =>
      selectedMonth
        ? sourceRows.filter((row) => (row.date ?? '').startsWith(selectedMonth) && isUnresolved(row))
        : [],
    [sourceRows, selectedMonth]
  )

  return (
    <div className="space-y-4">
      <ThreeFlowStrip />

      <FeedCapNotice />

      <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
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

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <SectionCard
          title="Monthly concentration of unresolved rows"
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
              <div className="grid grid-cols-4 gap-1.5 sm:grid-cols-6 lg:grid-cols-6">
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
        </SectionCard>

        <SectionCard
          title="Risk by rule"
          subtitle="Every rule that fired, grouped by rule id and ranked by severity weight."
        >
          {byRule.length === 0 ? (
            <p className="text-sm text-muted-foreground">No rule fired for this period.</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-xs">
                <thead>
                  <tr className="border-b border-border text-left text-muted-foreground">
                    <th className="py-1.5 pr-2 font-semibold">Rule</th>
                    <th className="py-1.5 pr-2 font-semibold">Flow</th>
                    <th className="py-1.5 pr-2 text-right font-semibold">Fired</th>
                    <th className="py-1.5 pr-2 text-right font-semibold">Σ amount</th>
                    <th className="py-1.5 text-right font-semibold">Weight</th>
                  </tr>
                </thead>
                <tbody>
                  {byRule.map((entry) => (
                    <tr key={entry.ruleId} className="border-b border-border/70">
                      <td className="py-1.5 pr-2 font-mono text-[11px]">{entry.ruleId}</td>
                      <td className="py-1.5 pr-2">{flowLabel(entry.flow)}</td>
                      <td className="py-1.5 pr-2 text-right tabular-nums">{fmtCount(entry.count)}</td>
                      <td className="py-1.5 pr-2 text-right tabular-nums">{fmtGel(entry.amount)}</td>
                      <td className="py-1.5 text-right font-semibold tabular-nums">
                        {fmtCount(entry.weight)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </SectionCard>
      </div>

      {selectedMonth ? (
        <SectionCard
          title={`Unresolved rows in ${selectedMonth}`}
          subtitle="From the same feed every other view reads."
          actions={
            <button
              type="button"
              className="text-xs text-primary hover:underline"
              onClick={() => setSelectedMonth(null)}
            >
              Clear month
            </button>
          }
        >
          <SourceRowTable rows={monthRows} emptyMessage="No unresolved rows in this month." />
        </SectionCard>
      ) : null}

      <SectionCard
        title="Cross-flow clusters"
        subtitle="Subjects named by rules in more than one flow. The link is the rule's own subject list, not an inference."
      >
        {clusters.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            No rule named a product or counterparty, so no cluster can be formed.
          </p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr className="border-b border-border text-left text-muted-foreground">
                  <th className="py-1.5 pr-2 font-semibold">Subject</th>
                  {FLOW_KEYS.map((flow) => (
                    <th key={flow} className="py-1.5 pr-2 text-right font-semibold">
                      {flowLabel(flow)}
                    </th>
                  ))}
                  <th className="py-1.5 text-right font-semibold">Flows</th>
                </tr>
              </thead>
              <tbody>
                {clusters.map((cluster) => (
                  <tr key={cluster.subject} className="border-b border-border/70">
                    <td className="max-w-[16rem] py-1.5 pr-2">
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
                        <td key={flow} className="py-1.5 pr-2 text-right tabular-nums">
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
                    <td className="py-1.5 text-right font-semibold tabular-nums">
                      {cluster.flowCount}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      <SectionCard title="All fired rules" subtitle="The full list behind every number above.">
        <AlertList alerts={flows?.alerts} />
      </SectionCard>
    </div>
  )
}
