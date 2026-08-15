import * as React from 'react'
import type { UnifiedCategoryCard } from '@/types/domain'
import { formatNumber } from '@/lib/utils'
import { waterfallSteps } from '../derive'

/**
 * Inventory identity for one category (BOR-87 hybrid, from concept 2):
 * opening (not recorded) → +purchases → −write-off → −sales = net movement.
 * A bridge makes the additive chain visible where the page had a formula
 * sentence; the dashed opening step keeps it honest.
 */
export function InventoryWaterfall({ card }: { card: UnifiedCategoryCard }) {
  const steps = React.useMemo(() => waterfallSteps(card), [card])
  const W = 520
  const H = 220
  const padL = 52
  const padR = 12
  const top = 18
  const bottom = 40
  const lo = Math.min(0, ...steps.map((s) => Math.min(s.from, s.to)))
  const hi = Math.max(1, ...steps.map((s) => Math.max(s.from, s.to))) * 1.08
  const y = (v: number) => top + ((hi - v) / (hi - lo || 1)) * (H - top - bottom)
  const bw = (W - padL - padR) / steps.length

  return (
    <figure className="m-0">
      <figcaption className="mb-1 text-xs text-muted-foreground">
        Where the kilograms went: purchases − write-off − sales = net movement. Opening stock is not recorded, so the
        chain starts at 0.
      </figcaption>
      <svg viewBox={`0 0 ${W} ${H}`} className="block h-auto w-full" role="img" aria-label="Inventory waterfall for this category">
        {Array.from({ length: 5 }, (_, i) => {
          const v = lo + ((hi - lo) / 4) * i
          return (
            <g key={i}>
              <line x1={padL} x2={W - padR} y1={y(v)} y2={y(v)} className="stroke-border" />
              <text x={padL - 6} y={y(v) + 4} textAnchor="end" className="fill-muted-foreground text-[10px]">
                {formatNumber(v, 0)}
              </text>
            </g>
          )
        })}
        <line x1={padL} x2={W - padR} y1={y(0)} y2={y(0)} className="stroke-muted-foreground" />
        {steps.map((s, i) => {
          const x = padL + bw * i + bw * 0.18
          const w = bw * 0.64
          const hiV = Math.max(s.from, s.to)
          const loV = Math.min(s.from, s.to)
          const h = Math.max(2, y(loV) - y(hiV))
          const fill =
            s.kind === 'up' ? 'fill-primary' : s.kind === 'down' ? 'fill-primary' : s.kind === 'total' ? (s.to < 0 ? 'fill-destructive' : 'fill-foreground') : 'fill-none'
          const opacity = s.kind === 'up' ? 0.45 : 1
          return (
            <g key={s.label} tabIndex={0} className="outline-none">
              <title>
                {s.kind === 'unknown'
                  ? 'Opening stock is not recorded in the system; the chain starts at 0. This is why "On hand" is really net movement.'
                  : `${s.label}: ${formatNumber(Math.abs(s.value), 0)} kg`}
              </title>
              {s.kind === 'unknown' ? (
                <rect x={x} y={y(0) - 22} width={w} height={22} rx={3} fill="none" className="stroke-muted-foreground" strokeDasharray="4 3" />
              ) : (
                <rect x={x} y={y(hiV)} width={w} height={h} rx={3} className={fill} fillOpacity={opacity} />
              )}
              <text x={x + w / 2} y={H - bottom + 16} textAnchor="middle" className="fill-muted-foreground text-[10px]">
                {s.label}
              </text>
              <text x={x + w / 2} y={(s.kind === 'unknown' ? y(0) - 22 : y(hiV)) - 5} textAnchor="middle" className="fill-foreground text-[11px] font-semibold">
                {s.kind === 'unknown' ? 'not recorded' : `${s.value > 0 && s.kind !== 'total' ? '+' : ''}${formatNumber(s.value, 0)}`}
              </text>
              {i < steps.length - 1 && s.kind !== 'total' ? (
                <line
                  x1={x + w}
                  x2={padL + bw * (i + 1) + bw * 0.18}
                  y1={y(s.kind === 'unknown' ? 0 : s.to)}
                  y2={y(s.kind === 'unknown' ? 0 : s.to)}
                  className="stroke-muted-foreground"
                  strokeDasharray="2 3"
                />
              ) : null}
            </g>
          )
        })}
      </svg>
    </figure>
  )
}
