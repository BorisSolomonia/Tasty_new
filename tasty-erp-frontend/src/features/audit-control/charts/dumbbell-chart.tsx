import * as React from 'react'
import type { UnifiedCategoryCard } from '@/types/domain'
import { formatCurrency, formatNumber } from '@/lib/utils'
import { ChartFrame, DocSwatch, Legend, RealSwatch } from '../chart-frame'
import { dumbbellRows, type DumbbellMeasure } from '../derive'

const fmt = {
  gel: (v: number) => formatCurrency(v),
  kg: (v: number) => `${formatNumber(v, 0)} kg`,
}

const MEASURES: { key: DumbbellMeasure; label: string; unit: (v: number) => string }[] = [
  { key: 'purchase', label: 'Purchases ₾', unit: fmt.gel },
  { key: 'sales', label: 'Sales ₾', unit: fmt.gel },
  { key: 'kg', label: 'Purchases kg', unit: fmt.kg },
]

/**
 * Documented ↔ real per category on one axis (BOR-87 hybrid, from concept 1).
 * Hollow = documented (RS.ge), solid = what the operator recorded as real; the
 * gap is labelled. Two positions on a common scale is the most accurately read
 * encoding (Cleveland & McGill), which is why this replaces the interleaved
 * label/value rows for the cross-category comparison.
 */
export function DumbbellChart({ cards }: { cards: UnifiedCategoryCard[] }) {
  const [measure, setMeasure] = React.useState<DumbbellMeasure>('purchase')
  const m = MEASURES.find((x) => x.key === measure) ?? MEASURES[0]
  const rows = React.useMemo(() => dumbbellRows(cards, measure, fmt), [cards, measure])

  const W = 720
  const rowH = 36
  const padL = 96
  const padR = 110
  const top = 20
  const H = top + rows.length * rowH + 28
  const max = Math.max(1, ...rows.map((r) => Math.max(r.doc, r.real))) * 1.08
  const x = (v: number) => padL + (v / max) * (W - padL - padR)
  const ticks = 4

  return (
    <ChartFrame
      id="audit-dumbbell"
      title="Documented vs real, per category"
      description={
        <>
          Each row is one category on one axis. Hollow = what RS.ge documents; solid = what the operator recorded as
          real. The label is the gap (real − documented); hover a row for the detail.
        </>
      }
      aside={
        <div className="flex flex-col items-end gap-2">
          <Legend items={[{ swatch: <DocSwatch />, label: 'Documented' }, { swatch: <RealSwatch />, label: 'Real' }]} />
          <div role="tablist" aria-label="Measure" className="flex flex-wrap gap-1">
            {MEASURES.map((opt) => (
              <button
                key={opt.key}
                type="button"
                role="tab"
                aria-selected={opt.key === measure}
                onClick={() => setMeasure(opt.key)}
                className={`min-h-8 rounded-md border px-2 py-1 text-xs ${
                  opt.key === measure ? 'border-primary bg-primary/10 text-foreground' : 'border-border text-muted-foreground hover:bg-accent'
                }`}
              >
                {opt.label}
              </button>
            ))}
          </div>
        </div>
      }
      table={
        <table className="min-w-full text-sm">
          <thead className="text-xs uppercase text-muted-foreground">
            <tr>
              <th className="py-1 pr-2 text-left">Category</th>
              <th className="py-1 pr-2 text-right">Documented</th>
              <th className="py-1 pr-2 text-right">Real</th>
              <th className="py-1 pr-2 text-right">Real − doc</th>
              <th className="py-1 text-left">Detail</th>
            </tr>
          </thead>
          <tbody className="divide-y">
            {rows.map((r) => (
              <tr key={r.category}>
                <td className="py-1 pr-2">{r.label}</td>
                <td className="py-1 pr-2 text-right tabular-nums">{m.unit(r.doc)}</td>
                <td className="py-1 pr-2 text-right tabular-nums">{m.unit(r.real)}</td>
                <td className="py-1 pr-2 text-right tabular-nums">{m.unit(r.gap)}</td>
                <td className="py-1 text-muted-foreground">{r.detail}</td>
              </tr>
            ))}
          </tbody>
        </table>
      }
    >
      {rows.length === 0 ? (
        <p className="text-sm text-muted-foreground">No categories in range.</p>
      ) : (
        <svg viewBox={`0 0 ${W} ${H}`} className="block h-auto w-full" role="img" aria-label={`${m.label}: documented versus real per category`}>
          {Array.from({ length: ticks + 1 }, (_, i) => {
            const v = (max / ticks) * i
            return (
              <g key={i}>
                <line x1={x(v)} x2={x(v)} y1={top - 6} y2={H - 22} className="stroke-border" strokeWidth={1} />
                <text x={x(v)} y={H - 6} textAnchor="middle" className="fill-muted-foreground text-[10px]">
                  {m.unit(v)}
                </text>
              </g>
            )
          })}
          {rows.map((r, i) => {
            const y = top + i * rowH + rowH / 2
            const gapLabel = Math.abs(r.gap) < 0.5 ? 'no gap' : `${r.gap > 0 ? '+' : '−'}${m.unit(Math.abs(r.gap))}`
            return (
              <g key={r.category} tabIndex={0} className="outline-none focus-visible:opacity-80">
                <title>{`${r.label} · ${m.label}: documented ${m.unit(r.doc)} → real ${m.unit(r.real)} (${gapLabel}). ${r.detail}`}</title>
                <text x={padL - 10} y={y + 4} textAnchor="end" className="fill-foreground text-[11px] font-medium">
                  {r.label}
                </text>
                <line
                  x1={x(Math.min(r.doc, r.real))}
                  x2={x(Math.max(r.doc, r.real))}
                  y1={y}
                  y2={y}
                  className="stroke-primary"
                  strokeOpacity={0.45}
                  strokeWidth={4}
                  strokeLinecap="round"
                />
                <circle cx={x(r.doc)} cy={y} r={7} className="fill-background stroke-primary" strokeWidth={3} />
                <circle cx={x(r.real)} cy={y} r={7} className="fill-primary stroke-background" strokeWidth={2} />
                <text
                  x={W - padR + 8}
                  y={y + 4}
                  className={`text-[11px] ${Math.abs(r.gap) < 0.5 ? 'fill-muted-foreground' : r.gap > 0 ? 'fill-destructive font-semibold' : 'fill-foreground font-semibold'}`}
                >
                  {gapLabel}
                </text>
                <rect x={padL} y={y - rowH / 2} width={W - padL - padR} height={rowH} fill="transparent" />
              </g>
            )
          })}
        </svg>
      )}
    </ChartFrame>
  )
}
