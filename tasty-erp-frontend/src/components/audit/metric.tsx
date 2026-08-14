/**
 * The label/value primitives shared by all eight variants.
 *
 * Anything that displays an aggregate can carry a `drilldownKey`; clicking then
 * expands that aggregate into the source rows it was computed from. Values are
 * pre-formatted strings so the em-dash rule in `format.ts` is applied once, at
 * the point the payload is read, rather than re-implemented per view.
 */
import * as React from 'react'
import { ChevronRight } from 'lucide-react'
import { Card } from '@/components/ui/card'
import { cn } from '@/lib/cn'
import { toneClass, type Tone } from './format'
import { useAudit } from './audit-context'
import type { DrilldownKey } from './drilldown-keys'

interface DrillProps {
  /**
   * Typed against the keys the backend actually resolves — an unsupported key
   * is a compile error here rather than a ValidationException at click time.
   */
  drilldownKey?: DrilldownKey
  subject?: string
}

export function useDrilldownHandler({ drilldownKey, subject }: DrillProps, label?: string) {
  const { showEvidence } = useAudit()
  if (!drilldownKey) return undefined
  return () => showEvidence({ key: drilldownKey, subject, label })
}

export interface MetricRowProps extends DrillProps {
  label: string
  value: string
  tone?: Tone
  /** Secondary text under the label — say what the figure means, not how big it is. */
  hint?: string
  className?: string
}

export function MetricRow({
  label,
  value,
  tone = 'neutral',
  hint,
  className,
  drilldownKey,
  subject,
}: MetricRowProps) {
  const onOpen = useDrilldownHandler({ drilldownKey, subject }, label)

  const body = (
    <>
      <span className="min-w-0 text-xs text-muted-foreground">
        <span className="block truncate">{label}</span>
        {hint ? <span className="block text-[10px] text-muted-foreground/80">{hint}</span> : null}
      </span>
      <span className="flex shrink-0 items-center gap-1">
        <span className={cn('text-xs font-semibold tabular-nums', toneClass[tone])}>{value}</span>
        {onOpen ? <ChevronRight className="h-3 w-3 text-muted-foreground" /> : null}
      </span>
    </>
  )

  if (!onOpen) {
    return (
      <div
        className={cn(
          'flex items-start justify-between gap-3 border-b border-border py-1 last:border-b-0',
          className
        )}
      >
        {body}
      </div>
    )
  }

  return (
    <button
      type="button"
      onClick={onOpen}
      className={cn(
        'flex w-full items-start justify-between gap-3 border-b border-border py-1 text-left last:border-b-0 hover:bg-accent/50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
        className
      )}
    >
      {body}
    </button>
  )
}

export interface MetricCardProps extends DrillProps {
  label: string
  value: string
  tone?: Tone
  description?: string
  icon?: React.ReactNode
  className?: string
}

export function MetricCard({
  label,
  value,
  tone = 'neutral',
  description,
  icon,
  className,
  drilldownKey,
  subject,
}: MetricCardProps) {
  const onOpen = useDrilldownHandler({ drilldownKey, subject }, label)

  return (
    <Card
      className={cn(
        'p-2.5',
        onOpen && 'cursor-pointer transition-colors hover:border-primary/40 hover:bg-accent/40',
        className
      )}
      onClick={onOpen}
      role={onOpen ? 'button' : undefined}
      tabIndex={onOpen ? 0 : undefined}
      onKeyDown={
        onOpen
          ? (event) => {
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault()
                onOpen()
              }
            }
          : undefined
      }
    >
      <div className="flex items-center justify-between gap-2">
        <span className="text-[10px] font-bold uppercase tracking-wider text-muted-foreground">
          {label}
        </span>
        {icon}
      </div>
      <div className={cn('mt-1 text-lg font-bold tabular-nums', toneClass[tone])}>{value}</div>
      {description ? (
        <p className="mt-0.5 text-[11px] leading-snug text-muted-foreground">{description}</p>
      ) : null}
    </Card>
  )
}

/** A titled panel. Used everywhere so the eight views share one visual rhythm. */
export function SectionCard({
  title,
  subtitle,
  actions,
  children,
  className,
  bodyClassName,
}: {
  title: string
  subtitle?: string
  actions?: React.ReactNode
  children: React.ReactNode
  className?: string
  bodyClassName?: string
}) {
  return (
    <Card className={cn('flex flex-col overflow-hidden', className)}>
      <div className="flex items-start justify-between gap-3 border-b border-border px-3 py-1.5">
        <div className="min-w-0">
          <h3 className="text-xs font-semibold tracking-tight">{title}</h3>
          {subtitle ? (
            <p className="mt-0.5 text-[11px] leading-snug text-muted-foreground">{subtitle}</p>
          ) : null}
        </div>
        {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
      </div>
      <div className={cn('flex-1 px-3 py-2', bodyClassName)}>{children}</div>
    </Card>
  )
}

/** Explains where a derived figure came from. Used instead of silent arithmetic. */
export function FormulaNote({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <p className={cn('mt-1.5 text-[11px] leading-snug text-muted-foreground', className)}>
      {children}
    </p>
  )
}
