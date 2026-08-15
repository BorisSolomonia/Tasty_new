import * as React from 'react'
import type { DualLedger } from '@/types/domain'
import { formatCurrency } from '@/lib/utils'
import { ChartFrame } from '../chart-frame'
import { cashGapRows } from '../derive'

/**
 * Diverging cash-gap bars (BOR-87 hybrid, from concept 3). The backend has
 * computed `purchaseShortages` / `saleSurpluses` per category since BOR-76; the
 * page dropped them in the BOR-79 consolidation. Zero is in the middle: a
 * purchase shortage (documented − real) extends left, a sales surplus
 * (real − documented) extends right, on one axis.
 */
export function CashGapBars({ dual }: { dual: DualLedger }) {
  const rows = React.useMemo(() => cashGapRows(dual), [dual])
  if (!rows.length) return null
  const W = 720
  const rowH = 30
  const padL = 96
  const top = 22
  const H = top + rows.length * rowH + 30
  const max = Math.max(1, ...rows.map((r) => Math.max(Math.abs(r.purchaseShortage), Math.abs(r.saleSurplus)))) * 1.1
  const half = (W - padL - 12) / 2
  const mid = padL + half

  return (
    <ChartFrame
      id="audit-cash-gap"
      title="Cash gap per category"
      description={
        <>
          Purchase shortage = documented − real purchase total (what the paper says was paid above what really was);
          sales surplus = real − documented sales total. Zero is the centre line; both are in ₾ on one axis.
        </>
      }
      footnote={
        <>
          Totals: purchase shortage {formatCurrency(dual.totalPurchaseShortage ?? 0)}, sales surplus{' '}
          {formatCurrency(dual.totalSaleSurplus ?? 0)}.
        </>
      }
      table={
        <table className="min-w-full text-sm">
          <thead className="text-xs uppercase text-muted-foreground">
            <tr>
              <th className="py-1 pr-2 text-left">Category</th>
              <th className="py-1 pr-2 text-right">Purchase shortage</th>
              <th className="py-1 pr-2 text-right">Sales surplus</th>
            </tr>
          </thead>
          <tbody className="divide-y">
            {rows.map((r) => (
              <tr key={r.category}>
                <td className="py-1 pr-2">{r.label}</td>
                <td className="py-1 pr-2 text-right tabular-nums">{formatCurrency(r.purchaseShortage)}</td>
                <td className="py-1 pr-2 text-right tabular-nums">{formatCurrency(r.saleSurplus)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      }
    >
      <svg viewBox={`0 0 ${W} ${H}`} className="block h-auto w-full" role="img" aria-label="Purchase shortage and sales surplus per category">
        <text x={padL + 4} y={12} className="fill-muted-foreground text-[10px]">
          ← purchase shortage (doc − real)
        </text>
        <text x={W - 12} y={12} textAnchor="end" className="fill-muted-foreground text-[10px]">
          sales surplus (real − doc) →
        </text>
        <line x1={mid} x2={mid} y1={top - 4} y2={H - 24} className="stroke-muted-foreground" />
        {rows.map((r, i) => {
          const y = top + i * rowH + 5
          const pw = (Math.abs(r.purchaseShortage) / max) * half
          const sw = (Math.abs(r.saleSurplus) / max) * half
          return (
            <g key={r.category}>
              <text x={padL - 8} y={y + 12} textAnchor="end" className="fill-foreground text-[11px] font-medium">
                {r.label}
              </text>
              {r.purchaseShortage !== 0 ? (
                <g tabIndex={0} className="outline-none">
                  <title>{`${r.label}: purchase ${r.purchaseShortage > 0 ? 'shortage' : 'excess'} ${formatCurrency(Math.abs(r.purchaseShortage))}`}</title>
                  <rect
                    x={r.purchaseShortage > 0 ? mid - pw : mid}
                    y={y}
                    width={Math.max(pw, 1)}
                    height={9}
                    rx={2}
                    className={r.purchaseShortage > 0 ? 'fill-primary' : 'fill-destructive'}
                  />
                </g>
              ) : null}
              {r.saleSurplus !== 0 ? (
                <g tabIndex={0} className="outline-none">
                  <title>{`${r.label}: sales ${r.saleSurplus > 0 ? 'surplus' : 'shortfall'} ${formatCurrency(Math.abs(r.saleSurplus))}`}</title>
                  <rect
                    x={r.saleSurplus > 0 ? mid : mid - sw}
                    y={y + 11}
                    width={Math.max(sw, 1)}
                    height={9}
                    rx={2}
                    className={r.saleSurplus > 0 ? 'fill-primary' : 'fill-warning'}
                    fillOpacity={r.saleSurplus > 0 ? 0.45 : 1}
                  />
                </g>
              ) : null}
            </g>
          )
        })}
        <text x={padL} y={H - 6} className="fill-muted-foreground text-[10px]">
          Top bar: purchases (solid = doc above real, red = real above doc). Bottom bar: sales (light = real above doc).
        </text>
      </svg>
    </ChartFrame>
  )
}
