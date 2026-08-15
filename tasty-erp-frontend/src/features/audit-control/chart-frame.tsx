import * as React from 'react'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'

/**
 * Common shell for an audit-control chart: title, one-line definition, an
 * optional legend/control slot, the plot, and a collapsible table view so the
 * numbers behind every mark are always reachable without the picture.
 */
export function ChartFrame({
  id,
  title,
  description,
  aside,
  children,
  table,
  footnote,
}: {
  id?: string
  title: string
  description?: React.ReactNode
  aside?: React.ReactNode
  children: React.ReactNode
  table?: React.ReactNode
  footnote?: React.ReactNode
}) {
  return (
    <Card id={id} className="scroll-mt-20">
      <CardHeader className="flex flex-row flex-wrap items-start justify-between gap-2 space-y-0 p-4 pb-2">
        <div className="min-w-0">
          <CardTitle className="text-base">{title}</CardTitle>
          {description ? <CardDescription className="max-w-[70ch]">{description}</CardDescription> : null}
        </div>
        {aside}
      </CardHeader>
      <CardContent className="p-4 pt-2">
        {children}
        {footnote ? <p className="mt-2 text-xs text-muted-foreground">{footnote}</p> : null}
        {table ? (
          <details className="mt-2">
            <summary className="cursor-pointer text-xs text-muted-foreground">Table view</summary>
            <div className="mt-2 overflow-x-auto">{table}</div>
          </details>
        ) : null}
      </CardContent>
    </Card>
  )
}

/** Small legend swatch row. */
export function Legend({ items }: { items: { swatch: React.ReactNode; label: string }[] }) {
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-muted-foreground">
      {items.map((it) => (
        <span key={it.label} className="inline-flex items-center gap-1.5">
          {it.swatch}
          {it.label}
        </span>
      ))}
    </div>
  )
}

export const DocSwatch = () => (
  <span aria-hidden="true" className="inline-block h-3 w-3 rounded-full border-2 border-primary bg-background" />
)
export const RealSwatch = () => <span aria-hidden="true" className="inline-block h-3 w-3 rounded-full bg-primary" />
export const CritSwatch = () => <span aria-hidden="true" className="inline-block h-3 w-3 rounded-sm bg-destructive" />
