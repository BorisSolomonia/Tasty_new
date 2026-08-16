/**
 * The window one statement row opens (BOR-92 v2).
 *
 * Parties sheet: every counterparty behind the figure, ₾ / kg / lines, a
 * checkbox that includes it in "chosen" (saved, shared across the rows that
 * use the same set), and a chevron that unfolds its transactions in place.
 * Products sheet (purchases and sales): the same figure by product group; a
 * group unfolds to its lines, where a product's group can be corrected — a
 * shared rule that moves every line carrying that product name, which the
 * confirmation states before anything is written.
 * Inventory: levels per group with the LIFO supplier attribution.
 */
import * as React from 'react'
import { ChevronDown, ChevronRight, Search } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { cn } from '@/lib/cn'
import type {
  AuditStatement,
  StatementInventoryRow,
  StatementLevel,
  StatementParty,
  StatementProductGroup,
  StatementRow,
  StatementRowKey,
  StatementSelection,
} from '@/lib/audit-api'
import { CATEGORY_LABELS } from '@/features/audit-control/labels'
import { useAudit } from '../audit-context'
import { EM_DASH, fmtCount, fmtGel, fmtKg } from '../format'
import { TransactionsTable } from './transactions-table'

export type StatementDialogTarget = { key: StatementRowKey | 'inventory' }

const PARTY_LABEL: Record<StatementRowKey, string> = {
  purchases: 'Suppliers',
  bankPaymentsToSuppliers: 'Suppliers',
  cashOutflow: 'Counterparties',
  sales: 'Customers',
  bankInflow: 'Customers',
  cashInflow: 'Customers',
}

export function catLabel(code: string | null | undefined): string {
  if (!code) return EM_DASH
  return CATEGORY_LABELS[code] ?? code
}

export function StatementDialog({
  statement,
  target,
  onClose,
  selection,
  onToggle,
  onSetMany,
  canEdit,
}: {
  statement: AuditStatement
  target: StatementDialogTarget
  onClose: () => void
  selection: StatementSelection
  onToggle: (set: 'suppliers' | 'customers', tin: string, on?: boolean) => void
  onSetMany: (set: 'suppliers' | 'customers', tins: string[], on: boolean) => void
  canEdit: boolean
}) {
  const isInventory = target.key === 'inventory'
  const row = isInventory ? null : (statement[target.key] as StatementRow)
  const inv = isInventory ? statement.inventory : null
  const title = row?.title ?? inv?.title ?? ''
  const definition = row?.definition ?? inv?.definition ?? ''

  return (
    <Dialog open onOpenChange={(open) => (!open ? onClose() : undefined)}>
      <DialogContent size="wide" aria-describedby="statement-dialog-desc">
        <DialogHeader>
          <DialogTitle className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
            <span>{title}</span>
            <span className="text-sm font-normal text-muted-foreground">
              {statement.startDate} → {statement.endDate}
            </span>
          </DialogTitle>
          <DialogDescription id="statement-dialog-desc">{definition}</DialogDescription>
        </DialogHeader>

        {row ? (
          <div className="flex flex-wrap gap-x-6 gap-y-1 text-sm">
            <Stat label="Total" value={fmtGel(row.total)} sub={row.totalKg !== null ? fmtKg(row.totalKg, 0) : undefined} />
            <Stat
              label="Chosen"
              value={row.chosen === null ? EM_DASH : fmtGel(row.chosen)}
              sub={row.chosen === null ? 'nothing ticked' : row.chosenKg !== null ? fmtKg(row.chosenKg, 0) : undefined}
            />
            {(row.extras ?? (row.secondary !== null && row.secondaryLabel ? [{ label: row.secondaryLabel, amount: row.secondary }] : [])).map((f) => (
              <Stat key={f.label} label={f.label} value={fmtGel(f.amount)} />
            ))}
          </div>
        ) : inv ? (
          <div className="flex flex-wrap gap-x-6 gap-y-1 text-sm">
            <Stat label="Net kg" value={fmtKg(inv.totalKg, 0)} />
            <Stat label="Value" value={fmtGel(inv.totalValue)} sub={inv.unpricedCategories.length ? `excludes ${inv.unpricedCategories.join(', ')}` : 'at avg purchase price / kg'} />
          </div>
        ) : null}

        <div className="min-h-0 flex-1 overflow-y-auto pr-1">
          {inv ? <InventoryLevels inv={inv} /> : null}
          {row && row.products ? (
            <Tabs defaultValue="parties">
              <TabsList className="mb-3 h-8">
                <TabsTrigger value="parties" className="h-7 text-xs">
                  {PARTY_LABEL[row.key]} ({fmtCount(row.parties.length)})
                </TabsTrigger>
                <TabsTrigger value="products" className="h-7 text-xs">
                  Products ({fmtCount(row.products.length)})
                </TabsTrigger>
              </TabsList>
              <TabsContent value="parties">
                <PartiesSheet row={row} statement={statement} selection={selection} onToggle={onToggle} onSetMany={onSetMany} canEdit={canEdit} />
              </TabsContent>
              <TabsContent value="products">
                <ProductsSheet row={row} statement={statement} />
              </TabsContent>
            </Tabs>
          ) : row ? (
            <PartiesSheet row={row} statement={statement} selection={selection} onToggle={onToggle} onSetMany={onSetMany} canEdit={canEdit} />
          ) : null}
        </div>
      </DialogContent>
    </Dialog>
  )
}

function Stat({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="leading-tight">
      <div className="text-[11px] uppercase tracking-wide text-muted-foreground">{label}</div>
      <div className="font-semibold tabular-nums">{value}</div>
      {sub ? <div className="text-[11px] tabular-nums text-muted-foreground">{sub}</div> : null}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Parties
// ---------------------------------------------------------------------------

function PartiesSheet({
  row,
  statement,
  selection,
  onToggle,
  onSetMany,
  canEdit,
}: {
  row: StatementRow
  statement: AuditStatement
  selection: StatementSelection
  onToggle: (set: 'suppliers' | 'customers', tin: string, on?: boolean) => void
  onSetMany: (set: 'suppliers' | 'customers', tins: string[], on: boolean) => void
  canEdit: boolean
}) {
  const set: 'suppliers' | 'customers' = row.chosenBy === 'SUPPLIERS' ? 'suppliers' : 'customers'
  const chosenSet = new Set(selection[set])
  const [needle, setNeedle] = React.useState('')
  const [open, setOpen] = React.useState<string | null>(null)
  const [attribution, setAttribution] = React.useState<'ALL' | 'DIRECT' | 'MAPPED'>('ALL')
  const [withdrawalsOnly, setWithdrawalsOnly] = React.useState(false)
  const hasKg = row.key === 'purchases' || row.key === 'sales'
  const hasSecondary = row.key === 'cashOutflow'
  const isBankRow = row.key === 'cashOutflow' || row.key === 'bankPaymentsToSuppliers' || row.key === 'bankInflow'
  const isPurchases = row.key === 'purchases'
  const withdrawalsFigure = row.extras?.find((f) => f.label === 'withdrawals')

  const q = needle.trim().toLowerCase()
  const visible = q ? row.parties.filter((p) => p.name.toLowerCase().includes(q) || p.tin.toLowerCase().includes(q)) : row.parties
  const choosable = visible.filter((p) => !p.tin.startsWith('name:'))
  const allOn = choosable.length > 0 && choosable.every((p) => chosenSet.has(p.tin))

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <label className="relative">
          <Search className="pointer-events-none absolute left-2 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground" />
          <input
            className="h-8 w-64 rounded-md border border-input bg-background pl-7 pr-2 text-sm"
            placeholder={`Filter ${PARTY_LABEL[row.key].toLowerCase()}…`}
            value={needle}
            onChange={(e) => setNeedle(e.target.value)}
            aria-label={`Filter ${PARTY_LABEL[row.key]}`}
          />
        </label>
        <div className="flex flex-wrap items-center gap-2 text-xs">
          {isBankRow ? (
            <label className="inline-flex items-center gap-1 text-muted-foreground" title="Only the rows attributed to a party by a slice on someone else's row, or only its own rows">
              <span>Show</span>
              <select className="h-7 rounded border border-input bg-background px-1 text-xs" value={attribution} onChange={(e) => setAttribution(e.target.value as 'ALL' | 'DIRECT' | 'MAPPED')} aria-label="Attribution filter">
                <option value="ALL">all transactions</option>
                <option value="DIRECT">direct only (party's own rows)</option>
                <option value="MAPPED">mapped only (attributed by a slice)</option>
              </select>
            </label>
          ) : null}
          {row.key === 'cashOutflow' ? (
            <label className="inline-flex items-center gap-1 text-muted-foreground">
              <input type="checkbox" className="h-3.5 w-3.5" checked={withdrawalsOnly} onChange={(e) => setWithdrawalsOnly(e.target.checked)} aria-label="Withdrawals only" />
              withdrawals only{withdrawalsFigure ? ` (${fmtGel(withdrawalsFigure.amount)})` : ''}
            </label>
          ) : null}
          <span className="text-muted-foreground">
            {fmtCount(choosable.filter((p) => chosenSet.has(p.tin)).length)} of {fmtCount(choosable.length)} listed are chosen
          </span>
          <Button size="sm" variant="outline" className="h-7 px-2" disabled={!canEdit || choosable.length === 0} onClick={() => onSetMany(set, choosable.map((p) => p.tin), !allOn)}>
            {allOn ? 'Untick listed' : 'Tick listed'}
          </Button>
        </div>
      </div>

      <div className="overflow-x-auto rounded-md border border-border">
        <table className="w-full min-w-[44rem] text-sm">
          <thead className="bg-muted/40 text-[11px] uppercase tracking-wide text-muted-foreground">
            <tr>
              <th scope="col" className="w-8 px-2 py-1.5">
                <span className="sr-only">Chosen</span>
              </th>
              <th scope="col" className="px-2 py-1.5 text-left font-medium">
                {PARTY_LABEL[row.key].replace(/s$/, '')}
              </th>
              <th scope="col" className="px-2 py-1.5 text-right font-medium">
                ₾
              </th>
              {hasKg ? (
                <th scope="col" className="px-2 py-1.5 text-right font-medium">
                  kg
                </th>
              ) : null}
              {isPurchases ? (
                <th scope="col" className="px-2 py-1.5 text-right font-medium" title="Real bank money mapped to this supplier in the period (supplier-settlement slices)">
                  bank paid ₾
                </th>
              ) : null}
              {isPurchases ? (
                <th scope="col" className="px-2 py-1.5 text-right font-medium" title="Documented purchases minus bank paid — what the bank has not settled: cash, checks, or still owed">
                  unpaid after bank ₾
                </th>
              ) : null}
              {isBankRow ? (
                <th scope="col" className="px-2 py-1.5 text-right font-medium" title="On this party's own rows">
                  direct ₾
                </th>
              ) : null}
              {isBankRow ? (
                <th scope="col" className="px-2 py-1.5 text-right font-medium" title="Attributed to this party by slices on other counterparties' rows">
                  mapped ₾
                </th>
              ) : null}
              {hasSecondary ? (
                <th scope="col" className="px-2 py-1.5 text-right font-medium">
                  unmapped ₾
                </th>
              ) : null}
              <th scope="col" className="px-2 py-1.5 text-right font-medium">
                {row.key === 'purchases' || row.key === 'sales' ? 'lines' : 'rows'}
              </th>
              <th scope="col" className="w-8 px-2 py-1.5">
                <span className="sr-only">Transactions</span>
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {visible.map((p) => {
              const nameless = p.tin.startsWith('name:')
              const isOpen = open === p.tin
              const checked = chosenSet.has(p.tin)
              return (
                <React.Fragment key={p.tin}>
                  <tr className={cn('hover:bg-accent/40', isOpen && 'bg-accent/30')}>
                    <td className="px-2 py-1.5 align-middle">
                      <input
                        type="checkbox"
                        className="h-4 w-4"
                        checked={checked}
                        disabled={!canEdit || nameless}
                        aria-label={`Choose ${p.name}`}
                        title={nameless ? 'No TIN — the source did not identify this counterparty, so it cannot be chosen' : canEdit ? undefined : 'Enter an operator name to tick'}
                        onChange={(e) => onToggle(set, p.tin, e.target.checked)}
                      />
                    </td>
                    <td className="px-2 py-1.5">
                      <button
                        type="button"
                        className="flex w-full items-start gap-1 text-left"
                        aria-expanded={isOpen}
                        onClick={() => setOpen(isOpen ? null : p.tin)}
                      >
                        {isOpen ? <ChevronDown className="mt-0.5 h-3.5 w-3.5 shrink-0" /> : <ChevronRight className="mt-0.5 h-3.5 w-3.5 shrink-0" />}
                        <span className="min-w-0">
                          <span className="block truncate font-medium">{p.name}</span>
                          <span className="block text-[11px] text-muted-foreground">
                            {nameless ? 'no TIN in source' : p.tin}
                            {p.unreal ? ' · unreal' : ''}
                            {p.identityBasis ? ` · ${p.identityBasis}` : ''}
                          </span>
                        </span>
                      </button>
                    </td>
                    <td className="px-2 py-1.5 text-right tabular-nums whitespace-nowrap">{fmtGel(p.amount)}</td>
                    {hasKg ? <td className="px-2 py-1.5 text-right tabular-nums whitespace-nowrap">{fmtKg(p.quantityKg, 0)}</td> : null}
                    {isPurchases ? <td className="px-2 py-1.5 text-right tabular-nums whitespace-nowrap">{fmtGel(p.bankPaid)}</td> : null}
                    {isPurchases ? (
                      <td className={cn('px-2 py-1.5 text-right tabular-nums whitespace-nowrap', (p.unpaidAfterBank ?? 0) < 0 && 'text-destructive')} title={(p.unpaidAfterBank ?? 0) < 0 ? 'Bank paid more than the documents show — paid, undocumented' : undefined}>
                        {fmtGel(p.unpaidAfterBank)}
                      </td>
                    ) : null}
                    {isBankRow ? (
                      <td className="px-2 py-1.5 text-right tabular-nums whitespace-nowrap">
                        {fmtGel(p.directAmount)}
                        <span className="ml-1 text-[10px] text-muted-foreground">·{fmtCount(p.directCount)}</span>
                      </td>
                    ) : null}
                    {isBankRow ? (
                      <td className="px-2 py-1.5 text-right tabular-nums whitespace-nowrap">
                        {fmtGel(p.mappedAmount)}
                        <span className="ml-1 text-[10px] text-muted-foreground">·{fmtCount(p.mappedCount)}</span>
                      </td>
                    ) : null}
                    {hasSecondary ? (
                      <td className={cn('px-2 py-1.5 text-right tabular-nums whitespace-nowrap', (p.secondary ?? 0) > 0 && 'text-destructive')}>
                        {p.secondary === null ? EM_DASH : fmtGel(p.secondary)}
                      </td>
                    ) : null}
                    <td className="px-2 py-1.5 text-right tabular-nums">{fmtCount(p.rowCount)}</td>
                    <td className="px-2 py-1.5" />
                  </tr>
                  {isOpen ? (
                    <tr>
                      <td colSpan={11} className="bg-muted/20 px-3 py-2">
                        <TransactionsTable
                          row={row.key}
                          startDate={statement.startDate}
                          endDate={statement.endDate}
                          tin={p.tin}
                          attribution={attribution === 'ALL' ? undefined : attribution}
                          withdrawalsOnly={withdrawalsOnly || undefined}
                        />
                      </td>
                    </tr>
                  ) : null}
                </React.Fragment>
              )
            })}
            {!visible.length ? (
              <tr>
                <td colSpan={11} className="px-3 py-3 text-muted-foreground">
                  {row.parties.length ? 'No counterparty matches the filter.' : 'Nothing in this period.'}
                </td>
              </tr>
            ) : null}
          </tbody>
        </table>
      </div>
      {withdrawalsOnly ? (
        <div className="rounded-md border border-border bg-muted/20 p-2">
          <div className="mb-1 text-xs font-medium">All withdrawals in the period{withdrawalsFigure ? ` — ${fmtGel(withdrawalsFigure.amount)}` : ''}</div>
          <TransactionsTable row={row.key} startDate={statement.startDate} endDate={statement.endDate} withdrawalsOnly />
        </div>
      ) : null}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Products (purchases / sales)
// ---------------------------------------------------------------------------

function ProductsSheet({ row, statement }: { row: StatementRow; statement: AuditStatement }) {
  const [open, setOpen] = React.useState<string | null>(null)
  const groups: StatementProductGroup[] = row.products ?? []
  const withChosen = row.chosen !== null
  return (
    <div className="space-y-2">
      <p className="text-xs text-muted-foreground">
        Product groups come from the shared category store (/audit-control, /product-categories). Open a group to see its lines; a
        line's group can be changed there — that corrects the product everywhere, not just this line.
      </p>
      <div className="overflow-x-auto rounded-md border border-border">
        <table className="w-full min-w-[40rem] text-sm">
          <thead className="bg-muted/40 text-[11px] uppercase tracking-wide text-muted-foreground">
            <tr>
              <th scope="col" className="px-2 py-1.5 text-left font-medium">
                Group
              </th>
              <th scope="col" className="px-2 py-1.5 text-right font-medium">
                ₾
              </th>
              <th scope="col" className="px-2 py-1.5 text-right font-medium">
                kg
              </th>
              {withChosen ? (
                <th scope="col" className="px-2 py-1.5 text-right font-medium">
                  ₾ chosen
                </th>
              ) : null}
              {withChosen ? (
                <th scope="col" className="px-2 py-1.5 text-right font-medium">
                  kg chosen
                </th>
              ) : null}
              <th scope="col" className="px-2 py-1.5 text-right font-medium">
                products
              </th>
              <th scope="col" className="px-2 py-1.5 text-right font-medium">
                lines
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {groups.map((g) => {
              const isOpen = open === g.category
              return (
                <React.Fragment key={g.category}>
                  <tr className={cn('hover:bg-accent/40', isOpen && 'bg-accent/30')}>
                    <td className="px-2 py-1.5">
                      <button type="button" className="flex items-center gap-1 text-left font-medium" aria-expanded={isOpen} onClick={() => setOpen(isOpen ? null : g.category)}>
                        {isOpen ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" />}
                        {catLabel(g.category)}
                      </button>
                    </td>
                    <td className="px-2 py-1.5 text-right tabular-nums whitespace-nowrap">{fmtGel(g.amount)}</td>
                    <td className="px-2 py-1.5 text-right tabular-nums whitespace-nowrap">{fmtKg(g.quantityKg, 0)}</td>
                    {withChosen ? <td className="px-2 py-1.5 text-right tabular-nums whitespace-nowrap">{fmtGel(g.chosenAmount)}</td> : null}
                    {withChosen ? <td className="px-2 py-1.5 text-right tabular-nums whitespace-nowrap">{fmtKg(g.chosenKg, 0)}</td> : null}
                    <td className="px-2 py-1.5 text-right tabular-nums">{fmtCount(g.productCount)}</td>
                    <td className="px-2 py-1.5 text-right tabular-nums">{fmtCount(g.rowCount)}</td>
                  </tr>
                  {isOpen ? (
                    <tr>
                      <td colSpan={7} className="bg-muted/20 px-3 py-2">
                        <TransactionsTable row={row.key} startDate={statement.startDate} endDate={statement.endDate} category={g.category} allowRecategorise />
                      </td>
                    </tr>
                  ) : null}
                </React.Fragment>
              )
            })}
            {!groups.length ? (
              <tr>
                <td colSpan={7} className="px-3 py-3 text-muted-foreground">
                  No document lines in this period.
                </td>
              </tr>
            ) : null}
          </tbody>
        </table>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Inventory levels
// ---------------------------------------------------------------------------

function InventoryLevels({ inv }: { inv: StatementInventoryRow }) {
  const [open, setOpen] = React.useState<string | null>(null)
  const { showEvidence } = useAudit()
  return (
    <div className="space-y-2">
      <p className="text-xs text-muted-foreground">
        Levels are the period's net movement per group: purchased − write-off (the group's rate) − sold. Open a group to see which
        suppliers the remaining kg came from, latest purchases first (LIFO). Real counted stock lives in the Inventory section below.
      </p>
      <div className="overflow-x-auto rounded-md border border-border">
        <table className="w-full min-w-[52rem] text-sm">
          <thead className="bg-muted/40 text-[11px] uppercase tracking-wide text-muted-foreground">
            <tr>
              <th scope="col" className="px-2 py-1.5 text-left font-medium">
                Group
              </th>
              <th scope="col" className="px-2 py-1.5 text-right font-medium">
                Purchased kg
              </th>
              <th scope="col" className="px-2 py-1.5 text-right font-medium">
                Write-off
              </th>
              <th scope="col" className="px-2 py-1.5 text-right font-medium">
                Sold kg
              </th>
              <th scope="col" className="px-2 py-1.5 text-right font-medium">
                Net kg
              </th>
              <th scope="col" className="px-2 py-1.5 text-right font-medium">
                ₾ / kg
              </th>
              <th scope="col" className="px-2 py-1.5 text-right font-medium">
                Value ₾
              </th>
              <th scope="col" className="px-2 py-1.5 text-left font-medium">
                Suppliers
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {inv.levels.map((l: StatementLevel) => {
              const isOpen = open === l.category
              return (
                <React.Fragment key={l.category}>
                  <tr className={cn('hover:bg-accent/40', isOpen && 'bg-accent/30')}>
                    <td className="px-2 py-1.5">
                      <button type="button" className="flex items-center gap-1 text-left font-medium" aria-expanded={isOpen} onClick={() => setOpen(isOpen ? null : l.category)}>
                        {isOpen ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" />}
                        {catLabel(l.category)}
                      </button>
                    </td>
                    <td className="px-2 py-1.5 text-right tabular-nums whitespace-nowrap">{fmtKg(l.purchasedKg, 0)}</td>
                    <td className="px-2 py-1.5 text-right tabular-nums whitespace-nowrap">
                      {fmtKg(l.writeOffKg, 0)} <span className="text-muted-foreground">({l.writeOffPercent ?? 0}%)</span>
                    </td>
                    <td className="px-2 py-1.5 text-right tabular-nums whitespace-nowrap">{fmtKg(l.soldKg, 0)}</td>
                    <td className={cn('px-2 py-1.5 text-right font-medium tabular-nums whitespace-nowrap', (l.netKg ?? 0) < 0 && 'text-destructive')}>{fmtKg(l.netKg, 0)}</td>
                    <td className="px-2 py-1.5 text-right tabular-nums whitespace-nowrap">{l.avgPurchasePricePerKg === null ? EM_DASH : fmtGel(l.avgPurchasePricePerKg)}</td>
                    <td className="px-2 py-1.5 text-right tabular-nums whitespace-nowrap">{l.value === null ? <span title="No priced purchases this period">{EM_DASH}</span> : fmtGel(l.value)}</td>
                    <td className="px-2 py-1.5 text-muted-foreground">
                      {l.stockBySupplier.length ? `${fmtCount(l.stockBySupplier.length)}` : (l.netKg ?? 0) <= 0 ? 'nothing left on paper' : EM_DASH}
                    </td>
                  </tr>
                  {isOpen ? (
                    <tr>
                      <td colSpan={8} className="bg-muted/20 px-3 py-2">
                        {l.stockBySupplier.length ? (
                          <ul className="grid gap-1 sm:grid-cols-2 lg:grid-cols-3">
                            {l.stockBySupplier.map((s) => (
                              <li key={s.tin ?? s.name ?? ''} className="flex items-baseline justify-between gap-2 rounded border border-border bg-card px-2 py-1 text-xs">
                                <span className="min-w-0">
                                  <span className="block truncate font-medium">{s.name ?? s.tin}</span>
                                  <span className="text-muted-foreground">
                                    {s.tin}
                                    {s.lastPurchaseDate ? ` · last ${s.lastPurchaseDate}` : ''}
                                  </span>
                                </span>
                                <span className="whitespace-nowrap tabular-nums">{fmtKg(s.quantityKg, 0)}</span>
                              </li>
                            ))}
                          </ul>
                        ) : (
                          <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-muted-foreground">
                            <span>
                              {(l.netKg ?? 0) <= 0
                                ? 'Sales exceed purchases after write-off in this period — nothing to attribute. Either opening stock covered it, or lines are in the wrong group (check the Products sheet of Purchases / Sales).'
                                : 'No purchase lots with a supplier TIN.'}
                            </span>
                            <Button size="sm" variant="ghost" className="h-7 px-2" onClick={() => showEvidence({ key: 'inventory.negativeGap', label: 'Sold more than purchased' })}>
                              Open inventory section
                            </Button>
                          </div>
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
  )
}

export function partyLabelFor(row: StatementRowKey): string {
  return PARTY_LABEL[row]
}

export type { StatementParty }
