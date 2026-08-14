/**
 * A panel that costs one line until it is asked for.
 *
 * The consolidated page renders every table it owns at full height, which put
 * the six sections at ~133,000px on a 1280px viewport — the readable summaries
 * were separated by tens of thousands of pixels of rows nobody had asked to
 * see. So every table-bearing block is now one of these: closed on load, and
 * still informative while closed, because the header carries the count and the
 * headline figure the open table would have shown.
 *
 * A closed panel that says nothing is worse than an open one — it hides the
 * finding as well as the rows. `summary` is therefore not optional decoration:
 * it is the reason a reader can leave the panel shut.
 *
 * Deliberately not `<details>`: its open state is owned by the browser, which
 * makes it invisible to React and unreliable in the Android WebView this
 * codebase also ships to.
 */
import * as React from 'react'
import { ChevronRight } from 'lucide-react'
import { Card } from '@/components/ui/card'
import { cn } from '@/lib/cn'

export interface CollapsiblePanelProps {
  title: string
  /** Shown in the header, open or closed — the count and the headline figure. */
  summary?: React.ReactNode
  /** What the panel is, shown once it is open. */
  subtitle?: string
  /** Header controls. Rendered outside the toggle, so they stay clickable. */
  actions?: React.ReactNode
  /** Panels are shut on load unless the section is small and is the point. */
  defaultOpen?: boolean
  /** Marks the panel as the reason the reader was scrolled here. */
  highlight?: boolean
  children: React.ReactNode
  className?: string
  bodyClassName?: string
}

export function CollapsiblePanel({
  title,
  summary,
  subtitle,
  actions,
  defaultOpen = false,
  highlight = false,
  children,
  className,
  bodyClassName,
}: CollapsiblePanelProps) {
  const [open, setOpen] = React.useState(defaultOpen)
  const bodyId = React.useId()

  return (
    <Card
      className={cn(
        'overflow-hidden',
        highlight && 'border-primary/60 ring-1 ring-primary/30',
        className
      )}
    >
      <div className="flex items-center gap-2 px-3 py-1.5">
        <button
          type="button"
          aria-expanded={open}
          aria-controls={bodyId}
          onClick={() => setOpen((current) => !current)}
          className="flex min-w-0 flex-1 items-center gap-2 text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <ChevronRight
            className={cn(
              'h-3.5 w-3.5 shrink-0 text-muted-foreground transition-transform',
              open && 'rotate-90'
            )}
          />
          <span className="truncate text-xs font-semibold tracking-tight">{title}</span>
          {summary ? (
            <span className="ml-auto truncate pl-2 text-[11px] tabular-nums text-muted-foreground">
              {summary}
            </span>
          ) : null}
        </button>
        {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
      </div>

      {open ? (
        <div id={bodyId} className={cn('border-t border-border px-3 py-2', bodyClassName)}>
          {subtitle ? (
            <p className="mb-2 text-[11px] leading-snug text-muted-foreground">{subtitle}</p>
          ) : null}
          {children}
        </div>
      ) : null}
    </Card>
  )
}
