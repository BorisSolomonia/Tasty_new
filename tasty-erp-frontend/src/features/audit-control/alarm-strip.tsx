import * as React from 'react'
import { AlertTriangle, CheckCircle2, CircleAlert } from 'lucide-react'
import type { AuditDashboard, DualLedger } from '@/types/domain'
import { formatCurrency } from '@/lib/utils'
import { cn } from '@/lib/cn'
import { deriveAlarms, type AlarmTone } from './derive'

const TONE: Record<AlarmTone, { chip: string; badge: string; Icon: typeof AlertTriangle; word: string }> = {
  crit: { chip: 'border-destructive/40', badge: 'bg-destructive text-destructive-foreground', Icon: AlertTriangle, word: 'critical' },
  warn: { chip: 'border-warning/50', badge: 'bg-warning text-warning-foreground', Icon: CircleAlert, word: 'warning' },
  ok: { chip: 'border-border', badge: 'bg-success text-success-foreground', Icon: CheckCircle2, word: 'ok' },
}

/**
 * Alarm strip (BOR-87 hybrid, from concept 3): what needs attention this
 * period, each chip carrying its count, its tone as an icon + word (never
 * colour alone), and a jump link to the section that holds the evidence.
 */
export function AlarmStrip({ dashboard, dual }: { dashboard: AuditDashboard | undefined; dual: DualLedger | undefined }) {
  const alarms = React.useMemo(() => deriveAlarms(dashboard, dual, { gel: formatCurrency }), [dashboard, dual])
  if (!dashboard && !dual) return null
  return (
    <nav aria-label="Alarms" className="flex flex-wrap gap-2">
      {alarms.map((a) => {
        const t = TONE[a.tone]
        return (
          <a
            key={a.id}
            href={`#${a.target}`}
            className={cn(
              'inline-flex min-h-9 items-center gap-2 rounded-full border bg-card px-3 py-1.5 text-sm text-foreground no-underline hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
              t.chip
            )}
          >
            <span className={cn('inline-grid h-5 min-w-5 place-items-center rounded-full px-1 text-xs font-bold', t.badge)}>{a.count}</span>
            <t.Icon className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
            <span className="sr-only">{t.word}: </span>
            <span>{a.text}</span>
          </a>
        )
      })}
    </nav>
  )
}
