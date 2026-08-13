/**
 * Inline editable figure — real inventory kg and real supplier debt.
 *
 * These two are inputs, not derivations: the ticket forbids inferring real
 * stock or real debt from documents. The control therefore distinguishes a
 * value somebody confirmed from a default nobody has touched, and says which
 * it is showing.
 */
import * as React from 'react'
import { Check, Pencil, X } from 'lucide-react'
import { apiErrorMessage } from '@/lib/api-client'
import { cn } from '@/lib/cn'
import { formatNumber } from '@/lib/utils'
import { EM_DASH, hasValue } from './format'
import { useOperatorGuard } from './operator-picker'

export interface EditableNumberProps {
  value: number | null | undefined
  onSave: (value: number) => Promise<unknown> | unknown
  /** Rendered after the number, e.g. "kg". */
  suffix?: string
  decimals?: number
  /** False renders the value as an unconfirmed default rather than a fact. */
  confirmed?: boolean
  label: string
  disabled?: boolean
  className?: string
}

export function EditableNumber({
  value,
  onSave,
  suffix,
  decimals = 2,
  confirmed = true,
  label,
  disabled,
  className,
}: EditableNumberProps) {
  const { ready, message } = useOperatorGuard()
  const [editing, setEditing] = React.useState(false)
  const [draft, setDraft] = React.useState('')
  const [saving, setSaving] = React.useState(false)
  const [error, setError] = React.useState<string | null>(null)

  /**
   * Only a structural reason blocks editing — a row with no product to write
   * against. A missing operator name deliberately does NOT: gating the control
   * on it produced a cell that looked editable, reported itself disabled, and
   * could never unlock, because the name commits on blur and clicking a
   * disabled button never moves focus. The name is required to *save*, which is
   * enforced in `commit` with a message that says so.
   */
  const blocked = Boolean(disabled)

  const startEditing = () => {
    if (blocked) return
    setDraft(hasValue(value) ? String(value) : '')
    setError(null)
    setEditing(true)
  }

  const commit = async () => {
    const parsed = Number(draft.replace(',', '.'))
    if (draft.trim() === '' || !Number.isFinite(parsed)) {
      setError('Enter a number')
      return
    }
    if (!ready) {
      setError(message)
      return
    }
    setSaving(true)
    setError(null)
    try {
      await onSave(parsed)
      setEditing(false)
    } catch (caught) {
      setError(apiErrorMessage(caught))
    } finally {
      setSaving(false)
    }
  }

  if (editing) {
    return (
      <span className={cn('inline-flex flex-col items-end gap-1', className)}>
        <span className="inline-flex items-center gap-1">
          <input
            autoFocus
            type="number"
            step="any"
            value={draft}
            aria-label={label}
            disabled={saving}
            onChange={(event) => setDraft(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') void commit()
              if (event.key === 'Escape') setEditing(false)
            }}
            className="h-7 w-28 rounded-md border border-input bg-background px-2 text-right text-sm tabular-nums focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          />
          <button
            type="button"
            aria-label="Save"
            disabled={saving}
            onClick={() => void commit()}
            className="rounded-md p-1 text-success hover:bg-accent disabled:opacity-50"
          >
            <Check className="h-3.5 w-3.5" />
          </button>
          <button
            type="button"
            aria-label="Cancel"
            disabled={saving}
            onClick={() => setEditing(false)}
            className="rounded-md p-1 text-muted-foreground hover:bg-accent disabled:opacity-50"
          >
            <X className="h-3.5 w-3.5" />
          </button>
        </span>
        {error ? <span className="text-[11px] text-destructive">{error}</span> : null}
      </span>
    )
  }

  const display = hasValue(value)
    ? `${formatNumber(value, decimals)}${suffix ? ` ${suffix}` : ''}`
    : EM_DASH

  return (
    <button
      type="button"
      onClick={startEditing}
      disabled={blocked}
      // Without this the accessible name is just the number, so the control
      // announces "0.00 kg" with no hint of what it edits — and any test or
      // screen reader has to fall back to the title attribute to identify it.
      aria-label={label}
      title={
        blocked
          ? 'This row has no product name to record a confirmation against.'
          : confirmed
            ? `${label} — confirmed`
            : `${label} — not yet confirmed`
      }
      className={cn(
        'inline-flex items-center gap-1.5 rounded-md border border-dashed px-2 py-0.5 text-sm tabular-nums',
        confirmed
          ? 'border-primary/50 bg-primary/5 text-foreground'
          : 'border-muted-foreground/40 text-muted-foreground',
        blocked ? 'cursor-not-allowed opacity-70' : 'hover:border-primary hover:bg-primary/10',
        className
      )}
    >
      <span>{display}</span>
      {!confirmed ? <span className="text-[10px] uppercase tracking-wide">unconfirmed</span> : null}
      <Pencil className="h-3 w-3 opacity-60" />
    </button>
  )
}
