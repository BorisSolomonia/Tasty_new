/**
 * Classify one immutable source row (BOR-89 §6).
 *
 * Two rules are load-bearing here:
 *
 *  - the unresolved remainder is derived on every keystroke as
 *    `|source amount| − Σ split amounts` and is always on screen. It is never
 *    stored, so it cannot silently disagree with the splits;
 *  - over-allocation is blocked. Splits that sum past the source amount would
 *    mean the audit had invented money, so save is refused while that holds.
 *
 * Saving never touches the source row. It writes a separate mapping record.
 */
import * as React from 'react'
import { Plus, Save, Trash2, Undo2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Separator } from '@/components/ui/separator'
import { apiErrorMessage } from '@/lib/api-client'
import { cn } from '@/lib/cn'
import type {
  AuditMapping,
  AuditMappingSplit,
  AuditMappingStatus,
  AuditSourceRow,
} from '@/lib/audit-api'
import { useSaveMapping, useVoidMapping } from '@/hooks/use-audit-flows'
import { useAudit } from './audit-context'
import { useOperatorGuard } from './operator-picker'
import { StatusBadge } from './status-badge'
import { ChangeHistory } from './change-history'
import { EM_DASH, fmtDate, fmtGel, fmtKgSigned, fmtText, hasValue } from './format'
import { sourceRowKey } from './source-row-table'

const TOLERANCE = 0.005

const inputClass =
  'h-8 w-full rounded-md border border-input bg-background px-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring'

function emptySplit(): AuditMappingSplit {
  return {
    categoryCode: null,
    counterpartyName: null,
    counterpartyTin: null,
    productName: null,
    amount: null,
    quantityKg: null,
    note: null,
  }
}

function toNumberOrNull(raw: string): number | null {
  const trimmed = raw.trim()
  if (!trimmed) return null
  const parsed = Number(trimmed.replace(',', '.'))
  return Number.isFinite(parsed) ? parsed : null
}

export function MappingEditor({
  row,
  onSaved,
  className,
  showHistory = true,
}: {
  row: AuditSourceRow
  onSaved?: () => void
  className?: string
  showHistory?: boolean
}) {
  const { categories, operator } = useAudit()
  const { ready, message } = useOperatorGuard()
  const saveMutation = useSaveMapping(operator)
  const voidMutation = useVoidMapping(operator)

  const rowKey = sourceRowKey(row)
  const [splits, setSplits] = React.useState<AuditMappingSplit[]>(() => row.mapping?.splits ?? [])
  const [note, setNote] = React.useState<string>(row.mapping?.note ?? '')
  const [voidReason, setVoidReason] = React.useState('')
  const [voiding, setVoiding] = React.useState(false)
  const [error, setError] = React.useState<string | null>(null)
  const [saved, setSaved] = React.useState(false)

  // Reset whenever a different source row is selected.
  React.useEffect(() => {
    setSplits(row.mapping?.splits ?? [])
    setNote(row.mapping?.note ?? '')
    setVoidReason('')
    setVoiding(false)
    setError(null)
    setSaved(false)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rowKey])

  const sourceAmount = hasValue(row.amount) ? row.amount : row.mapping?.sourceAmount ?? null
  // Splits are always positive; a debit row carries its direction, not a sign.
  const allocatable = hasValue(sourceAmount) ? Math.abs(sourceAmount) : null
  const allocated = splits.reduce((sum, split) => sum + (split.amount ?? 0), 0)
  const remainder = allocatable === null ? null : allocatable - allocated
  const overAllocated = remainder !== null && remainder < -TOLERANCE
  const fullyAllocated = remainder !== null && Math.abs(remainder) <= TOLERANCE

  const nextStatus = ((): AuditMappingStatus => {
    if (splits.length === 0) return 'UNMAPPED'
    const previous = row.mapping?.status
    if (fullyAllocated) {
      return previous === 'AUTO_MAPPED' || previous === 'SUGGESTED' ? 'OVERRIDDEN' : 'MANUALLY_MAPPED'
    }
    return 'PARTIALLY_MAPPED'
  })()

  // The backend rejects a split with a non-positive amount or an unknown
  // category, so the same conditions block the button rather than being
  // discovered as a 400 after a round trip.
  const incompleteSplit = splits.some((split) => !split.categoryCode || !hasValue(split.amount))
  const nonPositiveSplit = splits.some((split) => hasValue(split.amount) && split.amount <= 0)
  const blockedReason = !ready
    ? message
    : allocatable === null
      ? 'This row has no source amount, so it cannot be allocated.'
      : overAllocated
        ? 'Splits exceed the source amount. Reduce a split before saving.'
        : incompleteSplit
          ? 'Every split needs a category and an amount.'
          : nonPositiveSplit
            ? 'A split amount must be greater than zero. Remove the split instead.'
            : null

  const updateSplit = (index: number, patch: Partial<AuditMappingSplit>) => {
    setSaved(false)
    setSplits((current) => current.map((split, i) => (i === index ? { ...split, ...patch } : split)))
  }

  const handleSave = async () => {
    if (blockedReason) return
    setError(null)
    const payload: AuditMapping = {
      id: row.mapping?.id ?? null,
      sourceType: row.sourceType,
      sourceRowId: row.sourceRowId,
      sourceAmount,
      status: nextStatus,
      splits,
      // Derived server-side from the splits; never sent as a stored figure.
      unresolvedAmount: null,
      linkedSourceRows: row.mapping?.linkedSourceRows ?? null,
      suggestionReason: row.mapping?.suggestionReason ?? null,
      confidence: row.mapping?.confidence ?? null,
      note: note.trim() ? note.trim() : null,
      createdBy: row.mapping?.createdBy ?? null,
      updatedBy: null,
      createdAt: row.mapping?.createdAt ?? null,
      updatedAt: null,
    }
    try {
      await saveMutation.mutateAsync(payload)
      setSaved(true)
      onSaved?.()
    } catch (caught) {
      setError(apiErrorMessage(caught))
    }
  }

  const handleVoid = async () => {
    const id = row.mapping?.id
    if (!id || !voidReason.trim()) return
    setError(null)
    try {
      await voidMutation.mutateAsync({ id, reason: voidReason.trim() })
      setVoiding(false)
      setVoidReason('')
      onSaved?.()
    } catch (caught) {
      setError(apiErrorMessage(caught))
    }
  }

  return (
    <div className={cn('space-y-4', className)}>
      {/* ---------------------------------------------------------------- */}
      {/* The source row, exactly as imported.                              */}
      {/* ---------------------------------------------------------------- */}
      <div className="rounded-md border border-border bg-muted/40 p-3">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            {row.sourceType ?? 'SOURCE'} · {fmtText(row.reference)}
          </span>
          <StatusBadge status={row.status} className="ml-auto" />
        </div>
        <dl className="mt-2 grid grid-cols-2 gap-x-4 gap-y-1 text-xs sm:grid-cols-3">
          <Field label="Date" value={fmtDate(row.date)} />
          <Field label="Direction" value={fmtText(row.direction)} />
          <Field label="Amount" value={fmtGel(row.amount)} />
          <Field label="Quantity" value={fmtKgSigned(row.quantityKg)} />
          <Field label="Counterparty" value={fmtText(row.counterpartyName)} />
          <Field label="Product" value={fmtText(row.productName)} />
        </dl>
        <p className="mt-2 break-words text-xs text-muted-foreground">
          {fmtText(row.description)}
          {row.additionalInformation ? ` · ${row.additionalInformation}` : ''}
        </p>
        <p className="mt-2 text-[11px] italic text-muted-foreground">
          Source records are immutable. Mapping creates an audit-layer classification only.
        </p>
      </div>

      {/* ---------------------------------------------------------------- */}
      {/* Splits.                                                           */}
      {/* ---------------------------------------------------------------- */}
      <div className="space-y-3">
        {splits.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            No allocation yet — the row&apos;s full amount is unresolved.
          </p>
        ) : null}

        {splits.map((split, index) => (
          <div key={index} className="rounded-md border border-border p-3">
            <div className="grid gap-2 sm:grid-cols-[minmax(0,1.2fr)_minmax(0,1.2fr)_minmax(0,0.8fr)_auto]">
              <div>
                <Label className="text-[11px] text-muted-foreground">Category</Label>
                <select
                  className={inputClass}
                  value={split.categoryCode ?? ''}
                  onChange={(event) =>
                    updateSplit(index, { categoryCode: event.target.value || null })
                  }
                >
                  <option value="">Select category…</option>
                  {categories.map((category) => (
                    <option key={category.code} value={category.code}>
                      {category.label ?? category.code}
                      {category.builtIn ? '' : ' (custom)'}
                    </option>
                  ))}
                </select>
              </div>
              <div>
                <Label className="text-[11px] text-muted-foreground">Counterparty</Label>
                <input
                  className={inputClass}
                  value={split.counterpartyName ?? ''}
                  placeholder="Name as written in the source"
                  onChange={(event) =>
                    updateSplit(index, { counterpartyName: event.target.value || null })
                  }
                />
              </div>
              <div>
                <Label className="text-[11px] text-muted-foreground">Amount</Label>
                <input
                  className={cn(inputClass, 'text-right tabular-nums')}
                  type="number"
                  step="any"
                  value={split.amount ?? ''}
                  onChange={(event) => updateSplit(index, { amount: toNumberOrNull(event.target.value) })}
                />
              </div>
              <div className="flex items-end">
                <Button
                  type="button"
                  size="sm"
                  variant="ghost"
                  className="h-8 px-2 text-destructive"
                  aria-label="Remove split"
                  onClick={() => {
                    setSaved(false)
                    setSplits((current) => current.filter((_, i) => i !== index))
                  }}
                >
                  <Trash2 className="h-4 w-4" />
                </Button>
              </div>
            </div>

            <div className="mt-2 grid gap-2 sm:grid-cols-[minmax(0,1fr)_minmax(0,0.6fr)_minmax(0,1.4fr)]">
              <div>
                <Label className="text-[11px] text-muted-foreground">Product (optional)</Label>
                <input
                  className={inputClass}
                  value={split.productName ?? ''}
                  onChange={(event) => updateSplit(index, { productName: event.target.value || null })}
                />
              </div>
              <div>
                <Label className="text-[11px] text-muted-foreground">Qty kg (optional)</Label>
                <input
                  className={cn(inputClass, 'text-right tabular-nums')}
                  type="number"
                  step="any"
                  value={split.quantityKg ?? ''}
                  onChange={(event) =>
                    updateSplit(index, { quantityKg: toNumberOrNull(event.target.value) })
                  }
                />
              </div>
              <div>
                <Label className="text-[11px] text-muted-foreground">Split note (optional)</Label>
                <input
                  className={inputClass}
                  value={split.note ?? ''}
                  onChange={(event) => updateSplit(index, { note: event.target.value || null })}
                />
              </div>
            </div>

            {remainder !== null && remainder > TOLERANCE ? (
              <button
                type="button"
                className="mt-2 text-[11px] text-primary underline-offset-2 hover:underline"
                onClick={() =>
                  updateSplit(index, { amount: Number(((split.amount ?? 0) + remainder).toFixed(2)) })
                }
              >
                Fill remainder into this split ({fmtGel(remainder)})
              </button>
            ) : null}
          </div>
        ))}

        <Button
          type="button"
          size="sm"
          variant="outline"
          onClick={() => {
            setSaved(false)
            setSplits((current) => [...current, emptySplit()])
          }}
        >
          <Plus className="mr-1 h-4 w-4" />
          Add split
        </Button>
      </div>

      <Separator />

      {/* ---------------------------------------------------------------- */}
      {/* The remainder — always visible, blocking when negative.           */}
      {/* ---------------------------------------------------------------- */}
      <div
        className={cn(
          'rounded-md border p-3',
          overAllocated
            ? 'border-destructive bg-destructive/10'
            : fullyAllocated
              ? 'border-success bg-success/10'
              : 'border-warning bg-warning/10'
        )}
      >
        <div className="flex items-center justify-between gap-3 text-sm">
          <span className="font-medium">Unresolved remainder</span>
          <span className="font-bold tabular-nums">
            {remainder === null ? EM_DASH : fmtGel(remainder)}
          </span>
        </div>
        <p className="mt-1 text-xs text-muted-foreground">
          {allocatable === null
            ? 'This row carries no source amount, so no remainder can be derived.'
            : `|source ${fmtGel(allocatable)}| − Σ splits ${fmtGel(allocated)}. Derived on every change, never stored.`}
        </p>
        {overAllocated ? (
          <p className="mt-1 text-xs font-semibold text-destructive">
            Splits exceed the source amount by {fmtGel(Math.abs(remainder ?? 0))}. Saving is blocked.
          </p>
        ) : null}
        {/* A div, not a p: Badge renders a block-level element, and a browser
            auto-closes the paragraph around it. */}
        <div className="mt-1 flex items-center gap-1 text-xs text-muted-foreground">
          <span>Status on save:</span>
          <StatusBadge status={nextStatus} />
        </div>
      </div>

      <div>
        <Label htmlFor="mapping-note" className="text-xs text-muted-foreground">
          Note — why this mapping says what it says
        </Label>
        <Input
          id="mapping-note"
          value={note}
          className="mt-1 h-9"
          onChange={(event) => {
            setSaved(false)
            setNote(event.target.value)
          }}
        />
      </div>

      {error ? <p className="text-sm text-destructive">{error}</p> : null}
      {blockedReason ? <p className="text-xs text-muted-foreground">{blockedReason}</p> : null}
      {saved ? <p className="text-xs text-success">Mapping saved and logged.</p> : null}

      <div className="flex flex-wrap items-center gap-2">
        <Button
          type="button"
          onClick={() => void handleSave()}
          disabled={Boolean(blockedReason) || saveMutation.isPending}
        >
          <Save className="mr-1 h-4 w-4" />
          {saveMutation.isPending ? 'Saving…' : 'Save mapping'}
        </Button>

        {row.mapping?.id && !voiding ? (
          <Button
            type="button"
            variant="outline"
            disabled={!ready}
            onClick={() => setVoiding(true)}
          >
            <Undo2 className="mr-1 h-4 w-4" />
            Void mapping
          </Button>
        ) : null}
      </div>

      {voiding ? (
        <div className="rounded-md border border-border p-3">
          <Label htmlFor="void-reason" className="text-xs text-muted-foreground">
            Reason for voiding — kept permanently in the change log
          </Label>
          <Input
            id="void-reason"
            value={voidReason}
            className="mt-1 h-9"
            onChange={(event) => setVoidReason(event.target.value)}
          />
          <div className="mt-2 flex gap-2">
            <Button
              type="button"
              variant="destructive"
              size="sm"
              disabled={!voidReason.trim() || voidMutation.isPending}
              onClick={() => void handleVoid()}
            >
              {voidMutation.isPending ? 'Voiding…' : 'Confirm void'}
            </Button>
            <Button type="button" variant="ghost" size="sm" onClick={() => setVoiding(false)}>
              Cancel
            </Button>
          </div>
        </div>
      ) : null}

      {showHistory ? (
        <>
          <Separator />
          <ChangeHistory
            sourceType={row.sourceType}
            sourceRowId={row.sourceRowId}
            fallback={row.history}
          />
        </>
      ) : null}
    </div>
  )
}

function Field({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0">
      <dt className="text-[11px] text-muted-foreground">{label}</dt>
      <dd className="truncate font-medium" title={value}>
        {value}
      </dd>
    </div>
  )
}
