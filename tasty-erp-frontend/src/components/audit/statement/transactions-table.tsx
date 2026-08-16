/**
 * The transactions behind one statement figure (BOR-92 v2), in one table for
 * document lines, bank rows and payments alike.
 *
 * On document lines a product's group can be changed. That is not a per-line
 * edit: one product name carries one group everywhere (purchases and sales,
 * /audit and /audit-control), so the change goes through a confirmation that
 * says exactly that and how many of the loaded lines carry the name.
 */
import * as React from 'react'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { cn } from '@/lib/cn'
import { apiErrorMessage } from '@/lib/api-client'
import type { StatementRowKey } from '@/lib/audit-api'
import { useProductCategoryCodes, useSetProductCategory, useStatementTransactions } from '@/hooks/use-audit-flows'
import { CATEGORY_LABELS } from '@/features/audit-control/labels'
import { useAudit } from '../audit-context'
import { useOperatorGuard } from '../operator-picker'
import { EM_DASH, fmtCount, fmtDate, fmtGel, fmtKg, fmtText } from '../format'

const PAGE = 200

function label(code: string | null | undefined): string {
  if (!code) return EM_DASH
  return CATEGORY_LABELS[code] ?? code
}

export function TransactionsTable({
  row,
  startDate,
  endDate,
  tin,
  category,
  allowRecategorise = false,
}: {
  row: StatementRowKey
  startDate: string
  endDate: string
  tin?: string
  category?: string
  /** Show the group select on document lines (Products sheet). */
  allowRecategorise?: boolean
}) {
  const query = useStatementTransactions({ row, startDate, endDate, tin, category })
  const [shown, setShown] = React.useState(PAGE)
  const rows = query.data ?? []
  const visible = rows.slice(0, shown)
  const kind = rows[0]?.kind ?? (row === 'purchases' || row === 'sales' ? 'DOCUMENT_LINE' : row === 'bankInflow' ? 'PAYMENT' : row === 'cashInflow' ? 'CASH_PAYMENT' : 'BANK_ROW')
  const isDoc = kind === 'DOCUMENT_LINE'
  const isBank = kind === 'BANK_ROW'
  const total = rows.reduce((s, t) => s + (t.amount ?? 0), 0)

  if (query.isLoading) return <p className="text-xs text-muted-foreground">Loading transactions…</p>
  if (query.isError) return <p className="text-xs text-destructive">Transactions did not load: {apiErrorMessage(query.error)}</p>
  if (!rows.length) return <p className="text-xs text-muted-foreground">No transactions behind this figure.</p>

  return (
    <div className="space-y-1">
      <div className="flex flex-wrap items-center justify-between gap-2 text-[11px] text-muted-foreground">
        <span>
          {fmtCount(rows.length)} transaction{rows.length === 1 ? '' : 's'} · {fmtGel(total)}
          {rows.length >= 5000 ? ' · capped at 5000 — narrow the period' : ''}
        </span>
        {allowRecategorise ? <span>Change a line's group to correct that product everywhere.</span> : null}
      </div>
      <div className="overflow-x-auto rounded border border-border bg-card">
        <table className="w-full min-w-[56rem] text-xs">
          <thead className="bg-muted/40 text-[10px] uppercase tracking-wide text-muted-foreground">
            <tr>
              <th className="px-2 py-1 text-left font-medium">Date</th>
              {isDoc ? <th className="px-2 py-1 text-left font-medium">Waybill</th> : null}
              <th className="px-2 py-1 text-left font-medium">Counterparty</th>
              {isDoc ? <th className="px-2 py-1 text-left font-medium">Product</th> : null}
              {isDoc ? <th className="px-2 py-1 text-left font-medium">Group</th> : null}
              {isDoc ? <th className="px-2 py-1 text-right font-medium">Qty</th> : null}
              {!isDoc ? <th className="px-2 py-1 text-left font-medium">Description</th> : null}
              {!isDoc ? <th className="px-2 py-1 text-left font-medium">{isBank ? 'Reference' : 'Source'}</th> : null}
              <th className="px-2 py-1 text-right font-medium">₾</th>
              {isDoc || isBank ? <th className="px-2 py-1 text-left font-medium">Mapping</th> : null}
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {visible.map((t) => (
              <tr key={`${t.kind}:${t.id}`} className="align-top hover:bg-accent/40">
                <td className="whitespace-nowrap px-2 py-1 tabular-nums">{fmtDate(t.date)}</td>
                {isDoc ? <td className="whitespace-nowrap px-2 py-1 font-mono">{fmtText(t.waybillId)}</td> : null}
                <td className="px-2 py-1">
                  <div className="max-w-[16rem] truncate" title={t.counterpartyName ?? undefined}>
                    {t.counterpartyName ?? t.counterpartyTin ?? EM_DASH}
                  </div>
                  {t.counterpartyTin && t.counterpartyName ? <div className="text-[10px] text-muted-foreground">{t.counterpartyTin}</div> : null}
                </td>
                {isDoc ? (
                  <td className="px-2 py-1">
                    <div className="max-w-[18rem] truncate" title={t.productName ?? undefined}>
                      {fmtText(t.productName)}
                    </div>
                  </td>
                ) : null}
                {isDoc ? (
                  <td className="px-2 py-1">
                    {allowRecategorise && t.productName ? (
                      <GroupSelect productName={t.productName} current={t.category} sameNameCount={rows.filter((r) => r.productName === t.productName).length} />
                    ) : (
                      label(t.category)
                    )}
                  </td>
                ) : null}
                {isDoc ? (
                  <td className="whitespace-nowrap px-2 py-1 text-right tabular-nums">
                    {t.quantityKg === null ? EM_DASH : `${fmtKg(t.quantityKg, 2).replace(/ kg$/, '')} ${t.unit ?? ''}`.trim()}
                  </td>
                ) : null}
                {!isDoc ? (
                  <td className="px-2 py-1">
                    <div className="max-w-[24rem] truncate" title={t.description ?? undefined}>
                      {fmtText(t.description)}
                    </div>
                  </td>
                ) : null}
                {!isDoc ? <td className="max-w-[10rem] truncate px-2 py-1 font-mono text-[10px]">{fmtText(isBank ? t.reference : t.source)}</td> : null}
                <td className="whitespace-nowrap px-2 py-1 text-right tabular-nums">{fmtGel(t.amount)}</td>
                {isDoc || isBank ? (
                  <td className="px-2 py-1">
                    {t.mappingSummary ? (
                      <div className="max-w-[20rem]" title={t.mappingSummary}>
                        <span className="line-clamp-2">{t.mappingSummary}</span>
                      </div>
                    ) : (
                      <span className="text-muted-foreground">{t.mappingStatus === 'UNMAPPED' || !t.mappingStatus ? 'unmapped' : t.mappingStatus.toLowerCase().replace(/_/g, ' ')}</span>
                    )}
                    {t.unresolvedAmount != null && t.unresolvedAmount > 0.005 && t.mappingSummary ? (
                      <div className="text-[10px] text-destructive">unmapped {fmtGel(t.unresolvedAmount)}</div>
                    ) : null}
                  </td>
                ) : null}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {rows.length > shown ? (
        <div className="text-[11px] text-muted-foreground">
          Showing {fmtCount(shown)} of {fmtCount(rows.length)}.{' '}
          <Button size="sm" variant="ghost" className="h-6 px-2" onClick={() => setShown((s) => s + PAGE)}>
            Show more
          </Button>
        </div>
      ) : null}
    </div>
  )
}

/**
 * The group select on a document line. Choosing a different group opens the
 * confirmation; nothing is written until it is accepted.
 */
function GroupSelect({ productName, current, sameNameCount }: { productName: string; current: string | null; sameNameCount: number }) {
  const { operator } = useAudit()
  const { ready, message } = useOperatorGuard()
  const codes = useProductCategoryCodes().data ?? Object.keys(CATEGORY_LABELS)
  const mutation = useSetProductCategory(operator)
  const [pending, setPending] = React.useState<string | null>(null)
  const [error, setError] = React.useState<string | null>(null)

  const confirm = async () => {
    if (!pending) return
    setError(null)
    try {
      await mutation.mutateAsync({ productName, category: pending })
      setPending(null)
    } catch (caught) {
      setError(apiErrorMessage(caught))
    }
  }

  return (
    <>
      <select
        className={cn('h-7 max-w-[10rem] rounded border border-input bg-background px-1 text-xs', !ready && 'opacity-70')}
        value={current ?? ''}
        aria-label={`Group for ${productName}`}
        title={ready ? 'Change the group of this product everywhere' : message}
        disabled={!ready || mutation.isPending}
        onChange={(e) => {
          if (e.target.value && e.target.value !== current) setPending(e.target.value)
        }}
      >
        {!current || !codes.includes(current) ? <option value="">{label(current)}</option> : null}
        {codes.map((c) => (
          <option key={c} value={c}>
            {label(c)}
          </option>
        ))}
      </select>

      {pending ? (
        <Dialog open onOpenChange={(open) => (!open ? setPending(null) : undefined)}>
          <DialogContent aria-describedby="recategorise-desc">
            <DialogHeader>
              <DialogTitle>Move “{productName}” to {label(pending)}?</DialogTitle>
              <DialogDescription id="recategorise-desc">
                This is a rule for the product, not for one line. Every document line whose product name is exactly “{productName}” —
                purchases and sales, on /audit and /audit-control — will count under {label(pending)} instead of {label(current)}. In the
                list you are looking at, {fmtCount(sameNameCount)} line{sameNameCount === 1 ? ' carries' : 's carry'} this name. Lines with
                any other spelling are not touched.
              </DialogDescription>
            </DialogHeader>
            {error ? <p className="text-sm text-destructive">{error}</p> : null}
            <DialogFooter>
              <Button variant="outline" onClick={() => setPending(null)} disabled={mutation.isPending}>
                Cancel
              </Button>
              <Button onClick={() => void confirm()} disabled={mutation.isPending}>
                {mutation.isPending ? 'Saving…' : `Move to ${label(pending)}`}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      ) : null}
    </>
  )
}
