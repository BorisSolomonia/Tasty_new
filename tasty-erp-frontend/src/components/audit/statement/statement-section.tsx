/**
 * The statement at the top of /audit (BOR-92 v2).
 *
 * One ledger, seven rows in income-statement order, two figure columns:
 * Total (the whole period) and Chosen (the same figure for the ticked
 * counterparties). Every row opens its own window with the parties behind the
 * figure — each tickable, each expandable to its transactions — and, for the
 * document rows, a Products sheet where a product's group can be corrected
 * once for everything.
 *
 * "Chosen" is a saved selection: one supplier set shared by the supplier-side
 * rows and one customer set shared by the customer-side rows, stored per
 * operator name so it survives reloads and devices.
 */
import * as React from 'react'
import { ChevronRight, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/cn'
import { apiErrorMessage } from '@/lib/api-client'
import type { AuditStatement, StatementRow, StatementRowKey, StatementSelection } from '@/lib/audit-api'
import { useAuditStatement, useSaveStatementSelection } from '@/hooks/use-audit-flows'
import { useAudit } from '../audit-context'
import { useOperatorGuard } from '../operator-picker'
import { EM_DASH, fmtCount, fmtGel, fmtKg } from '../format'
import { StatementDialog, type StatementDialogTarget } from './statement-dialog'

export const STATEMENT_ORDER: (StatementRowKey | 'inventory')[] = [
  'purchases',
  'bankPaymentsToSuppliers',
  'cashOutflow',
  'inventory',
  'sales',
  'bankInflow',
  'cashInflow',
]

const SAVE_DEBOUNCE_MS = 500

function sameSelection(a: StatementSelection, b: StatementSelection): boolean {
  const s = (x: string[]) => [...x].sort().join('|')
  return s(a.suppliers) === s(b.suppliers) && s(a.customers) === s(b.customers)
}

/**
 * The selection lives locally while the operator is ticking and is written
 * back debounced; the server copy wins again once it agrees with ours. Ticks
 * are never lost to a refetch that lands mid-edit.
 */
export function useStatementSelection(serverSelection: StatementSelection | undefined) {
  const { operator } = useAudit()
  const { ready } = useOperatorGuard()
  const save = useSaveStatementSelection(operator)
  const [local, setLocal] = React.useState<StatementSelection | null>(null)
  const timer = React.useRef<number | null>(null)
  const latest = React.useRef<StatementSelection | null>(null)

  React.useEffect(() => {
    if (local && serverSelection && sameSelection(local, serverSelection) && !save.isPending) {
      setLocal(null)
    }
  }, [local, serverSelection, save.isPending])

  const effective: StatementSelection = React.useMemo(
    () => local ?? serverSelection ?? { suppliers: [], customers: [] },
    [local, serverSelection]
  )

  const flush = React.useCallback(() => {
    if (timer.current !== null) {
      window.clearTimeout(timer.current)
      timer.current = null
    }
    if (latest.current) save.mutate(latest.current)
  }, [save])

  const update = React.useCallback(
    (next: StatementSelection) => {
      setLocal(next)
      latest.current = next
      if (timer.current !== null) window.clearTimeout(timer.current)
      timer.current = window.setTimeout(flush, SAVE_DEBOUNCE_MS)
    },
    [flush]
  )

  const toggle = React.useCallback(
    (set: 'suppliers' | 'customers', tin: string, on?: boolean) => {
      const current = effective[set]
      const has = current.includes(tin)
      const want = on ?? !has
      if (want === has) return
      update({ ...effective, [set]: want ? [...current, tin] : current.filter((t) => t !== tin) })
    },
    [effective, update]
  )

  const setMany = React.useCallback(
    (set: 'suppliers' | 'customers', tins: string[], on: boolean) => {
      const current = new Set(effective[set])
      for (const t of tins) {
        if (on) current.add(t)
        else current.delete(t)
      }
      update({ ...effective, [set]: [...current] })
    },
    [effective, update]
  )

  const clear = React.useCallback(() => update({ suppliers: [], customers: [] }), [update])

  return {
    selection: effective,
    toggle,
    setMany,
    clear,
    canEdit: ready,
    saving: save.isPending || local !== null,
    error: save.isError ? apiErrorMessage(save.error) : null,
  }
}

export function StatementSection() {
  const { filters, operator } = useAudit()
  const { message: operatorMessage } = useOperatorGuard()
  const query = useAuditStatement({ startDate: filters.startDate, endDate: filters.endDate, operator: operator || undefined })
  const statement = query.data
  const sel = useStatementSelection(statement?.selection)
  const [target, setTarget] = React.useState<StatementDialogTarget | null>(null)

  const chosenCount = sel.selection.suppliers.length + sel.selection.customers.length

  return (
    <section aria-labelledby="statement-h" className="rounded-lg border border-border bg-card">
      <div className="flex flex-wrap items-start justify-between gap-3 border-b border-border px-4 py-3">
        <div>
          <h2 id="statement-h" className="text-sm font-semibold">
            Statement <span className="font-normal text-muted-foreground">{filters.startDate} → {filters.endDate}</span>
          </h2>
          <p className="text-xs text-muted-foreground">Total = the whole period. Chosen = the counterparties you tick inside each row. Click a row to open it.</p>
        </div>
        <div className="flex items-center gap-2 text-xs">
          <span className="text-muted-foreground">
            {chosenCount === 0
              ? 'Nothing chosen yet'
              : `${fmtCount(sel.selection.suppliers.length)} supplier${sel.selection.suppliers.length === 1 ? '' : 's'} · ${fmtCount(sel.selection.customers.length)} customer${sel.selection.customers.length === 1 ? '' : 's'} chosen`}
            {sel.saving ? ' · saving…' : ''}
          </span>
          {chosenCount > 0 ? (
            <Button size="sm" variant="ghost" className="h-7 px-2" disabled={!sel.canEdit} onClick={sel.clear}>
              <X className="mr-1 h-3.5 w-3.5" /> Clear
            </Button>
          ) : null}
        </div>
      </div>

      {query.isError ? (
        <p className="px-4 py-3 text-sm text-destructive">Statement did not load: {apiErrorMessage(query.error)}</p>
      ) : null}
      {sel.error ? <p className="px-4 pt-2 text-xs text-destructive">Selection not saved: {sel.error}</p> : null}
      {!sel.canEdit ? <p className="px-4 pt-2 text-xs text-muted-foreground">{operatorMessage} Ticks are then saved under that name.</p> : null}

      <div className="overflow-x-auto">
        <table className="w-full min-w-[40rem] text-sm">
          <thead>
            <tr className="text-[11px] uppercase tracking-wide text-muted-foreground">
              <th scope="col" className="px-4 py-2 text-left font-medium">
                Row
              </th>
              <th scope="col" className="px-3 py-2 text-right font-medium">
                Total
              </th>
              <th scope="col" className="px-3 py-2 text-right font-medium">
                Chosen
              </th>
              <th scope="col" className="px-3 py-2 text-left font-medium">
                Note
              </th>
              <th scope="col" className="w-8 px-2 py-2">
                <span className="sr-only">Open</span>
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {STATEMENT_ORDER.map((key) => (
              <StatementLine key={key} rowKey={key} statement={statement} loading={query.isLoading} onOpen={() => setTarget({ key })} />
            ))}
          </tbody>
        </table>
      </div>

      {statement?.summary ? <SummaryLines summary={statement.summary} /> : null}

      {statement?.notes?.length ? (
        <ul className="list-disc space-y-0.5 border-t border-border px-4 py-2 pl-8 text-[11px] text-muted-foreground">
          {statement.notes.map((n) => (
            <li key={n}>{n}</li>
          ))}
        </ul>
      ) : null}

      {statement && target ? (
        <StatementDialog
          statement={statement}
          target={target}
          onClose={() => setTarget(null)}
          selection={sel.selection}
          onToggle={sel.toggle}
          onSetMany={sel.setMany}
          canEdit={sel.canEdit}
        />
      ) : null}
    </section>
  )
}

/** Money on the first line, kg (when the row has it) beneath — never side by side, so nothing can overlap. */
export function Figure({ amount, kg, muted }: { amount: number | null | undefined; kg?: number | null; muted?: boolean }) {
  return (
    <span className={cn('inline-flex flex-col items-end leading-tight', muted && 'text-muted-foreground')}>
      <span className="whitespace-nowrap tabular-nums">{fmtGel(amount)}</span>
      {kg !== undefined ? <span className="whitespace-nowrap text-[11px] tabular-nums text-muted-foreground">{kg === null ? '' : fmtKg(kg, 0)}</span> : null}
    </span>
  )
}

function StatementLine({
  rowKey,
  statement,
  loading,
  onOpen,
}: {
  rowKey: StatementRowKey | 'inventory'
  statement: AuditStatement | undefined
  loading: boolean
  onOpen: () => void
}) {
  const isInventory = rowKey === 'inventory'
  const row = statement && !isInventory ? (statement[rowKey] as StatementRow) : null
  const inv = statement && isInventory ? statement.inventory : null
  const title = row?.title ?? inv?.title ?? TITLES[rowKey]
  const definition = row?.definition ?? inv?.definition ?? ''
  const emphasis = rowKey === 'purchases' || rowKey === 'sales'

  let note: React.ReactNode = null
  const extras = row?.extras ?? (row?.secondary != null && row.secondaryLabel ? [{ label: row.secondaryLabel, amount: row.secondary }] : [])
  if (extras.length) {
    note = (
      <span className="inline-flex flex-col gap-0.5 text-xs leading-tight">
        {extras.map((f) => (
          <span key={f.label} className={cn('whitespace-nowrap', (f.label === 'unmapped' || f.label === 'unmapped income') && (f.amount ?? 0) > 0 ? 'text-destructive' : 'text-muted-foreground')}>
            {f.label} {fmtGel(f.amount)}
          </span>
        ))}
      </span>
    )
  } else if (inv) {
    note = (
      <span className="whitespace-nowrap text-xs text-muted-foreground">
        {inv.unpricedCategories.length ? `value excludes ${inv.unpricedCategories.join(', ')}` : 'at avg purchase price'}
      </span>
    )
  }
  const chosenMissing = row ? row.chosen === null : true

  return (
    <tr
      className="cursor-pointer transition-colors hover:bg-accent/60 focus-within:bg-accent/60"
      onClick={onOpen}
      data-row={rowKey}
    >
      <td className={cn('px-4 py-2', emphasis && 'font-semibold')}>
        <button
          type="button"
          className="text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring rounded"
          onClick={(e) => {
            e.stopPropagation()
            onOpen()
          }}
          title={definition}
          aria-label={`${title} — open`}
        >
          {title}
        </button>
        {row ? <div className="text-[11px] font-normal text-muted-foreground">{fmtCount(row.rowCount)} {rowKey === 'purchases' || rowKey === 'sales' ? 'lines' : rowKey === 'bankPaymentsToSuppliers' ? 'slices' : 'rows'}</div> : null}
      </td>
      <td className="px-3 py-2 text-right align-top">
        {loading ? <span className="text-muted-foreground">…</span> : inv ? (
          <span className="inline-flex flex-col items-end leading-tight">
            <span className="whitespace-nowrap tabular-nums">{fmtKg(inv.totalKg, 0)}</span>
            <span className="whitespace-nowrap text-[11px] tabular-nums text-muted-foreground">{fmtGel(inv.totalValue)}</span>
          </span>
        ) : (
          <Figure amount={row?.total} kg={row && row.totalKg !== null ? row.totalKg : undefined} />
        )}
      </td>
      <td className="px-3 py-2 text-right align-top">
        {loading ? (
          <span className="text-muted-foreground">…</span>
        ) : inv ? (
          <span className="text-muted-foreground">{EM_DASH}</span>
        ) : chosenMissing ? (
          <span className="inline-flex flex-col items-end leading-tight text-muted-foreground">
            <span>{EM_DASH}</span>
            <span className="text-[11px]">nothing ticked</span>
          </span>
        ) : (
          <Figure amount={row?.chosen} kg={row && row.chosenKg !== null ? row.chosenKg : undefined} />
        )}
      </td>
      <td className="px-3 py-2 align-top">{loading ? null : note}</td>
      <td className="px-2 py-2 align-middle text-muted-foreground">
        <ChevronRight className="h-4 w-4" aria-hidden />
      </td>
    </tr>
  )
}

/**
 * The arithmetic under the table, every operand shown. Period flows, except
 * receivables (AR) which is /payments' balance as of now — said next to it.
 */
function SummaryLines({ summary: s }: { summary: NonNullable<AuditStatement['summary']> }) {
  const g = (v: number | null) => <span className="whitespace-nowrap font-medium tabular-nums">{fmtGel(v)}</span>
  return (
    <div className="space-y-1.5 border-t border-border px-4 py-3 text-sm" data-testid="statement-summary">
      <p>
        purchases {g(s.purchases)} − bank payments to suppliers {g(s.bankPaymentsToSuppliers)} ={' '}
        <span className="font-semibold">possible checks needed {g(s.possibleChecksNeeded)}</span>
      </p>
      <p>
        cash withdrawals from bank {g(s.withdrawals)} · withdrawals mapped to suppliers {g(s.withdrawalsToSuppliers)} · unresolved {g(s.withdrawalsUnresolved)}
      </p>
      <p>
        sales {g(s.sales)} − bank receipts from customers {g(s.bankReceiptsFromCustomers)} − AR {g(s.receivables)}
        <span className="text-xs text-muted-foreground"> (/payments total outstanding, as of now)</span> ={' '}
        <span className="font-semibold">cash to be received from customers {g(s.cashToReceiveFromCustomers)}</span>
      </p>
      <p>
        withdrawals {g(s.withdrawals)} + cash to be received from customers {g(s.cashToReceiveFromCustomers)} ={' '}
        <span className="font-semibold">{g(s.cashToPaySuppliers)} to be paid to suppliers as cash</span>
      </p>
    </div>
  )
}

const TITLES: Record<StatementRowKey | 'inventory', string> = {
  purchases: 'Purchases',
  bankPaymentsToSuppliers: 'Bank payments to suppliers',
  cashOutflow: 'Cash outflow',
  inventory: 'Inventory (net, on paper)',
  sales: 'Sales',
  bankInflow: 'Bank inflow (payments from customers)',
  cashInflow: 'Cash inflow (cash from customers)',
}
