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
import { Pencil } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { cn } from '@/lib/cn'
import { apiErrorMessage } from '@/lib/api-client'
import type { AuditSourceRow, StatementRowKey } from '@/lib/audit-api'
import { MappingDialog } from '../mapping-dialog'
import { useProductCategoryCodes, useSetProductCategory, useStatementTransactions, useVoidMapping } from '@/hooks/use-audit-flows'
import { BulkMapDialog } from './bulk-map-dialog'
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
  attribution,
  withdrawalsOnly,
  allowRecategorise = false,
}: {
  row: StatementRowKey
  startDate: string
  endDate: string
  tin?: string
  category?: string
  /** Bank rows narrowed to a party: only its own rows, or only rows attributed to it by a slice. */
  attribution?: 'DIRECT' | 'MAPPED'
  /** Bank debits with a slice in a cash-withdrawal group only. */
  withdrawalsOnly?: boolean
  /** Show the group select on document lines (Products sheet). */
  allowRecategorise?: boolean
}) {
  const query = useStatementTransactions({ row, startDate, endDate, tin, category, attribution, withdrawalsOnly })
  const [shown, setShown] = React.useState(PAGE)
  const [editing, setEditing] = React.useState<AuditSourceRow | null>(null)
  const [selected, setSelected] = React.useState<Set<string>>(() => new Set())
  const [bulk, setBulk] = React.useState<'selected' | 'all' | null>(null)
  const [unmapping, setUnmapping] = React.useState<{ id: string; label: string } | null>(null)
  const { operator } = useAudit()
  const { ready: canWrite } = useOperatorGuard()
  const voidMutation = useVoidMapping(operator)
  const rows = query.data ?? []
  const visible = rows.slice(0, shown)
  const kind = rows[0]?.kind ?? (row === 'purchases' || row === 'sales' ? 'DOCUMENT_LINE' : row === 'bankInflow' ? 'PAYMENT' : row === 'cashInflow' ? 'CASH_PAYMENT' : 'BANK_ROW')
  const isDoc = kind === 'DOCUMENT_LINE'
  const isBank = kind === 'BANK_ROW'
  const total = rows.reduce((s, t) => s + (t.amount ?? 0), 0)

  const selectedRows = rows.filter((r) => selected.has(r.id))
  const toggleSelected = (id: string, on: boolean) =>
    setSelected((s) => {
      const next = new Set(s)
      if (on) next.add(id)
      else next.delete(id)
      return next
    })

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
        {isBank ? (
          <span className="inline-flex flex-wrap items-center gap-2">
            <span>“Map…” = one row through the editor (then only this row / all like it). Tick rows to map several at once.</span>
            <Button size="sm" variant="outline" className="h-6 px-2 text-[11px]" disabled={!canWrite || selectedRows.length === 0} onClick={() => setBulk('selected')}>
              Map selected ({fmtCount(selectedRows.length)})…
            </Button>
            <Button size="sm" variant="outline" className="h-6 px-2 text-[11px]" disabled={!canWrite || rows.length === 0} onClick={() => setBulk('all')}>
              Map all {fmtCount(rows.length)} listed…
            </Button>
          </span>
        ) : null}
      </div>
      <div className="overflow-x-auto rounded border border-border bg-card">
        <table className="w-full min-w-[56rem] text-xs">
          <thead className="bg-muted/40 text-[10px] uppercase tracking-wide text-muted-foreground">
            <tr>
              {isBank ? (
                <th className="w-6 px-2 py-1">
                  <input
                    type="checkbox"
                    className="h-3.5 w-3.5"
                    aria-label="Select all loaded transactions"
                    checked={visible.length > 0 && visible.every((r) => selected.has(r.id))}
                    onChange={(e) => setSelected(e.target.checked ? new Set(visible.map((r) => r.id)) : new Set())}
                  />
                </th>
              ) : null}
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
              {isBank ? <th className="px-2 py-1 text-left font-medium">Mapped to</th> : null}
              {isBank ? (
                <th className="px-2 py-1 text-left font-medium">
                  <span className="sr-only">Edit</span>
                </th>
              ) : null}
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {visible.map((t) => (
              <tr key={`${t.kind}:${t.id}`} className="align-top hover:bg-accent/40">
                {isBank ? (
                  <td className="px-2 py-1">
                    <input type="checkbox" className="h-3.5 w-3.5" aria-label={`Select ${t.counterpartyName ?? t.id}`} checked={selected.has(t.id)} onChange={(e) => toggleSelected(t.id, e.target.checked)} />
                  </td>
                ) : null}
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
                    {t.withdrawal ? (
                      <Badge variant="outline" className="mt-0.5 h-4 px-1 text-[9px] uppercase">
                        withdrawal
                      </Badge>
                    ) : null}
                    {t.attribution === 'MAPPED' ? (
                      <Badge variant="secondary" className="ml-1 mt-0.5 h-4 px-1 text-[9px] uppercase" title="Attributed to this party by a slice on another counterparty's row">
                        mapped here
                      </Badge>
                    ) : null}
                  </td>
                ) : null}
                {isBank ? (
                  <td className="px-2 py-1">
                    {t.mappedCounterparties?.length ? (
                      <div className="max-w-[14rem]">
                        {t.mappedCounterparties.map((c) => (
                          <div key={c} className="truncate" title={c}>
                            {c}
                          </div>
                        ))}
                      </div>
                    ) : (
                      <span className="text-muted-foreground">{EM_DASH}</span>
                    )}
                  </td>
                ) : null}
                {isBank ? (
                  <td className="whitespace-nowrap px-2 py-1">
                    {t.sourceRow ? (
                      <Button size="sm" variant="outline" className="h-6 px-2 text-[11px]" onClick={() => setEditing(t.sourceRow)} aria-label={`Map ${t.counterpartyName ?? t.id}`}>
                        <Pencil className="mr-1 h-3 w-3" /> Map…
                      </Button>
                    ) : null}
                    {t.mappingStatus && t.mappingStatus !== 'UNMAPPED' && t.mappingStatus !== 'VOIDED' && t.sourceType && t.sourceRowId ? (
                      <Button
                        size="sm"
                        variant="ghost"
                        className="ml-1 h-6 px-2 text-[11px] text-destructive"
                        disabled={!canWrite}
                        aria-label={`Unmap ${t.counterpartyName ?? t.id}`}
                        onClick={() => setUnmapping({ id: `${t.sourceType}__${t.sourceRowId}`, label: `${fmtDate(t.date)} · ${t.counterpartyName ?? t.id} · ${fmtGel(t.amount)}` })}
                      >
                        Unmap
                      </Button>
                    ) : null}
                  </td>
                ) : null}
              </tr>
            ))}
          </tbody>
          <tfoot className="border-t-2 border-border bg-muted/40 font-semibold">
            <tr data-testid="transactions-totals">
              {isBank ? <td className="px-2 py-1" /> : null}
              <td className="px-2 py-1" colSpan={isDoc ? 3 : 2}>
                Total <span className="font-normal text-muted-foreground">{fmtCount(rows.length)} rows{visible.length < rows.length ? ` (${fmtCount(visible.length)} shown)` : ''}</span>
              </td>
              {isDoc ? <td className="px-2 py-1" /> : null}
              {isDoc ? <td className="px-2 py-1" /> : null}
              {isDoc ? (
                <td className="whitespace-nowrap px-2 py-1 text-right tabular-nums" title="Sum of the quantities as printed; mixed units are not converted">
                  {rows.reduce((s, t) => s + (t.quantityKg ?? 0), 0).toLocaleString('ka-GE', { maximumFractionDigits: 2 })}
                </td>
              ) : null}
              {!isDoc ? <td className="px-2 py-1" /> : null}
              {!isDoc ? <td className="px-2 py-1" /> : null}
              <td className="whitespace-nowrap px-2 py-1 text-right tabular-nums">{fmtGel(total)}</td>
              {isDoc || isBank ? (
                <td className="px-2 py-1 text-[10px] font-normal text-muted-foreground">
                  {isBank ? `unmapped ${fmtGel(rows.reduce((s, t) => s + (t.unresolvedAmount ?? 0), 0))}` : ''}
                </td>
              ) : null}
              {isBank ? <td className="px-2 py-1" /> : null}
              {isBank ? <td className="px-2 py-1" /> : null}
            </tr>
          </tfoot>
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
      {/* The same two-step editor the workbench uses: build the mapping, then only-this / all-like-it. */}
      <MappingDialog row={editing} onClose={() => setEditing(null)} />
      {bulk ? (
        <BulkMapDialog
          rows={bulk === 'selected' ? selectedRows : rows}
          startDate={startDate}
          endDate={endDate}
          title={bulk === 'selected' ? 'selected transactions' : 'all listed transactions'}
          onClose={() => {
            setBulk(null)
            setSelected(new Set())
          }}
        />
      ) : null}
      {unmapping ? (
        <UnmapDialog
          label={unmapping.label}
          pending={voidMutation.isPending}
          onCancel={() => setUnmapping(null)}
          onConfirm={async (reason) => {
            await voidMutation.mutateAsync({ id: unmapping.id, reason })
            setUnmapping(null)
          }}
        />
      ) : null}
    </div>
  )
}

/** Voiding keeps the history; the row returns to the unmapped queue. A reason is required, as in the workbench. */
function UnmapDialog({ label, pending, onCancel, onConfirm }: { label: string; pending: boolean; onCancel: () => void; onConfirm: (reason: string) => Promise<void> }) {
  const [reason, setReason] = React.useState('')
  const [error, setError] = React.useState<string | null>(null)
  return (
    <Dialog open onOpenChange={(open) => (!open ? onCancel() : undefined)}>
      <DialogContent aria-describedby="unmap-desc">
        <DialogHeader>
          <DialogTitle>Unmap this transaction?</DialogTitle>
          <DialogDescription id="unmap-desc">
            {label}. The mapping is voided, not deleted: its history stays, and the row goes back to unmapped — every total it fed moves
            accordingly.
          </DialogDescription>
        </DialogHeader>
        <label className="text-xs text-muted-foreground">
          Reason (required)
          <input className="mt-1 h-8 w-full rounded-md border border-input bg-background px-2 text-sm" value={reason} onChange={(e) => setReason(e.target.value)} aria-label="Unmap reason" />
        </label>
        {error ? <p className="text-sm text-destructive">{error}</p> : null}
        <DialogFooter>
          <Button variant="outline" onClick={onCancel} disabled={pending}>
            Cancel
          </Button>
          <Button
            variant="destructive"
            disabled={pending || !reason.trim()}
            onClick={() => {
              setError(null)
              onConfirm(reason.trim()).catch((caught) => setError(apiErrorMessage(caught)))
            }}
          >
            {pending ? 'Unmapping…' : 'Unmap'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
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
