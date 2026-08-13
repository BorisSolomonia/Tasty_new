/**
 * Says out loud that the shared row feed was capped — using the server's own
 * account of what it left out.
 *
 * Every figure here comes from `AuditSourceRowPageDto`: how many rows matched,
 * how many came back, and the span those rows actually cover versus the span
 * that was asked for. Nothing is inferred from the rows on hand, because a
 * client guessing at its own blind spot is exactly the failure this notice
 * exists to prevent.
 */
import { AlertTriangle } from 'lucide-react'
import { cn } from '@/lib/cn'
import { useAudit } from './audit-context'
import { fmtCount, fmtDate } from './format'

export function FeedCapNotice({ className }: { className?: string }) {
  const { sourceRowsPage } = useAudit()

  if (!sourceRowsPage?.truncated) return null

  const { totalCount, returnedCount, coverageFrom, coverageTo, requestedFrom, requestedTo } =
    sourceRowsPage

  const missing = Math.max(totalCount - returnedCount, 0)
  const narrowerThanAsked =
    Boolean(coverageFrom && requestedFrom) && coverageFrom !== requestedFrom

  return (
    <div
      role="status"
      className={cn(
        'flex items-start gap-2 rounded-md border border-warning/60 bg-warning/10 p-3 dark:bg-warning/15',
        className
      )}
    >
      <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-warning" />
      <div className="min-w-0 text-sm">
        <p className="font-semibold">
          Showing {fmtCount(returnedCount)} of {fmtCount(totalCount)} rows —{' '}
          {fmtCount(missing)} not loaded
        </p>
        <p className="mt-0.5 text-xs">
          {narrowerThanAsked ? (
            <>
              These rows cover <b>{fmtDate(coverageFrom)} → {fmtDate(coverageTo)}</b>, while the
              filter asks for {fmtDate(requestedFrom)} → {fmtDate(requestedTo)}. Everything counted
              from this feed describes the covered span alone — a period with no rows here has not
              been shown to be clean, only left unread.
            </>
          ) : (
            <>
              Counts below are computed from the rows returned, not from every row in{' '}
              {fmtDate(requestedFrom)} → {fmtDate(requestedTo)}.
            </>
          )}
        </p>
        <p className="mt-1 text-xs text-muted-foreground">
          Narrow the date range to bring the rest into the feed. Aggregate figures in the three-flow
          strip are unaffected: they come from the full-period payload.
        </p>
      </div>
    </div>
  )
}
