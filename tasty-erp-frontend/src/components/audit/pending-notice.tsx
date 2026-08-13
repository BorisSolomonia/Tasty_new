/**
 * A waiting state that tells the truth about how long the wait is.
 *
 * A wide date range scans a lot of source documents, and a skeleton alone reads
 * as a hung page — the reader's next move is to reload, which starts the scan
 * again. So the wait is named and the elapsed count shows the page is working.
 * The copy deliberately quotes no fixed duration: the backend's per-row history
 * N+1 was removed on 2026-08-13 and the same call went from ~70s to ~3s, so any
 * number written here would have been stale within the day.
 */
import * as React from 'react'
import { Loader2 } from 'lucide-react'
import { cn } from '@/lib/cn'

export function useElapsedSeconds(active: boolean): number {
  const [elapsed, setElapsed] = React.useState(0)

  React.useEffect(() => {
    if (!active) {
      setElapsed(0)
      return
    }
    const startedAt = Date.now()
    const timer = window.setInterval(() => {
      setElapsed(Math.round((Date.now() - startedAt) / 1000))
    }, 1000)
    return () => window.clearInterval(timer)
  }, [active])

  return elapsed
}

export function PendingNotice({
  active,
  what,
  className,
}: {
  active: boolean
  /** What is being read, in the reader's terms. */
  what: string
  className?: string
}) {
  const elapsed = useElapsedSeconds(active)
  if (!active) return null

  return (
    <div
      role="status"
      className={cn(
        'flex items-start gap-2 rounded-md border border-border bg-muted/40 p-3 text-sm',
        className
      )}
    >
      <Loader2 className="mt-0.5 h-4 w-4 shrink-0 animate-spin text-muted-foreground" />
      <div className="min-w-0">
        <p className="font-medium">
          Reading {what}
          {elapsed > 0 ? ` · ${elapsed}s` : ''}
        </p>
        <p className="mt-0.5 text-xs text-muted-foreground">
          {elapsed >= 20
            ? 'Still working. A wide date range scans more source documents — narrowing it returns faster.'
            : 'This scans the source documents for the selected period.'}
        </p>
      </div>
    </div>
  )
}
