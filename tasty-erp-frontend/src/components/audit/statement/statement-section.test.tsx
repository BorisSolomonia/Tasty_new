/**
 * BOR-92 v2: the statement renders as one ledger in income-statement order,
 * "chosen" reads as absent (never 0) until something is ticked, ticks are
 * saved (debounced) as the shared selection, and a product's group change goes
 * through a confirmation that names the product and the reach.
 */
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuditStatement, StatementRow, StatementTransaction } from '@/lib/audit-api'

const saveMutate = vi.fn()
const setCategoryMutateAsync = vi.fn(async () => undefined)
const statementState: { data: AuditStatement | undefined; isLoading: boolean; isError: boolean; error: unknown } = {
  data: undefined,
  isLoading: false,
  isError: false,
  error: null,
}
const transactionsState: { data: StatementTransaction[] | undefined; isLoading: boolean; isError: boolean; error: unknown } = {
  data: [],
  isLoading: false,
  isError: false,
  error: null,
}
const showEvidence = vi.fn()

vi.mock('../audit-context', () => ({
  useAudit: () => ({ filters: { startDate: '2026-08-01', endDate: '2026-08-15' }, operator: 'boris', showEvidence }),
}))
vi.mock('../operator-picker', () => ({
  useOperatorGuard: () => ({ operator: 'boris', ready: true, message: 'Enter an operator name in the page header before saving.' }),
}))
vi.mock('@/hooks/use-audit-flows', () => ({
  useAuditStatement: () => statementState,
  useSaveStatementSelection: () => ({ mutate: saveMutate, isPending: false, isError: false, error: null }),
  useStatementTransactions: () => transactionsState,
  useSetProductCategory: () => ({ mutateAsync: setCategoryMutateAsync, isPending: false }),
  useProductCategoryCodes: () => ({ data: ['BEEF', 'PORK', 'FAT', 'OTHER'] }),
}))

import { StatementSection } from './statement-section'

function row(key: StatementRow['key'], title: string, over: Partial<StatementRow> = {}): StatementRow {
  return {
    key,
    title,
    definition: `${title} definition`,
    chosenBy: key === 'sales' || key === 'bankInflow' || key === 'cashInflow' ? 'CUSTOMERS' : 'SUPPLIERS',
    total: 1000,
    totalKg: null,
    chosen: null,
    chosenKg: null,
    secondary: null,
    secondaryLabel: null,
    rowCount: 3,
    parties: [],
    products: null,
    ...over,
  }
}

function payload(): AuditStatement {
  return {
    startDate: '2026-08-01',
    endDate: '2026-08-15',
    operator: 'boris',
    selection: { suppliers: [], customers: [] },
    purchases: row('purchases', 'Purchases', {
      total: 3700,
      totalKg: 190,
      parties: [
        { tin: 'SUP_A', name: 'Supplier A', amount: 2600, quantityKg: 140, secondary: null, rowCount: 2, chosen: false, unreal: false, identityBasis: null },
        { tin: 'SUP_B', name: 'Supplier B', amount: 1100, quantityKg: 50, secondary: null, rowCount: 1, chosen: false, unreal: false, identityBasis: null },
      ],
      products: [
        { category: 'BEEF', amount: 3100, quantityKg: 150, chosenAmount: null, chosenKg: null, rowCount: 2, productCount: 1 },
        { category: 'PORK', amount: 600, quantityKg: 40, chosenAmount: null, chosenKg: null, rowCount: 1, productCount: 1 },
      ],
    }),
    bankPaymentsToSuppliers: row('bankPaymentsToSuppliers', 'Bank payments to suppliers', { total: 1400 }),
    cashOutflow: row('cashOutflow', 'Cash outflow', {
      total: 2150,
      secondary: 550,
      secondaryLabel: 'unmapped',
      parties: [
        { tin: 'SUP_A', name: 'Supplier A', amount: 1500, quantityKg: null, secondary: 300, rowCount: 1, chosen: false, unreal: false, identityBasis: null },
        { tin: 'name:ATM', name: 'ATM', amount: 250, quantityKg: null, secondary: 250, rowCount: 1, chosen: false, unreal: false, identityBasis: null },
      ],
    }),
    inventory: {
      key: 'inventory',
      title: 'Inventory (net, on paper)',
      definition: 'inv def',
      totalKg: 58,
      totalValue: 1028.67,
      unpricedCategories: [],
      levels: [
        {
          category: 'BEEF',
          purchasedKg: 150,
          purchasedAmount: 3100,
          writeOffPercent: 28,
          writeOffKg: 42,
          soldKg: 80,
          soldAmount: 2500,
          netKg: 28,
          avgPurchasePricePerKg: 20.67,
          value: 578.67,
          stockBySupplier: [{ tin: 'SUP_B', name: 'Supplier B', quantityKg: 28, lastPurchaseDate: '2026-08-05' }],
        },
      ],
    },
    sales: row('sales', 'Sales', { total: 2500, totalKg: 80, secondary: 1800, secondaryLabel: 'real' }),
    bankInflow: row('bankInflow', 'Bank inflow (payments from customers)', { total: 620 }),
    cashInflow: row('cashInflow', 'Cash inflow (cash from customers)', { total: 80 }),
    notes: ['No supplier is chosen — supplier-side figures are empty, not zero.'],
  }
}

describe('StatementSection', () => {
  beforeEach(() => {
    saveMutate.mockReset()
    setCategoryMutateAsync.mockClear()
    statementState.data = payload()
    statementState.isLoading = false
    statementState.isError = false
    transactionsState.data = []
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('lists the seven rows in income-statement order with total | chosen, and never shows 0 for an empty selection', () => {
    render(<StatementSection />)
    const rows = screen.getAllByRole('row').filter((r) => r.getAttribute('data-row'))
    expect(rows.map((r) => r.getAttribute('data-row'))).toEqual([
      'purchases',
      'bankPaymentsToSuppliers',
      'cashOutflow',
      'inventory',
      'sales',
      'bankInflow',
      'cashInflow',
    ])
    expect(within(rows[0]).getByText(/3700,00/)).toBeInTheDocument()
    expect(within(rows[0]).getByText(/190 kg/)).toBeInTheDocument()
    expect(within(rows[0]).getByText('nothing ticked')).toBeInTheDocument()
    expect(within(rows[2]).getByText(/unmapped 550,00/)).toBeInTheDocument()
    expect(within(rows[3]).getByText(/58 kg/)).toBeInTheDocument()
    expect(within(rows[4]).getByText(/real 1800,00/)).toBeInTheDocument()
    expect(screen.getByText(/No supplier is chosen/)).toBeInTheDocument()
  })

  it('opens Purchases with suppliers, saves a tick as the shared supplier set (debounced), and refuses to tick a nameless party', () => {
    vi.useFakeTimers()
    render(<StatementSection />)
    fireEvent.click(screen.getByRole('button', { name: /Purchases — open/ }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByRole('tab', { name: /Suppliers \(2\)/ })).toBeInTheDocument()
    fireEvent.click(within(dialog).getByLabelText('Choose Supplier A'))
    expect(saveMutate).not.toHaveBeenCalled()
    act(() => {
      vi.advanceTimersByTime(600)
    })
    expect(saveMutate).toHaveBeenCalledWith({ suppliers: ['SUP_A'], customers: [] })
    // Optimistic: the box reads as ticked before the server answers.
    expect((within(dialog).getByLabelText('Choose Supplier A') as HTMLInputElement).checked).toBe(true)

    fireEvent.keyDown(dialog, { key: 'Escape' })
    fireEvent.click(screen.getByRole('button', { name: /Cash outflow — open/ }))
    const outflow = screen.getByRole('dialog')
    expect((within(outflow).getByLabelText('Choose ATM') as HTMLInputElement).disabled).toBe(true)
    expect(within(outflow).getByText('no TIN in source')).toBeInTheDocument()
  })

  it('shows product groups, unfolds lines, and confirms a group change naming the product and its reach before saving', async () => {
    transactionsState.data = [
      {
        id: 'doc-0',
        kind: 'DOCUMENT_LINE',
        date: '2026-08-01',
        direction: 'PURCHASE',
        amount: 2000,
        counterpartyTin: 'SUP_A',
        counterpartyName: 'Supplier A',
        productName: 'beef carcass',
        category: 'BEEF',
        quantityKg: 100,
        unit: 'კგ',
        waybillId: 'w-1',
        description: null,
        reference: null,
        source: null,
        sourceType: 'RS_GE',
        sourceRowId: 'doc-0',
        mappingStatus: 'UNMAPPED',
        mappingSummary: null,
        unresolvedAmount: null,
      },
      {
        id: 'doc-1',
        kind: 'DOCUMENT_LINE',
        date: '2026-08-05',
        direction: 'PURCHASE',
        amount: 1100,
        counterpartyTin: 'SUP_B',
        counterpartyName: 'Supplier B',
        productName: 'beef carcass',
        category: 'BEEF',
        quantityKg: 50,
        unit: 'კგ',
        waybillId: 'w-2',
        description: null,
        reference: null,
        source: null,
        sourceType: 'RS_GE',
        sourceRowId: 'doc-1',
        mappingStatus: 'UNMAPPED',
        mappingSummary: null,
        unresolvedAmount: null,
      },
    ]
    render(<StatementSection />)
    fireEvent.click(screen.getByRole('button', { name: /Purchases — open/ }))
    const dialog = screen.getByRole('dialog')
    fireEvent.mouseDown(within(dialog).getByRole('tab', { name: /Products/ }))
    fireEvent.click(within(dialog).getByRole('tab', { name: /Products/ }))
    fireEvent.click(within(dialog).getByRole('button', { name: /Beef/ }))
    expect(within(dialog).getByText('w-1')).toBeInTheDocument()
    expect(within(dialog).getByText('Supplier B')).toBeInTheDocument()

    const selects = within(dialog).getAllByLabelText('Group for beef carcass')
    fireEvent.change(selects[0], { target: { value: 'PORK' } })
    const confirm = screen.getAllByRole('dialog').at(-1) as HTMLElement
    expect(within(confirm).getByText(/Move “beef carcass” to/)).toBeInTheDocument()
    expect(within(confirm).getByText(/2 lines carry this name/)).toBeInTheDocument()
    expect(setCategoryMutateAsync).not.toHaveBeenCalled()
    fireEvent.click(within(confirm).getByRole('button', { name: /Move to/ }))
    await act(async () => {
      await Promise.resolve()
    })
    expect(setCategoryMutateAsync).toHaveBeenCalledWith({ productName: 'beef carcass', category: 'PORK' })
  })

  it('opens inventory levels with the LIFO supplier attribution', () => {
    render(<StatementSection />)
    fireEvent.click(screen.getByRole('button', { name: /Inventory \(net, on paper\) — open/ }))
    const dialog = screen.getByRole('dialog')
    fireEvent.click(within(dialog).getByRole('button', { name: /Beef/ }))
    expect(within(dialog).getByText('Supplier B')).toBeInTheDocument()
    expect(within(dialog).getByText(/last 2026-08-05/)).toBeInTheDocument()
  })
})
