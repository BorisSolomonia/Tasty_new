/**
 * The append-only change log for one source row.
 *
 * Nothing here can be edited or deleted, which is what makes voiding a mapping
 * safe: the withdrawn decision stays readable forever, next to who claimed to
 * have made it.
 */
import { History } from 'lucide-react'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/cn'
import type { AuditChangeLog, AuditSourceType } from '@/lib/audit-api'
import { useMappingHistory } from '@/hooks/use-audit-flows'
import { EM_DASH, fmtDate, fmtText } from './format'

export function ChangeHistory({
  sourceType,
  sourceRowId,
  fallback,
  className,
}: {
  sourceType: AuditSourceType | null | undefined
  sourceRowId: string | null | undefined
  /** History already embedded in the source row, used until the fetch lands. */
  fallback?: AuditChangeLog[] | null
  className?: string
}) {
  const query = useMappingHistory(sourceType, sourceRowId)
  const entries = query.data ?? fallback ?? []

  return (
    <div className={cn('space-y-2', className)}>
      <div className="flex items-center gap-2 text-xs font-semibold text-muted-foreground">
        <History className="h-3.5 w-3.5" />
        Change history
      </div>

      {query.isLoading && entries.length === 0 ? <Skeleton className="h-12 w-full" /> : null}

      {!query.isLoading && entries.length === 0 ? (
        <p className="text-xs text-muted-foreground">
          No manual change has been recorded for this row.
        </p>
      ) : null}

      {query.isError && entries.length === 0 ? (
        <p className="text-xs text-destructive">
          History could not be loaded — treat this as unknown, not as empty.
        </p>
      ) : null}

      <ol className="space-y-2 border-l border-border pl-3">
        {entries.map((entry, index) => (
          <li key={entry.id ?? `${entry.changedAt ?? ''}-${index}`} className="text-xs">
            <div className="flex flex-wrap items-baseline gap-x-2">
              <span className="font-medium">{fmtText(entry.field ?? entry.entityType)}</span>
              <span className="text-muted-foreground">{fmtDate(entry.changedAt)}</span>
              <span className="text-muted-foreground">
                by {fmtText(entry.changedBy)}
                <span className="ml-1 opacity-70">(self-declared)</span>
              </span>
            </div>
            <div className="mt-0.5 break-words text-muted-foreground">
              <span className="line-through opacity-70">{entry.oldValue ?? EM_DASH}</span>
              <span className="mx-1">→</span>
              <span>{entry.newValue ?? EM_DASH}</span>
            </div>
            {entry.reason ? <div className="mt-0.5 italic text-muted-foreground">{entry.reason}</div> : null}
          </li>
        ))}
      </ol>
    </div>
  )
}
