import * as React from 'react'
import type { InventoryLedger } from '@/types/domain'
import { formatDate, formatNumber } from '@/lib/utils'
import { ChartFrame } from '../chart-frame'
import { heatStep, heatmapModel } from '../derive'

/** One hue, five ordinal steps (light → dark). Overage is a status ring, never a colour step. */
const STEP_OPACITY = [0.06, 0.25, 0.45, 0.68, 0.92]

/**
 * Category × day pressure heatmap (BOR-87 hybrid, from concept 3): the shade is
 * documented sales as a share of what could have been on hand that day; a red
 * ring is an overage day. This surfaces the only hard alarm the data can raise
 * — previously a table two clicks deep — for every category at once.
 */
export function LedgerHeatmap({ ledgers }: { ledgers: InventoryLedger[] }) {
  const model = React.useMemo(() => heatmapModel(ledgers), [ledgers])
  const n = model.dates.length
  const W = 760
  const left = 96
  const cw = n > 0 ? (W - left - 8) / n : 0
  const ch = 24
  const top = 20
  const H = top + model.rows.length * ch + 8

  if (!ledgers.length || n === 0) return null

  const tickEvery = n > 40 ? 7 : n > 20 ? 5 : n > 10 ? 2 : 1

  return (
    <ChartFrame
      id="audit-heatmap"
      title="Where the ledger is under pressure"
      description={
        <>
          Category × day. Darker = documented sales are a larger share of what could have been on hand that day. A
          red ring is an <b>overage day</b> — more was sold than the ledger says could have been there. Hover a cell
          for that day's row.
        </>
      }
      aside={
        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          <span>0 %</span>
          <span className="inline-flex gap-0.5" aria-hidden="true">
            {STEP_OPACITY.map((o) => (
              <span key={o} className="inline-block h-3 w-3.5 rounded-sm bg-primary" style={{ opacity: Math.max(o, 0.12) }} />
            ))}
          </span>
          <span>100 %</span>
          <span className="ml-2 inline-flex items-center gap-1">
            <span aria-hidden="true" className="inline-block h-3 w-3 rounded-full border-2 border-destructive" />
            overage
          </span>
        </div>
      }
      footnote={
        <>
          Opening stock is not recorded in the system, so "on hand" is the period's cumulative net movement; early-period
          cells are biased dark. Stated here rather than hidden.
        </>
      }
      table={
        <table className="min-w-full text-sm">
          <thead className="text-xs uppercase text-muted-foreground">
            <tr>
              <th className="py-1 pr-2 text-left">Category</th>
              <th className="py-1 pr-2 text-right">Overage days</th>
              <th className="py-1 pr-2 text-right">Days sold ≥ 90 % of available</th>
            </tr>
          </thead>
          <tbody className="divide-y">
            {model.rows.map((r) => (
              <tr key={r.category}>
                <td className="py-1 pr-2">{r.label}</td>
                <td className="py-1 pr-2 text-right tabular-nums">{r.overageDays}</td>
                <td className="py-1 pr-2 text-right tabular-nums">{r.cells.filter((c) => c.pressure >= 0.9).length}</td>
              </tr>
            ))}
          </tbody>
        </table>
      }
    >
      <div className="overflow-x-auto">
        <svg viewBox={`0 0 ${W} ${H}`} className="block h-auto w-full min-w-[520px]" role="img" aria-label="Inventory pressure by category and day">
          {model.dates.map((d, i) =>
            i % tickEvery === 0 ? (
              <text key={d} x={left + i * cw + cw / 2} y={13} textAnchor="middle" className="fill-muted-foreground text-[10px]">
                {formatDate(d, 'dd.MM')}
              </text>
            ) : null
          )}
          {model.rows.map((row, ri) => {
            const y = top + ri * ch
            return (
              <g key={row.category}>
                <text x={left - 8} y={y + ch / 2 + 4} textAnchor="end" className="fill-foreground text-[11px] font-medium">
                  {row.label}
                </text>
                {row.cells.map((cell, ci) => {
                  const x = left + ci * cw
                  const step = heatStep(cell.pressure)
                  return (
                    <g key={cell.date} tabIndex={0} className="outline-none">
                      <title>
                        {`${row.label} · ${formatDate(cell.date)}: sold ${formatNumber(cell.sold, 0)} kg of ${formatNumber(
                          Math.max(cell.start + cell.purchased, 0),
                          0
                        )} kg available (${formatNumber(Math.min(cell.pressure, 9) * 100, 0)} %)${cell.overage ? ' — OVERAGE' : ''}`}
                      </title>
                      <rect x={x + 1} y={y + 1} width={Math.max(cw - 2, 1)} height={ch - 2} rx={2} className="fill-primary" fillOpacity={STEP_OPACITY[step]} />
                      {cell.overage ? (
                        <rect x={x + 1.5} y={y + 1.5} width={Math.max(cw - 3, 1)} height={ch - 3} rx={2} fill="none" className="stroke-destructive" strokeWidth={2} />
                      ) : null}
                    </g>
                  )
                })}
              </g>
            )
          })}
        </svg>
      </div>
    </ChartFrame>
  )
}
