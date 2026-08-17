/**
 * Map a set of bank transactions in one go (BOR-92 v4).
 *
 * The set is explicit — the ids the operator ticked, or every row of one
 * counterparty in the period — never a pattern. The dialog states the count
 * and the money before anything is written, and by default only fills each
 * row's unmapped remainder so a decision already on a row is kept; replacing
 * is a separate, named choice.
 */
import * as React from 'react'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Label } from '@/components/ui/label'
import { apiErrorMessage } from '@/lib/api-client'
import type { StatementTransaction } from '@/lib/audit-api'
import { useAuditSubgroups, useBulkMapStatement } from '@/hooks/use-audit-flows'
import { useAudit } from '../audit-context'
import { useOperatorGuard } from '../operator-picker'
import { CounterpartyField } from '../counterparty-picker'
import { fmtCount, fmtGel } from '../format'

const inputClass = 'h-8 w-full rounded-md border border-input bg-background px-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring'

export function BulkMapDialog({
  rows,
  startDate,
  endDate,
  title,
  onClose,
}: {
  /** The exact transactions to map (bank rows). */
  rows: StatementTransaction[]
  startDate: string
  endDate: string
  title: string
  onClose: () => void
}) {
  const { categories, operator } = useAudit()
  const { ready, message } = useOperatorGuard()
  const subgroups = useAuditSubgroups().data ?? []
  const mutation = useBulkMapStatement(operator)

  const [categoryCode, setCategoryCode] = React.useState('')
  const [subgroupCode, setSubgroupCode] = React.useState('')
  const [counterpartyName, setCounterpartyName] = React.useState<string | null>(null)
  const [counterpartyTin, setCounterpartyTin] = React.useState<string | null>(null)
  const [note, setNote] = React.useState('')
  const [replace, setReplace] = React.useState(false)
  const [error, setError] = React.useState<string | null>(null)
  const [done, setDone] = React.useState<{ mapped: number; skipped: number; amount: number | null } | null>(null)

  const total = rows.reduce((s, r) => s + (r.amount ?? 0), 0)
  const alreadyMapped = rows.filter((r) => r.mappingStatus && r.mappingStatus !== 'UNMAPPED' && r.mappingStatus !== 'VOIDED')
  const unresolvedTotal = rows.reduce((s, r) => s + (r.unresolvedAmount ?? r.amount ?? 0), 0)
  const category = categories.find((c) => c.code === categoryCode)
  const blocked = !ready ? message : !categoryCode ? 'Choose a group.' : rows.length === 0 ? 'Nothing selected.' : null

  const submit = async () => {
    if (blocked) return
    setError(null)
    try {
      const result = await mutation.mutateAsync({
        startDate,
        endDate,
        sourceType: 'BANK',
        sourceRowIds: rows.map((r) => r.sourceRowId ?? r.id),
        categoryCode,
        subgroupCode: subgroupCode || null,
        counterpartyTin,
        counterpartyName,
        note: note.trim() || null,
        replace,
      })
      setDone(result)
    } catch (caught) {
      setError(apiErrorMessage(caught))
    }
  }

  return (
    <Dialog open onOpenChange={(open) => (!open ? onClose() : undefined)}>
      <DialogContent aria-describedby="bulk-map-desc">
        <DialogHeader>
          <DialogTitle>Map {fmtCount(rows.length)} transaction{rows.length === 1 ? '' : 's'} — {title}</DialogTitle>
          <DialogDescription id="bulk-map-desc">
            Exactly these {fmtCount(rows.length)} bank rows, {fmtGel(total)} in total, get one split each. This is not a rule: rows you did not
            select are untouched, and nothing similar is mapped by pattern.
          </DialogDescription>
        </DialogHeader>

        {done ? (
          <div className="space-y-2 text-sm">
            <p>
              <span className="font-semibold">{fmtCount(done.mapped)}</span> mapped ({fmtGel(done.amount)}), {fmtCount(done.skipped)} skipped
              {done.skipped ? ' (already fully mapped, or outside the period)' : ''}. The statement and its lines have been refreshed.
            </p>
            <DialogFooter>
              <Button onClick={onClose}>Close</Button>
            </DialogFooter>
          </div>
        ) : (
          <div className="space-y-3">
            <div className="grid gap-2 sm:grid-cols-2">
              <div>
                <Label htmlFor="bulk-category" className="text-[11px] text-muted-foreground">
                  Group
                </Label>
                <select id="bulk-category" className={inputClass} value={categoryCode} onChange={(e) => setCategoryCode(e.target.value)}>
                  <option value="">Select group…</option>
                  {categories.map((c) => (
                    <option key={c.code} value={c.code}>
                      {c.label ?? c.code}
                      {c.builtIn ? '' : ' (custom)'}
                    </option>
                  ))}
                </select>
                {category?.description ? <p className="mt-0.5 text-[11px] text-muted-foreground">{category.description}</p> : null}
              </div>
              <div>
                <Label htmlFor="bulk-subgroup" className="text-[11px] text-muted-foreground">
                  Document status
                </Label>
                <select id="bulk-subgroup" className={inputClass} value={subgroupCode} onChange={(e) => setSubgroupCode(e.target.value)}>
                  <option value="">— none —</option>
                  {subgroups.map((s) => (
                    <option key={s.code} value={s.code}>
                      {s.label ?? s.code}
                    </option>
                  ))}
                </select>
              </div>
            </div>
            <div>
              <Label htmlFor="bulk-counterparty" className="text-[11px] text-muted-foreground">
                Counterparty (who the document / check must come from) — optional
              </Label>
              <CounterpartyField
                id="bulk-counterparty"
                className={inputClass}
                name={counterpartyName}
                tin={counterpartyTin}
                onChange={(patch) => {
                  setCounterpartyName(patch.counterpartyName)
                  setCounterpartyTin(patch.counterpartyTin)
                }}
              />
            </div>
            <div>
              <Label htmlFor="bulk-note" className="text-[11px] text-muted-foreground">
                Note (optional) — recorded on every row's history
              </Label>
              <input id="bulk-note" className={inputClass} value={note} onChange={(e) => setNote(e.target.value)} />
            </div>

            <fieldset className="space-y-1 rounded-md border border-border p-2 text-xs">
              <legend className="px-1 text-[11px] text-muted-foreground">Rows that already carry a mapping ({fmtCount(alreadyMapped.length)})</legend>
              <label className="flex items-start gap-2">
                <input type="radio" name="bulk-mode" className="mt-0.5" checked={!replace} onChange={() => setReplace(false)} />
                <span>
                  <span className="font-medium">Keep them; fill only the unmapped remainder</span> — {fmtGel(unresolvedTotal)} across the selection.
                  Rows with nothing left are skipped.
                </span>
              </label>
              <label className="flex items-start gap-2">
                <input type="radio" name="bulk-mode" className="mt-0.5" checked={replace} onChange={() => setReplace(true)} />
                <span>
                  <span className="font-medium">Replace their splits</span> — every selected row becomes one split of {fmtGel(total)} in total. Earlier
                  decisions on {fmtCount(alreadyMapped.length)} row{alreadyMapped.length === 1 ? '' : 's'} are overwritten (history is kept).
                </span>
              </label>
            </fieldset>

            {error ? <p className="text-sm text-destructive">{error}</p> : null}
            {blocked ? <p className="text-xs text-muted-foreground">{blocked}</p> : null}
            <DialogFooter>
              <Button variant="outline" onClick={onClose} disabled={mutation.isPending}>
                Cancel
              </Button>
              <Button onClick={() => void submit()} disabled={Boolean(blocked) || mutation.isPending}>
                {mutation.isPending ? 'Mapping…' : `Map ${fmtCount(rows.length)} transaction${rows.length === 1 ? '' : 's'}`}
              </Button>
            </DialogFooter>
          </div>
        )}
      </DialogContent>
    </Dialog>
  )
}
