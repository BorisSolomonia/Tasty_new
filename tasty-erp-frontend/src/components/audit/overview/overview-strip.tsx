/**
 * The top of the audit page (BOR-92): four flow tiles — Purchases · Bank
 * payments to suppliers · Cash outflow · Sales — each "total | chosen", each
 * clickable into its own panel, plus the supplier picker that defines "chosen".
 *
 * Every figure comes from one payload (`GET /layer/overview`) so a mapping saved
 * in the workbench moves these tiles on the next read. Definitions are stated on
 * the tiles themselves; the payload's `notes` are rendered, never hidden.
 */
import * as React from 'react'
import { ChevronDown, ChevronRight, ExternalLink } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { cn } from '@/lib/cn'
import type { AuditOverview, OverviewBucket, OverviewCounterparty, OverviewCategoryAmount } from '@/lib/audit-api'
import { apiErrorMessage } from '@/lib/api-client'
import { useAuditOverview } from '@/hooks/use-audit-flows'
import { useAudit } from '../audit-context'
import { fmtGel, fmtKg, fmtCount, EM_DASH } from '../format'
import { CATEGORY_LABELS } from '@/features/audit-control/labels'

export type OverviewTile = 'purchases' | 'bank' | 'outflow' | 'sales'

const TILE_META: Record<OverviewTile, { title: string; left: string; right: string; definition: string }> = {
  purchases: {
    title: 'Purchases',
    left: 'total',
    right: 'chosen',
    definition: '₾ of every RS.ge purchase document line in the period. "Chosen" = the same for the supplier picked above.',
  },
  bank: {
    title: 'Bank payments to suppliers',
    left: 'total',
    right: 'to chosen',
    definition: 'Real bank money out, on rows mapped to a supplier-settlement group, attributed to the counterparty of each slice.',
  },
  outflow: {
    title: 'Cash outflow',
    left: 'total',
    right: 'unmapped',
    definition: 'Every bank debit row in the period. "Unmapped" = the part no group covers yet. Open to see group → document status → counterparty.',
  },
  sales: {
    title: 'Sales',
    left: 'total',
    right: 'real',
    definition: '₾ of every RS.ge sale line. "Real" excludes sales to customers marked unreal and lines mapped as paper-only.',
  },
}

export function OverviewStrip() {
  const { filters } = useAudit()
  const [supplierTin, setSupplierTin] = React.useState<string>('')
  const [active, setActive] = React.useState<OverviewTile | null>(null)
  const query = useAuditOverview({ startDate: filters.startDate, endDate: filters.endDate, supplierTin: supplierTin || undefined })
  const o = query.data

  const tileValues: Record<OverviewTile, [number | null | undefined, number | null | undefined]> = {
    purchases: [o?.purchases.total, o?.purchases.chosen],
    bank: [o?.bankPaymentsToSuppliers.total, o?.bankPaymentsToSuppliers.toChosen],
    outflow: [o?.cashOutflow.total, o?.cashOutflow.unmapped],
    sales: [o?.sales.total, o?.sales.real],
  }

  return (
    <section aria-labelledby="overview-h" className="space-y-3">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h2 id="overview-h" className="text-sm font-semibold">
            Overview <span className="font-normal text-muted-foreground">— {filters.startDate} → {filters.endDate}</span>
          </h2>
          <p className="text-xs text-muted-foreground">Click a tile to open it. "Chosen" figures follow the supplier picked here.</p>
        </div>
        <label className="flex items-center gap-2 text-sm">
          <span className="text-muted-foreground">Chosen supplier</span>
          <select
            className="h-9 min-w-[16rem] rounded-md border border-input bg-background px-2 text-sm"
            value={supplierTin}
            onChange={(e) => setSupplierTin(e.target.value)}
            aria-label="Chosen supplier"
          >
            <option value="">— none (totals only) —</option>
            {(o?.suppliers ?? []).map((s) => (
              <option key={s.tin ?? s.name ?? ''} value={s.tin ?? ''}>
                {(s.name ?? s.tin ?? '?') + (s.tin && s.name ? ` · ${s.tin}` : '')} · {fmtGel(s.purchases)}
              </option>
            ))}
          </select>
        </label>
      </div>

      {query.isError ? (
        <Card className="p-3 text-sm text-destructive">Overview did not load: {apiErrorMessage(query.error)}</Card>
      ) : null}

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4" role="tablist" aria-label="Overview flows">
        {(Object.keys(TILE_META) as OverviewTile[]).map((key) => {
          const meta = TILE_META[key]
          const [left, right] = tileValues[key]
          const isActive = active === key
          const rightMissing = key !== 'outflow' && key !== 'sales' && !supplierTin
          return (
            <button
              key={key}
              type="button"
              role="tab"
              aria-selected={isActive}
              aria-controls={`overview-panel-${key}`}
              onClick={() => setActive(isActive ? null : key)}
              className={cn(
                'rounded-lg border bg-card p-3 text-left transition-colors hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                isActive ? 'border-primary ring-1 ring-primary' : 'border-border'
              )}
            >
              <div className="flex items-center justify-between">
                <span className="text-sm font-semibold">{meta.title}</span>
                {isActive ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4 text-muted-foreground" />}
              </div>
              <div className="mt-2 grid grid-cols-2 gap-2">
                <div>
                  <div className="text-[11px] uppercase tracking-wide text-muted-foreground">{meta.left}</div>
                  <div className="text-lg font-semibold tabular-nums">{query.isLoading ? '…' : fmtGel(left)}</div>
                </div>
                <div>
                  <div className="text-[11px] uppercase tracking-wide text-muted-foreground">{meta.right}</div>
                  <div className={cn('text-lg font-semibold tabular-nums', key === 'outflow' && (right ?? 0) > 0 && 'text-destructive')}>
                    {query.isLoading ? '…' : rightMissing ? EM_DASH : fmtGel(right)}
                  </div>
                  {rightMissing && !query.isLoading ? <div className="text-[10px] text-muted-foreground">no supplier chosen</div> : null}
                </div>
              </div>
              <p className="mt-2 text-[11px] leading-snug text-muted-foreground">{meta.definition}</p>
            </button>
          )
        })}
      </div>

      {o && active ? (
        <Card id={`overview-panel-${active}`} role="tabpanel" className="p-4">
          {active === 'purchases' ? <PurchasesPanel o={o} /> : null}
          {active === 'bank' ? <BankPanel o={o} /> : null}
          {active === 'outflow' ? <OutflowPanel o={o} /> : null}
          {active === 'sales' ? <SalesPanel o={o} /> : null}
        </Card>
      ) : null}

      {o?.notes?.length ? (
        <ul className="list-disc space-y-0.5 pl-5 text-[11px] text-muted-foreground">
          {o.notes.map((n) => (
            <li key={n}>{n}</li>
          ))}
        </ul>
      ) : null}
    </section>
  )
}

// ---------------------------------------------------------------------------
// Panels
// ---------------------------------------------------------------------------

function catLabel(code: string) {
  return CATEGORY_LABELS[code] ?? code
}

function CategoryTable({ rows, showChosen, kgLabel }: { rows: OverviewCategoryAmount[]; showChosen: boolean; kgLabel: string }) {
  return (
    <div className="overflow-x-auto">
      <table className="min-w-full text-sm">
        <thead className="text-xs uppercase text-muted-foreground">
          <tr>
            <th className="py-1 pr-2 text-left">Product group</th>
            <th className="py-1 pr-2 text-right">₾ total</th>
            <th className="py-1 pr-2 text-right">{kgLabel}</th>
            {showChosen ? <th className="py-1 pr-2 text-right">₾ chosen</th> : null}
            {showChosen ? <th className="py-1 pr-2 text-right">kg chosen</th> : null}
            <th className="py-1 text-right">Lines</th>
          </tr>
        </thead>
        <tbody className="divide-y">
          {rows.map((r) => (
            <tr key={r.category}>
              <td className="py-1 pr-2">{catLabel(r.category)}</td>
              <td className="py-1 pr-2 text-right tabular-nums">{fmtGel(r.amount)}</td>
              <td className="py-1 pr-2 text-right tabular-nums">{fmtKg(r.quantityKg, 0)}</td>
              {showChosen ? <td className="py-1 pr-2 text-right tabular-nums">{fmtGel(r.chosenAmount)}</td> : null}
              {showChosen ? <td className="py-1 pr-2 text-right tabular-nums">{fmtKg(r.chosenQuantityKg, 0)}</td> : null}
              <td className="py-1 text-right tabular-nums">{fmtCount(r.rowCount)}</td>
            </tr>
          ))}
          {!rows.length ? (
            <tr>
              <td colSpan={6} className="py-2 text-muted-foreground">
                No lines in this period.
              </td>
            </tr>
          ) : null}
        </tbody>
      </table>
    </div>
  )
}

function CounterpartyTable({
  rows,
  columns,
  emptyText,
}: {
  rows: OverviewCounterparty[]
  columns: { key: keyof OverviewCounterparty; label: string; kind: 'gel' | 'kg' | 'count' }[]
  emptyText: string
}) {
  const [showAll, setShowAll] = React.useState(false)
  const visible = showAll ? rows : rows.slice(0, 25)
  return (
    <div className="overflow-x-auto">
      <table className="min-w-full text-sm">
        <thead className="text-xs uppercase text-muted-foreground">
          <tr>
            <th className="py-1 pr-2 text-left">Counterparty</th>
            {columns.map((c) => (
              <th key={String(c.key)} className="py-1 pr-2 text-right">
                {c.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y">
          {visible.map((r) => (
            <tr key={(r.tin ?? '') + (r.name ?? '')}>
              <td className="py-1 pr-2">
                <div className="font-medium">{r.name ?? r.tin ?? EM_DASH}</div>
                {r.tin && r.name ? <div className="text-[11px] text-muted-foreground">{r.tin}</div> : null}
              </td>
              {columns.map((c) => {
                const v = r[c.key] as number | null
                return (
                  <td key={String(c.key)} className="py-1 pr-2 text-right tabular-nums">
                    {c.kind === 'gel' ? fmtGel(v) : c.kind === 'kg' ? fmtKg(v, 0) : fmtCount(v)}
                  </td>
                )
              })}
            </tr>
          ))}
          {!rows.length ? (
            <tr>
              <td colSpan={columns.length + 1} className="py-2 text-muted-foreground">
                {emptyText}
              </td>
            </tr>
          ) : null}
        </tbody>
      </table>
      {rows.length > 25 ? (
        <div className="mt-1 text-xs text-muted-foreground">
          Showing {visible.length} of {rows.length}.{' '}
          <Button size="sm" variant="ghost" className="h-7 px-2" onClick={() => setShowAll((v) => !v)}>
            {showAll ? 'Show first 25' : 'Show all'}
          </Button>
        </div>
      ) : null}
    </div>
  )
}

function PurchasesPanel({ o }: { o: AuditOverview }) {
  const [openStock, setOpenStock] = React.useState<string | null>(null)
  const chosen = Boolean(o.supplierTin)
  return (
    <div className="space-y-5">
      <div>
        <h3 className="text-sm font-semibold">Purchases by product group</h3>
        <p className="mb-2 text-xs text-muted-foreground">
          {fmtGel(o.purchases.total)} · {fmtKg(o.purchases.totalKg, 0)} in {o.purchases.byCategory.reduce((s, r) => s + r.rowCount, 0)} document lines
          {chosen ? ` — chosen supplier ${o.supplierName ?? o.supplierTin}: ${fmtGel(o.purchases.chosen)} · ${fmtKg(o.purchases.chosenKg, 0)}` : ''}.
          Product groups come from the shared product-category store (same as /audit-control).
        </p>
        <CategoryTable rows={o.purchases.byCategory} showChosen={chosen} kgLabel="kg (kg lines only)" />
      </div>

      <div>
        <h3 className="text-sm font-semibold">Suppliers</h3>
        <p className="mb-2 text-xs text-muted-foreground">Every seller on a purchase document this period, with what the bank paid them and what paper (unreal-sale chains) points at them.</p>
        <CounterpartyTable
          rows={o.purchases.bySupplier}
          columns={[
            { key: 'purchases', label: '₾ purchases', kind: 'gel' },
            { key: 'quantityKg', label: 'kg', kind: 'kg' },
            { key: 'bankPayments', label: '₾ bank paid', kind: 'gel' },
            { key: 'paperOutflow', label: '₾ paper', kind: 'gel' },
            { key: 'rowCount', label: 'lines', kind: 'count' },
          ]}
          emptyText="No purchase documents in this period."
        />
      </div>

      <div>
        <h3 className="text-sm font-semibold">Net stock on paper, by product group</h3>
        <p className="mb-2 text-xs text-muted-foreground">
          purchased − write-off − sold for the period (opening stock is not recorded, so this is a net movement, not a stock level).
          Click a row to see which suppliers the remaining kg came from — latest purchases first (LIFO).
        </p>
        <div className="overflow-x-auto">
          <table className="min-w-full text-sm">
            <thead className="text-xs uppercase text-muted-foreground">
              <tr>
                <th className="py-1 pr-2 text-left">Product group</th>
                <th className="py-1 pr-2 text-right">Purchased kg</th>
                <th className="py-1 pr-2 text-right">Write-off</th>
                <th className="py-1 pr-2 text-right">Sold kg</th>
                <th className="py-1 pr-2 text-right">Net kg</th>
                <th className="py-1 text-left">Suppliers</th>
              </tr>
            </thead>
            <tbody className="divide-y">
              {o.inventory.byCategory.map((c) => {
                const open = openStock === c.category
                return (
                  <React.Fragment key={c.category}>
                    <tr>
                      <td className="py-1 pr-2">
                        <button
                          type="button"
                          className="inline-flex min-h-8 items-center gap-1 rounded px-1 text-left hover:bg-accent"
                          aria-expanded={open}
                          onClick={() => setOpenStock(open ? null : c.category)}
                        >
                          {open ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" />}
                          {catLabel(c.category)}
                        </button>
                      </td>
                      <td className="py-1 pr-2 text-right tabular-nums">{fmtKg(c.purchasedKg, 0)}</td>
                      <td className="py-1 pr-2 text-right tabular-nums">
                        {fmtKg(c.writeOffKg, 0)} <span className="text-muted-foreground">({c.writeOffPercent ?? 0}%)</span>
                      </td>
                      <td className="py-1 pr-2 text-right tabular-nums">{fmtKg(c.soldKg, 0)}</td>
                      <td className={cn('py-1 pr-2 text-right font-medium tabular-nums', (c.netKg ?? 0) < 0 && 'text-destructive')}>{fmtKg(c.netKg, 0)}</td>
                      <td className="py-1 text-muted-foreground">
                        {c.stockBySupplier.length ? `${c.stockBySupplier.length} supplier${c.stockBySupplier.length === 1 ? '' : 's'}` : (c.netKg ?? 0) <= 0 ? 'nothing left on paper' : EM_DASH}
                      </td>
                    </tr>
                    {open ? (
                      <tr className="bg-muted/30">
                        <td colSpan={6} className="px-3 py-2">
                          {c.stockBySupplier.length ? (
                            <ul className="grid gap-1 sm:grid-cols-2 lg:grid-cols-3">
                              {c.stockBySupplier.map((s) => (
                                <li key={s.tin ?? s.name ?? ''} className="flex items-baseline justify-between gap-2 rounded border border-border bg-card px-2 py-1 text-xs">
                                  <span>
                                    <span className="font-medium">{s.name ?? s.tin}</span>
                                    {s.lastPurchaseDate ? <span className="ml-1 text-muted-foreground">last {s.lastPurchaseDate}</span> : null}
                                  </span>
                                  <span className="tabular-nums">{fmtKg(s.quantityKg, 0)}</span>
                                </li>
                              ))}
                            </ul>
                          ) : (
                            <span className="text-xs text-muted-foreground">Net movement is not positive — nothing to attribute.</span>
                          )}
                        </td>
                      </tr>
                    ) : null}
                  </React.Fragment>
                )
              })}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}

function BankPanel({ o }: { o: AuditOverview }) {
  return (
    <div className="space-y-2">
      <h3 className="text-sm font-semibold">Bank payments to suppliers, by counterparty</h3>
      <p className="text-xs text-muted-foreground">
        {fmtGel(o.bankPaymentsToSuppliers.total)} of real bank money on rows mapped to a supplier-settlement group
        {o.supplierTin ? ` — ${fmtGel(o.bankPaymentsToSuppliers.toChosen)} to ${o.supplierName ?? o.supplierTin}` : ''}. Rows nobody has mapped are not here — they are in "Cash outflow → unmapped".
      </p>
      <CounterpartyTable
        rows={o.bankPaymentsToSuppliers.bySupplier}
        columns={[
          { key: 'bankPayments', label: '₾ bank paid', kind: 'gel' },
          { key: 'purchases', label: '₾ documented purchases', kind: 'gel' },
          { key: 'rowCount', label: 'slices', kind: 'count' },
        ]}
        emptyText="No bank row is mapped to a supplier-settlement group in this period."
      />
    </div>
  )
}

function BucketTree({ buckets, depthLabels }: { buckets: OverviewBucket[]; depthLabels: [string, string, string] }) {
  const [open, setOpen] = React.useState<Record<string, boolean>>({})
  const toggle = (k: string) => setOpen((s) => ({ ...s, [k]: !s[k] }))
  if (!buckets.length) return <p className="text-xs text-muted-foreground">Nothing here for this period.</p>
  return (
    <ul className="space-y-1">
      {buckets.map((g) => {
        const gk = `g:${g.code}`
        return (
          <li key={gk} className="rounded-md border border-border">
            <button type="button" aria-expanded={Boolean(open[gk])} onClick={() => toggle(gk)} className="flex min-h-9 w-full items-center justify-between gap-2 px-2 py-1.5 text-left text-sm hover:bg-accent">
              <span className="inline-flex items-center gap-1">
                {open[gk] ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" />}
                <span className="text-[10px] uppercase tracking-wide text-muted-foreground">{depthLabels[0]}</span>
                <span className="font-medium">{g.label}</span>
              </span>
              <span className="tabular-nums">
                {fmtGel(g.amount)} <span className="text-xs text-muted-foreground">· {fmtCount(g.rowCount)}</span>
              </span>
            </button>
            {open[gk] && g.children ? (
              <ul className="space-y-1 border-t border-border p-2 pl-6">
                {g.children.map((s) => {
                  const sk = `${gk}/${s.code}`
                  return (
                    <li key={sk} className="rounded border border-border/70">
                      <button type="button" aria-expanded={Boolean(open[sk])} onClick={() => toggle(sk)} className="flex min-h-8 w-full items-center justify-between gap-2 px-2 py-1 text-left text-sm hover:bg-accent">
                        <span className="inline-flex items-center gap-1">
                          {open[sk] ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" />}
                          <span className="text-[10px] uppercase tracking-wide text-muted-foreground">{depthLabels[1]}</span>
                          <span>{s.label}</span>
                        </span>
                        <span className="tabular-nums">
                          {fmtGel(s.amount)} <span className="text-xs text-muted-foreground">· {fmtCount(s.rowCount)}</span>
                        </span>
                      </button>
                      {open[sk] && s.children ? (
                        <ul className="divide-y border-t border-border/70 pl-6 text-sm">
                          {s.children.map((c) => (
                            <li key={`${sk}/${c.code}`} className="flex items-center justify-between gap-2 px-2 py-1">
                              <span>
                                <span className="text-[10px] uppercase tracking-wide text-muted-foreground">{depthLabels[2]} </span>
                                {c.label}
                                {c.tin && c.tin !== c.label ? <span className="ml-1 text-xs text-muted-foreground">{c.tin}</span> : null}
                              </span>
                              <span className="tabular-nums">
                                {fmtGel(c.amount)} <span className="text-xs text-muted-foreground">· {fmtCount(c.rowCount)}</span>
                              </span>
                            </li>
                          ))}
                        </ul>
                      ) : null}
                    </li>
                  )
                })}
              </ul>
            ) : null}
          </li>
        )
      })}
    </ul>
  )
}

function OutflowPanel({ o }: { o: AuditOverview }) {
  const { showEvidence } = useAudit()
  const c = o.cashOutflow
  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h3 className="text-sm font-semibold">Cash outflow — group → document status → counterparty</h3>
          <p className="text-xs text-muted-foreground">
            {fmtGel(c.total)} across {fmtCount(c.debitRowCount)} bank debit rows · mapped {fmtGel(c.mapped)} · unmapped {fmtGel(c.unmapped)} on {fmtCount(c.unmappedRowCount)} rows
            {o.supplierTin ? ` · to ${o.supplierName ?? o.supplierTin}: ${fmtGel(c.toChosen)}` : ''}.
            Groups and document statuses are yours to extend (Rules → Groups & subgroups); a slice with no status sits under "No document status".
          </p>
        </div>
        <Button size="sm" variant="outline" onClick={() => showEvidence({ key: 'cash.unresolvedWithdrawals', label: 'Unmapped bank outflow' })}>
          Map unmapped rows <ExternalLink className="ml-1 h-3.5 w-3.5" />
        </Button>
      </div>
      <BucketTree buckets={c.groups} depthLabels={['group', 'status', 'to']} />

      <div>
        <h3 className="text-sm font-semibold">Paper outflow (unreal-sale chains)</h3>
        <p className="mb-2 text-xs text-muted-foreground">
          {fmtGel(c.paperTotal)} of on-paper cash routed from unreal sales to a supplier ("check needed from X", "purchase act needed"). Shown beside real money, never added to it.
        </p>
        <BucketTree buckets={c.paperGroups} depthLabels={['group', 'status', 'from']} />
      </div>
    </div>
  )
}

function SalesPanel({ o }: { o: AuditOverview }) {
  const { showEvidence } = useAudit()
  const s = o.sales
  return (
    <div className="space-y-5">
      <div className="grid gap-2 sm:grid-cols-4">
        {[
          ['Total', s.total],
          ['Real', s.real],
          ['Unreal', s.unreal],
          ['Unreal already chained', s.unrealMapped],
        ].map(([l, v]) => (
          <div key={String(l)} className="rounded-md border border-border p-2">
            <div className="text-[11px] uppercase tracking-wide text-muted-foreground">{l}</div>
            <div className="text-base font-semibold tabular-nums">{fmtGel(v as number | null)}</div>
          </div>
        ))}
      </div>
      <p className="text-xs text-muted-foreground">
        Unreal = documented sales to customers marked unreal on /audit-control, or lines mapped as paper-only sale. Map an unreal line as
        "paper-only supplier payment → supplier X, status Check needed" to route its cash to that supplier; the routed part shows as
        paper outflow. {fmtGel(s.unrealUnmapped)} of unreal sales is not chained anywhere yet.
      </p>
      <div>
        <h3 className="text-sm font-semibold">Sales by product group</h3>
        <CategoryTable rows={s.byCategory} showChosen={false} kgLabel="kg (kg lines only)" />
      </div>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h3 className="text-sm font-semibold">Unreal customers</h3>
          <p className="text-xs text-muted-foreground">Documented sales per unreal customer and how much of it has been chained onwards.</p>
        </div>
        <Button size="sm" variant="outline" onClick={() => showEvidence({ key: 'documentation.rows', label: 'Document rows' })}>
          Open document rows to map <ExternalLink className="ml-1 h-3.5 w-3.5" />
        </Button>
      </div>
      <CounterpartyTable
        rows={s.unrealCustomers}
        columns={[
          { key: 'purchases', label: '₾ documented sales', kind: 'gel' },
          { key: 'paperOutflow', label: '₾ chained', kind: 'gel' },
          { key: 'rowCount', label: 'lines', kind: 'count' },
        ]}
        emptyText="No unreal customers with sales in this period (or none marked unreal on /audit-control)."
      />
    </div>
  )
}
