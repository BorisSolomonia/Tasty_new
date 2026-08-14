/**
 * The expansion of one aggregate into the exact rows that compose it (§11).
 *
 * The dialog does one thing the headline figures cannot: it re-adds the rows it
 * received and shows that sum next to the total the backend reported. When the
 * two disagree the reader sees the disagreement instead of a tidy number.
 */
import * as React from 'react'
import { ArrowLeft } from 'lucide-react'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { Badge } from '@/components/ui/badge'
import { apiErrorMessage } from '@/lib/api-client'
import type { AuditSourceRow } from '@/lib/audit-api'
import { useAuditDrilldown } from '@/hooks/use-audit-flows'
import { useAudit } from './audit-context'
import { SourceRowTable, sourceRowKey } from './source-row-table'
import { MappingEditor } from './mapping-editor'
import { EM_DASH, fmtCount, fmtGel, fmtKgSigned, hasValue } from './format'
import { PendingNotice } from './pending-notice'

const TOLERANCE = 0.01

export function DrilldownDialog() {
  const { drilldown, closeDrilldown, filters } = useAudit()
  const [selected, setSelected] = React.useState<AuditSourceRow | null>(null)

  const open = drilldown !== null

  React.useEffect(() => {
    if (!open) setSelected(null)
  }, [open, drilldown?.key])

  const query = useAuditDrilldown(
    {
      key: drilldown?.key ?? '',
      startDate: filters.startDate,
      endDate: filters.endDate,
      subject: drilldown?.subject,
    },
    open
  )

  const data = query.data
  const rows = data?.rows ?? []

  const rowSum = rows.reduce((sum, row) => sum + (row.amount ?? 0), 0)
  const reportedTotal = data?.total ?? null
  const totalsDisagree =
    hasValue(reportedTotal) && rows.length > 0 && !data?.truncated
      ? Math.abs(Math.abs(reportedTotal) - Math.abs(rowSum)) > TOLERANCE
      : false

  return (
    <Dialog open={open} onOpenChange={(next) => (next ? undefined : closeDrilldown())}>
      <DialogContent size="wide">
        <DialogHeader>
          <DialogTitle>{data?.label ?? drilldown?.label ?? 'Drill-down'}</DialogTitle>
          <DialogDescription>
            <span className="font-mono text-xs">{drilldown?.key}</span>
            {drilldown?.subject ? <span className="ml-2">· {drilldown.subject}</span> : null}
          </DialogDescription>
        </DialogHeader>

        <div className="min-h-0 flex-1 overflow-y-auto">
          {query.isLoading ? (
            <div className="space-y-2">
              <PendingNotice active what="the source rows behind this figure" />
              <Skeleton className="h-6 w-2/3" />
              <Skeleton className="h-32 w-full" />
            </div>
          ) : null}

          {query.isError ? (
            <div className="rounded-md border border-destructive/50 bg-destructive/10 p-3 text-sm text-destructive">
              <p className="font-semibold">This aggregate could not be expanded.</p>
              <p className="mt-1 text-xs">
                {apiErrorMessage(query.error)}
              </p>
              <p className="mt-1 text-xs">
                The headline figure above is therefore unverified in this session.
              </p>
            </div>
          ) : null}

          {data && !selected ? (
            <div className="space-y-3">
              {data.definition ? (
                <p className="text-sm text-muted-foreground">{data.definition}</p>
              ) : null}

              <div className="flex flex-wrap gap-4 rounded-md border border-border bg-muted/40 p-3 text-sm">
                <Summary label="Reported total" value={fmtGel(data.total)} />
                <Summary label="Quantity" value={fmtKgSigned(data.totalQuantityKg)} />
                <Summary label="Rows" value={fmtCount(data.rowCount)} />
                <Summary
                  label="Σ of rows shown"
                  value={rows.length > 0 ? fmtGel(rowSum) : EM_DASH}
                />
              </div>

              {totalsDisagree ? (
                <p className="rounded-md border border-warning bg-warning/10 p-2 text-xs">
                  The rows returned sum to {fmtGel(rowSum)}, which differs from the reported total of{' '}
                  {fmtGel(data.total)}. Shown as-is rather than reconciled.
                </p>
              ) : null}

              {data.truncated ? (
                <p className="rounded-md border border-warning bg-warning/10 p-2 text-xs">
                  This list was capped by the server. It shows {fmtCount(rows.length)} of{' '}
                  {fmtCount(data.rowCount)} rows — not the complete set.
                </p>
              ) : null}

              <SourceRowTable
                rows={rows}
                onSelect={setSelected}
                onMap={setSelected}
                emptyMessage="The backend returned no rows for this aggregate."
              />
            </div>
          ) : null}

          {selected ? (
            <div className="space-y-3">
              <div className="flex items-center gap-2">
                <Button type="button" variant="ghost" size="sm" onClick={() => setSelected(null)}>
                  <ArrowLeft className="mr-1 h-4 w-4" />
                  Back to rows
                </Button>
                <Badge variant="outline" className="font-mono text-[11px]">
                  {sourceRowKey(selected)}
                </Badge>
              </div>
              <MappingEditor row={selected} onSaved={() => void query.refetch()} />
            </div>
          ) : null}
        </div>
      </DialogContent>
    </Dialog>
  )
}

function Summary({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="text-[11px] uppercase tracking-wide text-muted-foreground">{label}</div>
      <div className="font-semibold tabular-nums">{value}</div>
    </div>
  )
}
