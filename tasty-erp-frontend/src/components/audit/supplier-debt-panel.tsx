/**
 * Real outstanding supplier debt — an input, never a derivation (§4B).
 *
 * The ticket forbids `goods = bank payments + withdrawals + debt`, because a
 * withdrawal does not prove a supplier was settled. So the per-supplier figures
 * below are typed in by a person, and the coverage control reads them.
 */
import * as React from 'react'
import { Plus } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { apiErrorMessage } from '@/lib/api-client'
import { useSaveSupplierDebt, useSupplierDebt } from '@/hooks/use-audit-flows'
import { useAudit } from './audit-context'
import { CollapsiblePanel } from './collapsible-panel'
import { EditableNumber } from './editable-number'
import { useOperatorGuard } from './operator-picker'
import { fmtCount, fmtDate, fmtGel, fmtText } from './format'

const inputClass =
  'h-8 w-full rounded-md border border-input bg-background px-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring'

export function SupplierDebtPanel() {
  const { operator } = useAudit()
  const { ready, message } = useOperatorGuard()
  const query = useSupplierDebt()
  const mutation = useSaveSupplierDebt(operator)

  const [newName, setNewName] = React.useState('')
  const [newTin, setNewTin] = React.useState('')
  const [newAmount, setNewAmount] = React.useState('')
  const [error, setError] = React.useState<string | null>(null)

  const rows = query.data ?? []
  const total = rows.reduce((sum, row) => sum + (row.outstandingAmount ?? 0), 0)

  const addSupplier = async () => {
    const amount = Number(newAmount.replace(',', '.'))
    if (!newName.trim() || !Number.isFinite(amount)) {
      setError('A supplier name and a numeric amount are required.')
      return
    }
    setError(null)
    try {
      await mutation.mutateAsync({
        id: null,
        supplierName: newName.trim(),
        supplierTin: newTin.trim() || null,
        outstandingAmount: amount,
        note: null,
        updatedBy: null,
        updatedAt: null,
      })
      setNewName('')
      setNewTin('')
      setNewAmount('')
    } catch (caught) {
      setError(apiErrorMessage(caught))
    }
  }

  if (query.isLoading) return <Skeleton className="h-24 w-full" />

  return (
    <div className="space-y-3">
      {query.isError ? (
        <p className="text-xs text-destructive">
          Supplier debt could not be loaded. The coverage control below is therefore missing an
          input — read it as incomplete, not as covered.
        </p>
      ) : null}

      {rows.length === 0 && !query.isError ? (
        <p className="text-xs text-muted-foreground">
          No supplier debt has been asserted yet. The coverage control reads this as zero debt,
          which is a claim somebody has to make deliberately.
        </p>
      ) : null}

      {/*
        The per-counterparty table is collapsed; Σ asserted debt stays on the
        header, because that is the figure the coverage control above reads.
      */}
      {rows.length > 0 ? (
        <CollapsiblePanel
          title="Per-supplier asserted debt"
          summary={`${fmtCount(rows.length)} suppliers · ${fmtGel(total)} asserted`}
        >
        <div className="overflow-x-auto">
          <table className="w-full text-[11px]">
            <thead>
              <tr className="border-b border-border text-left text-muted-foreground">
                <th className="py-1 pr-2 font-semibold">Supplier</th>
                <th className="py-1 pr-2 font-semibold">Last updated</th>
                <th className="py-1 text-right font-semibold">Outstanding</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.id ?? row.supplierName ?? ''} className="border-b border-border/70">
                  <td className="py-1 pr-2">
                    <div className="font-medium">{fmtText(row.supplierName)}</div>
                    {row.supplierTin ? (
                      <div className="text-[11px] text-muted-foreground">{row.supplierTin}</div>
                    ) : null}
                  </td>
                  <td className="py-1 pr-2 text-muted-foreground">
                    {fmtDate(row.updatedAt)}
                    {row.updatedBy ? ` · ${row.updatedBy}` : ''}
                  </td>
                  <td className="py-1 text-right">
                    <EditableNumber
                      value={row.outstandingAmount}
                      label={`Outstanding debt to ${row.supplierName ?? 'supplier'}`}
                      onSave={(outstandingAmount) =>
                        mutation.mutateAsync({ ...row, outstandingAmount, updatedBy: null })
                      }
                    />
                  </td>
                </tr>
              ))}
              <tr>
                <td className="py-1 pr-2 font-semibold" colSpan={2}>
                  Σ asserted debt
                </td>
                <td className="py-1 text-right font-semibold tabular-nums">{fmtGel(total)}</td>
              </tr>
            </tbody>
          </table>
        </div>
        </CollapsiblePanel>
      ) : null}

      <div className="grid gap-2 sm:grid-cols-[minmax(0,1.4fr)_minmax(0,0.8fr)_minmax(0,0.8fr)_auto]">
        <input
          className={inputClass}
          placeholder="Supplier name"
          value={newName}
          onChange={(event) => setNewName(event.target.value)}
        />
        <input
          className={inputClass}
          placeholder="TIN (optional)"
          value={newTin}
          onChange={(event) => setNewTin(event.target.value)}
        />
        <input
          className={`${inputClass} text-right tabular-nums`}
          placeholder="Amount"
          type="number"
          step="any"
          value={newAmount}
          onChange={(event) => setNewAmount(event.target.value)}
        />
        <Button
          type="button"
          size="sm"
          variant="outline"
          className="h-8"
          disabled={!ready || mutation.isPending}
          title={ready ? undefined : message}
          onClick={() => void addSupplier()}
        >
          <Plus className="mr-1 h-3.5 w-3.5" />
          Add
        </Button>
      </div>

      {error ? <p className="text-xs text-destructive">{error}</p> : null}
    </div>
  )
}
