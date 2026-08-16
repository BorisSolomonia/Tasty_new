/**
 * Counterparty picker for mapping splits (BOR-92).
 *
 * A split's counterparty answers "who does this document / check / act have to
 * come from?" — a supplier, a customer (returns), or anyone else typed by hand.
 * The field stays free text (the source spelling is legitimate) but offers the
 * known suppliers (sellers on purchase documents in the audit period) and the
 * customer register as suggestions; picking one also fills the TIN so the
 * overview can attribute the slice by identity instead of by spelling.
 */
import * as React from 'react'
import { useQuery } from '@tanstack/react-query'
import { configApi } from '@/lib/api-client'
import { useAuditStatement } from '@/hooks/use-audit-flows'
import { useAudit } from './audit-context'

export interface CounterpartyOption {
  tin: string | null
  name: string
  kind: 'supplier' | 'customer'
}

export function useCounterpartyDirectory(): { options: CounterpartyOption[]; loading: boolean } {
  const { filters, operator } = useAudit()
  const statement = useAuditStatement({ startDate: filters.startDate, endDate: filters.endDate, operator: operator || undefined })
  const customers = useQuery({
    queryKey: ['config', 'customers'],
    queryFn: () => configApi.getCustomers(),
    staleTime: 1000 * 60 * 30,
    retry: 1,
  })
  const options = React.useMemo(() => {
    const out: CounterpartyOption[] = []
    const seen = new Set<string>()
    const supplierParties = [
      ...(statement.data?.purchases.parties ?? []),
      ...(statement.data?.bankPaymentsToSuppliers.parties ?? []),
    ]
    for (const s of supplierParties) {
      if (s.tin.startsWith('name:')) continue
      const key = `s:${s.tin}`
      if (seen.has(key)) continue
      seen.add(key)
      out.push({ tin: s.tin, name: s.name || s.tin, kind: 'supplier' })
    }
    for (const c of customers.data ?? []) {
      const name = c.customerName || c.identification
      if (!name) continue
      const key = `c:${c.identification || name}`
      if (seen.has(key)) continue
      seen.add(key)
      out.push({ tin: c.identification || null, name, kind: 'customer' })
    }
    return out
  }, [statement.data, customers.data])
  return { options, loading: statement.isLoading || customers.isLoading }
}

/** The label shown in the datalist; parsed back by {@link parseOptionLabel}. */
export function optionLabel(o: CounterpartyOption): string {
  return o.tin && o.tin !== o.name ? `${o.name} · ${o.tin}` : o.name
}

/** Finds the option a typed value refers to — either its label or its exact name/TIN. */
export function matchOption(value: string, options: CounterpartyOption[]): CounterpartyOption | null {
  const v = value.trim()
  if (!v) return null
  return (
    options.find((o) => optionLabel(o) === v) ??
    options.find((o) => o.name === v) ??
    options.find((o) => o.tin === v) ??
    null
  )
}

export function CounterpartyField({
  id,
  name,
  tin,
  onChange,
  className,
  placeholder = 'Supplier, customer, or name as written in the source',
}: {
  id: string
  name: string | null
  tin: string | null
  onChange: (patch: { counterpartyName: string | null; counterpartyTin: string | null }) => void
  className?: string
  placeholder?: string
}) {
  const { options } = useCounterpartyDirectory()
  const listId = `${id}-options`
  const matched = React.useMemo(() => (tin ? options.find((o) => o.tin === tin) ?? null : null), [options, tin])
  return (
    <div>
      <input
        id={id}
        className={className}
        list={listId}
        value={name ?? ''}
        placeholder={placeholder}
        autoComplete="off"
        onChange={(event) => {
          const raw = event.target.value
          const hit = matchOption(raw, options)
          if (hit) {
            onChange({ counterpartyName: hit.name, counterpartyTin: hit.tin })
          } else {
            // Free text: keep the TIN only while the typed name still matches its record.
            const keep = tin && matched && matched.name === raw
            onChange({ counterpartyName: raw || null, counterpartyTin: keep ? tin : null })
          }
        }}
      />
      <datalist id={listId}>
        {options.map((o) => (
          <option key={`${o.kind}:${o.tin ?? o.name}`} value={optionLabel(o)}>
            {o.kind === 'supplier' ? 'supplier' : 'customer'}
          </option>
        ))}
      </datalist>
      <div className="mt-0.5 text-[10px] text-muted-foreground">
        {tin ? (
          <>
            {matched ? (matched.kind === 'supplier' ? 'Supplier' : 'Customer') : 'TIN'} {tin}
          </>
        ) : name ? (
          'Free text — no TIN; pick a suggestion to attach one'
        ) : (
          'Who must the document / check come from?'
        )}
      </div>
    </div>
  )
}
