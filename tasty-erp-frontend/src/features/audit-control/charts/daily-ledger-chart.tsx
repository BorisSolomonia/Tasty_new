import type { InventoryLedger } from '@/types/domain'
import { formatDate, formatNumber } from '@/lib/utils'

/**
 * Daily ledger for one category as a step-area (BOR-87 hybrid, from concept 2):
 * cumulative documented ending kg per day, overage days shaded, hover for the
 * row. The full daily table stays available underneath it on the page.
 */
export function DailyLedgerChart({ ledger }: { ledger: InventoryLedger }) {
  const rows = ledger.dailyRows
  if (!rows.length) return null
  const W = 900
  const H = 200
  const padL = 56
  const padR = 12
  const top = 12
  const bottom = 30
  const n = rows.length
  const vals = rows.map((r) => r.endingInventoryKg)
  const hi = Math.max(1, ...vals, 0) * 1.1
  const lo = Math.min(0, ...vals) * 1.2
  const x = (i: number) => padL + (i / Math.max(n - 1, 1)) * (W - padL - padR)
  const y = (v: number) => top + ((hi - v) / (hi - lo || 1)) * (H - top - bottom)
  const dw = (W - padL - padR) / Math.max(n - 1, 1)
  const tickEvery = n > 40 ? 7 : n > 20 ? 5 : n > 10 ? 2 : 1

  let path = `M ${x(0)} ${y(0)}`
  rows.forEach((r, i) => {
    path += ` L ${x(i)} ${y(r.startingInventoryKg)} L ${x(i)} ${y(r.endingInventoryKg)}`
  })
  const area = `${path} L ${x(n - 1)} ${y(0)} Z`

  return (
    <figure className="m-0">
      <figcaption className="mb-1 text-xs text-muted-foreground">
        Daily ledger — cumulative documented net kg. Shaded days are overage days (sold more than could have been on
        hand). Hover a day for the row; the table below has every value.
      </figcaption>
      <div className="overflow-x-auto">
        <svg viewBox={`0 0 ${W} ${H}`} className="block h-auto w-full min-w-[520px]" role="img" aria-label="Daily ledger for this category">
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
          {rows.map((r, i) =>
            r.overage ? <rect key={`o${r.date}`} x={x(i) - dw / 2} y={top} width={dw} height={H - top - bottom} className="fill-destructive" fillOpacity={0.12} /> : null
          )}
          <line x1={padL} x2={W - padR} y1={y(0)} y2={y(0)} className="stroke-muted-foreground" />
          <path d={area} className="fill-primary" fillOpacity={0.12} />
          <path d={path} fill="none" className="stroke-primary" strokeWidth={2} />
          {rows.map((r, i) => (
            <g key={r.date} tabIndex={0} className="outline-none">
              <title>
                {`${formatDate(r.date)}: start ${formatNumber(r.startingInventoryKg, 0)} + purchased ${formatNumber(r.purchasedKg, 0)} − sold ${formatNumber(
                  r.soldKg,
                  0
                )} − write-off ${formatNumber(r.writeOffKg, 0)} = ${formatNumber(r.endingInventoryKg, 0)} kg${r.overage ? ' — OVERAGE' : ''}`}
              </title>
              {i % tickEvery === 0 ? (
                <text x={x(i)} y={H - bottom + 14} textAnchor="middle" className="fill-muted-foreground text-[10px]">
                  {formatDate(r.date, 'dd.MM')}
                </text>
              ) : null}
              <rect x={x(i) - dw / 2} y={top} width={dw} height={H - top - bottom} fill="transparent" />
              {r.overage ? <circle cx={x(i)} cy={y(r.endingInventoryKg)} r={4.5} className="fill-destructive stroke-background" strokeWidth={2} /> : null}
            </g>
          ))}
        </svg>
      </div>
    </figure>
  )
}
