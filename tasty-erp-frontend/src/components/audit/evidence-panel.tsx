/**
 * One problem's own rows, rendered inside the section that owns that kind of
 * row (BOR-91 follow-up). This replaces the drill-down dialog.
 *
 * What the dialog did that this must keep doing: it re-adds the rows it
 * received and prints that sum next to the total the backend reported, so a
 * disagreement between them is shown rather than smoothed over. It also states
 * the drill-down's own definition, and says when the server capped the list —
 * a capped list read as a complete one is the failure this panel exists to
 * prevent.
 *
 * What it does differently: the rows appear where the reader can keep working
 * on them, under a chip naming the problem they belong to, with a Clear that
 * puts the section back to its normal contents. The panel renders exactly the
 * response for `evidence.key` — never the section's full data set, and never a
 * wider key than the problem's own.
 */
import * as React from 'react'
import { X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { apiErrorMessage } from '@/lib/api-client'
import type { AuditSourceRow } from '@/lib/audit-api'
import { useAuditDrilldown } from '@/hooks/use-audit-flows'
import { useAudit } from './audit-context'
import { MappingDialog } from './mapping-dialog'
import { SourceRowTable } from './source-row-table'
import { PendingNotice } from './pending-notice'
import { EM_DASH, fmtCount, fmtGel, fmtKgSigned, hasValue } from './format'
import type { EvidenceSection } from './evidence'

const TOLERANCE = 0.01

export function SectionEvidence({ section }: { section: EvidenceSection }) {
  const { evidence, clearEvidence, filters } = useAudit()
  const [mappingRow, setMappingRow] = React.useState<AuditSourceRow | null>(null)

  const active = evidence && evidence.section === section ? evidence : null

  const query = useAuditDrilldown(
    {
      key: active?.key ?? '',
      startDate: filters.startDate,
      endDate: filters.endDate,
      subject: active?.subject,
    },
    active !== null
  )

  if (!active) return null

  const data = query.data
  const rows = data?.rows ?? []
  const rowSum = rows.reduce((sum, row) => sum + (row.amount ?? 0), 0)
  const reportedTotal = data?.total ?? null
  const totalsDisagree =
    hasValue(reportedTotal) && rows.length > 0 && !data?.truncated
      ? Math.abs(Math.abs(reportedTotal) - Math.abs(rowSum)) > TOLERANCE
      : false

  return (
    <div className="scroll-mt-28 rounded-lg border border-primary/60 bg-primary/5 ring-1 ring-primary/20">
      <div className="flex flex-wrap items-center gap-2 border-b border-primary/30 px-3 py-2">
        <span className="rounded bg-primary px-1.5 py-0.5 text-[10px] font-bold uppercase tracking-wider text-primary-foreground">
          Evidence
        </span>
        <span className="min-w-0 text-xs font-semibold">
          Showing evidence for: {data?.label ?? active.label ?? active.key}
        </span>
        <span className="font-mono text-[10px] text-muted-foreground">{active.key}</span>
        {active.subject ? (
          <span className="text-[11px] text-muted-foreground">· {active.subject}</span>
        ) : null}
        <Button
          type="button"
          size="sm"
          variant="outline"
          className="ml-auto h-6 px-2 text-[11px]"
          onClick={clearEvidence}
        >
          <X className="mr-1 h-3 w-3" />
          Clear
        </Button>
      </div>

      <div className="px-3 py-2">
        {query.isLoading ? (
          <div className="space-y-2">
            <PendingNotice active what="the source rows behind this problem" />
            <Skeleton className="h-24 w-full" />
          </div>
        ) : null}

        {query.isError ? (
          <div className="rounded-md border border-destructive/50 bg-destructive/10 p-2 text-xs text-destructive">
            <p className="font-semibold">This problem could not be expanded.</p>
            <p className="mt-0.5">{apiErrorMessage(query.error)}</p>
            <p className="mt-0.5">
              The figure that raised it is therefore unverified in this session.
            </p>
          </div>
        ) : null}

        {data ? (
          <div className="space-y-2">
            {data.definition ? (
              <p className="text-[11px] leading-snug text-muted-foreground">{data.definition}</p>
            ) : null}

            <div className="flex flex-wrap gap-4 rounded-md border border-border bg-card px-3 py-1.5">
              <Summary label="Reported total" value={fmtGel(data.total)} />
              <Summary label="Quantity" value={fmtKgSigned(data.totalQuantityKg)} />
              <Summary label="Rows" value={fmtCount(data.rowCount)} />
              <Summary
                label="Σ of rows shown"
                value={rows.length > 0 ? fmtGel(rowSum) : EM_DASH}
              />
            </div>

            {totalsDisagree ? (
              <p className="rounded-md border border-warning bg-warning/10 p-1.5 text-[11px]">
                The rows returned sum to {fmtGel(rowSum)}, which differs from the reported total of{' '}
                {fmtGel(data.total)}. Shown as-is rather than reconciled.
              </p>
            ) : null}

            {data.truncated ? (
              <p className="rounded-md border border-warning bg-warning/10 p-1.5 text-[11px]">
                This list was capped by the server. It shows {fmtCount(rows.length)} of{' '}
                {fmtCount(data.rowCount)} rows — not the complete set.
              </p>
            ) : null}

            <SourceRowTable
              rows={rows}
              onMap={setMappingRow}
              emptyMessage="The backend returned no rows for this problem."
            />
          </div>
        ) : null}
      </div>

      <MappingDialog row={mappingRow} onClose={() => setMappingRow(null)} />
    </div>
  )
}

function Summary({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="text-[10px] uppercase tracking-wide text-muted-foreground">{label}</div>
      <div className="text-xs font-semibold tabular-nums">{value}</div>
    </div>
  )
}
