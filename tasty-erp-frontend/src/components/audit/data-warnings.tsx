/**
 * `dataWarnings` from the canonical payload, rendered above every variant.
 *
 * One of these warnings says bank outflows have not been imported yet. A reader
 * who misses that would read an empty cash flow as a clean cash flow, so this
 * banner is deliberately loud and is never collapsed by default.
 */
import { AlertTriangle, WifiOff } from 'lucide-react'
import { apiErrorMessage } from '@/lib/api-client'
import { cn } from '@/lib/cn'

export function DataWarnings({
  warnings,
  error,
  className,
}: {
  warnings: string[] | null | undefined
  error?: unknown
  className?: string
}) {
  const list = (warnings ?? []).filter((w) => typeof w === 'string' && w.trim().length > 0)

  if (error) {
    const message = apiErrorMessage(error)
    return (
      <div
        role="alert"
        className={cn(
          'flex items-start gap-3 rounded-lg border border-destructive/50 bg-destructive/10 p-3 text-destructive dark:bg-destructive/20',
          className
        )}
      >
        <WifiOff className="mt-0.5 h-4 w-4 shrink-0" />
        <div className="min-w-0 text-sm">
          <p className="font-semibold">The audit layer did not answer.</p>
          <p className="mt-0.5 break-words text-xs opacity-90">{message}</p>
          <p className="mt-1 text-xs opacity-90">
            Nothing below is a measurement of a clean period — it is the absence of an answer.
          </p>
        </div>
      </div>
    )
  }

  if (list.length === 0) return null

  return (
    <div
      role="status"
      className={cn(
        'rounded-lg border border-warning/60 bg-warning/10 p-3 dark:bg-warning/15',
        className
      )}
    >
      <div className="flex items-center gap-2">
        <AlertTriangle className="h-4 w-4 shrink-0 text-warning" />
        <span className="text-sm font-semibold">
          Data availability {list.length === 1 ? 'warning' : `warnings (${list.length})`}
        </span>
      </div>
      <ul className="mt-2 space-y-1 pl-6">
        {list.map((warning) => (
          <li key={warning} className="list-disc text-sm leading-snug">
            {warning}
          </li>
        ))}
      </ul>
      <p className="mt-2 pl-6 text-xs text-muted-foreground">
        A figure that is missing an input is not a figure showing zero risk.
      </p>
    </div>
  )
}
