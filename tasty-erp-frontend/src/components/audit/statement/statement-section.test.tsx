/**
 * BOR-92 v2: the statement renders as one ledger in income-statement order,
 * "chosen" reads as absent (never 0) until something is ticked, ticks are
 * saved (debounced) as the shared selection, and a product's group change goes
 * through a confirmation that names the product and the reach.
 */
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuditSourceRow, AuditStatement, StatementParty, StatementRow, StatementTransaction } from '@/lib/audit-api'

const saveMutate = vi.fn()
const setCategoryMutateAsync = vi.fn(async () => undefined)
const voidMutateAsync = vi.fn(async () => undefined)
const bulkMutateAsync = vi.fn(async () => ({ mapped: 1, skipped: 0, amount: 600 }))
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
  useAudit: () => ({
    filters: { startDate: '2026-08-01', endDate: '2026-08-15' },
    operator: 'boris',
    showEvidence,
    categories: [
      { code: 'UNDOCUMENTED_WITHDRAWAL', label: 'Undocumented withdrawal', builtIn: true, description: 'Cash gone elsewhere.' },
      { code: 'SUPPLIER_CASH_PAYMENT', label: 'Supplier real cash payment', builtIn: true },
    ],
  }),
}))
vi.mock('../counterparty-picker', () => ({
  CounterpartyField: ({ id }: { id: string }) => <input id={id} aria-label="Counterparty" />,
}))
vi.mock('../operator-picker', () => ({
  useOperatorGuard: () => ({ operator: 'boris', ready: true, message: 'Enter an operator name in the page header before saving.' }),
}))
vi.mock('../mapping-dialog', () => ({
  MappingDialog: ({ row }: { row: AuditSourceRow | null }) => (row ? <div role="dialog" aria-label="mapping-editor">editing {row.sourceRowId}</div> : null),
}))

vi.mock('@/hooks/use-audit-flows', () => ({
  useAuditStatement: () => statementState,
  useSaveStatementSelection: () => ({ mutate: saveMutate, isPending: false, isError: false, error: null }),
  useStatementTransactions: () => transactionsState,
  useSetProductCategory: () => ({ mutateAsync: setCategoryMutateAsync, isPending: false }),
  useProductCategoryCodes: () => ({ data: ['BEEF', 'PORK', 'FAT', 'OTHER'] }),
  useVoidMapping: () => ({ mutateAsync: voidMutateAsync, isPending: false }),
  useBulkMapStatement: () => ({ mutateAsync: bulkMutateAsync, isPending: false }),
  useAuditSubgroups: () => ({ data: [{ code: 'CHECK_NEEDED', label: 'Check needed', description: null, builtIn: true }] }),
}))

import { StatementSection } from './statement-section'

function party(over: Partial<StatementParty> & { tin: string; name: string }): StatementParty {
  return {
    amount: null,
    quantityKg: null,
    secondary: null,
    directAmount: null,
    directCount: 0,
    mappedAmount: null,
    mappedCount: 0,
    bankPaid: null,
    unpaidAfterBank: null,
    supplierPaymentsNotOnRsGe: null,
    rowCount: 0,
    chosen: false,
    unreal: false,
    identityBasis: null,
    ...over,
  }
}

function tx(over: Partial<StatementTransaction> & { id: string; kind: StatementTransaction['kind'] }): StatementTransaction {
  return {
    date: null,
    direction: null,
    amount: null,
    counterpartyTin: null,
    counterpartyName: null,
    productName: null,
    category: null,
    quantityKg: null,
    unit: null,
    waybillId: null,
    description: null,
    reference: null,
    source: null,
    sourceType: null,
    sourceRowId: null,
    mappingStatus: null,
    mappingSummary: null,
    unresolvedAmount: null,
    mappedCounterparties: null,
    withdrawal: false,
    attribution: null,
    sourceRow: null,
    ...over,
  }
}

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
    extras: null,
    firstDate: null,
    lastDate: null,
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
        party({ tin: 'SUP_A', name: 'Supplier A', amount: 2600, quantityKg: 140, rowCount: 2, bankPaid: 1000, unpaidAfterBank: 1600 }),
        party({ tin: 'SUP_B', name: 'Supplier B', amount: 1100, quantityKg: 50, rowCount: 1, bankPaid: 750, unpaidAfterBank: 350 }),
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
      extras: [
        { label: 'unmapped', amount: 550 },
        { label: 'withdrawals', amount: 600 },
      ],
      parties: [
        party({ tin: 'SUP_A', name: 'Supplier A', amount: 1500, secondary: 300, rowCount: 1, directAmount: 1500, directCount: 1, mappedAmount: 0, mappedCount: 0 }),
        party({ tin: 'SUP_B', name: 'Supplier B', amount: 750, rowCount: 2, directAmount: 400, directCount: 1, mappedAmount: 350, mappedCount: 1 }),
        party({ tin: 'name:ATM', name: 'ATM', amount: 250, secondary: 250, rowCount: 1, directAmount: 250, directCount: 1, mappedAmount: 0, mappedCount: 0 }),
        party({ tin: '600000009', name: 'ნუკრი ბოშიშვილი', amount: 900, rowCount: 1, directAmount: 900, directCount: 1, mappedAmount: 0, mappedCount: 0, supplierPaymentsNotOnRsGe: 900 }),
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
    sales: row('sales', 'Sales', {
      total: 2500,
      totalKg: 80,
      secondary: 1800,
      secondaryLabel: 'real',
      parties: [party({ tin: 'CUST_REAL', name: 'Real Customer', amount: 1800, quantityKg: 60, rowCount: 1, bankPaid: 900, unpaidAfterBank: 900 })],
    }),
    bankInflow: row('bankInflow', 'Bank inflow (payments from customers)', {
      total: 1049,
      firstDate: '2026-08-05',
      lastDate: '2026-08-13',
      extras: [
        { label: 'mapped from customers', amount: 900 },
        { label: 'unmapped income', amount: 149 },
      ],
    }),
    cashInflow: row('cashInflow', 'Cash inflow (cash from customers)', { total: 80 }),
    summary: {
      purchases: 3700,
      bankPaymentsToSuppliers: 1750,
      possibleChecksNeeded: 1950,
      withdrawals: 600,
      withdrawalsToSuppliers: 350,
      withdrawalsUnresolved: 250,
      withdrawalsUndocumented: 120,
      sales: 2500,
      bankReceiptsFromCustomers: 900,
      receivables: 400,
      cashToReceiveFromCustomers: 1200,
      cashToPaySuppliers: 1800,
    },
    checks: [
      { code: 'PARTIES_SUM_purchases', label: 'Purchases: counterparties add up to the row', status: 'PASSED', expected: 3700, actual: 3700, detail: '2 counterparties' },
      { code: 'RSGE_DOCUMENTS_PURCHASES', label: 'Purchases: RS.ge document totals vs goods lines', status: 'FAILED', expected: 4000, actual: 3700, detail: '4 waybills, 3 counterparties · 1 without goods (300,00)' },
      { code: 'RSGE_DOCUMENTS_SALES', label: 'Sales: RS.ge document totals vs goods lines', status: 'SKIPPED', expected: null, actual: null, detail: 'waybill-service did not answer — not checked' },
    ],
    notes: ['No supplier is chosen — supplier-side figures are empty, not zero.'],
  }
}

describe('StatementSection', () => {
  beforeEach(() => {
    saveMutate.mockReset()
    setCategoryMutateAsync.mockClear()
    voidMutateAsync.mockClear()
    bulkMutateAsync.mockClear()
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
    expect(within(dialog).getByText('bank paid ₾')).toBeInTheDocument()
    expect(within(dialog).getByText('unpaid after bank ₾')).toBeInTheDocument()
    expect(within(dialog).getByText(/1600,00/)).toBeInTheDocument()
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
    // v4: a party without a TIN is choosable by its printed label and saved as name:<label>.
    const atm = within(outflow).getByLabelText('Choose ATM') as HTMLInputElement
    expect(atm.disabled).toBe(false)
    expect(within(outflow).getByText('no TIN in source')).toBeInTheDocument()
    fireEvent.click(atm)
    act(() => {
      vi.advanceTimersByTime(600)
    })
    expect(saveMutate).toHaveBeenLastCalledWith({ suppliers: ['SUP_A', 'name:ATM'], customers: [] })
  })

  it('shows product groups, unfolds lines, and confirms a group change naming the product and its reach before saving', async () => {
    transactionsState.data = [
      tx({ id: 'doc-0', kind: 'DOCUMENT_LINE', date: '2026-08-01', direction: 'PURCHASE', amount: 2000, counterpartyTin: 'SUP_A', counterpartyName: 'Supplier A', productName: 'beef carcass', category: 'BEEF', quantityKg: 100, unit: 'კგ', waybillId: 'w-1', sourceType: 'RS_GE', sourceRowId: 'doc-0', mappingStatus: 'UNMAPPED' }),
      tx({ id: 'doc-1', kind: 'DOCUMENT_LINE', date: '2026-08-05', direction: 'PURCHASE', amount: 1100, counterpartyTin: 'SUP_B', counterpartyName: 'Supplier B', productName: 'beef carcass', category: 'BEEF', quantityKg: 50, unit: 'კგ', waybillId: 'w-2', sourceType: 'RS_GE', sourceRowId: 'doc-1', mappingStatus: 'UNMAPPED' }),
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

  it('renders the summary lines under the table with every operand and the AR basis', () => {
    render(<StatementSection />)
    const summary = screen.getByTestId('statement-summary')
    expect(within(summary).getByText(/possible checks needed/)).toBeInTheDocument()
    expect(within(summary).getByText(/1950,00/)).toBeInTheDocument()
    expect(within(summary).getByText(/withdrawals mapped to suppliers/)).toBeInTheDocument()
    expect(within(summary).getAllByText(/undocumented withdrawals/).length).toBe(2)
    expect(within(summary).getAllByText(/120,00/).length).toBeGreaterThanOrEqual(2)
    expect(within(summary).getByText(/\/payments total outstanding, as of now/)).toBeInTheDocument()
    expect(within(summary).getAllByText(/1200,00/)).toHaveLength(2) // once as a result, once as an operand of the last line
    expect(within(summary).getByText(/to be paid to suppliers as cash/)).toBeInTheDocument()
    expect(within(summary).getByText(/1800,00/)).toBeInTheDocument()
    // Bank inflow's note carries both figures.
    const inflow = screen.getAllByRole('row').find((r) => r.getAttribute('data-row') === 'bankInflow') as HTMLElement
    expect(within(inflow).getByText(/mapped from customers 900,00/)).toBeInTheDocument()
    expect(within(inflow).getByText(/unmapped income 149,00/)).toBeInTheDocument()
    expect(within(inflow).getByText(/rows dated 2026-08-05 → 2026-08-13/)).toBeInTheDocument()
  })

  it('cash outflow: direct vs mapped per party, withdrawals filter, "Mapped to" column and a Map… button that opens the editor', () => {
    transactionsState.data = [
      tx({
        id: 'b5',
        kind: 'BANK_ROW',
        date: '2026-08-05',
        direction: 'DEBIT',
        amount: 600,
        counterpartyTin: '500000001',
        counterpartyName: 'ალექსანდრე თოფურიძე',
        description: 'cash-out',
        sourceType: 'BANK',
        sourceRowId: 'b5',
        mappingStatus: 'MANUALLY_MAPPED',
        mappingSummary: 'Supplier real cash payment → Supplier B (350); Cash withdrawal — unresolved (250)',
        mappedCounterparties: ['Supplier B'],
        withdrawal: true,
        attribution: 'MAPPED',
        sourceRow: { sourceType: 'BANK', sourceRowId: 'b5' } as AuditSourceRow,
      }),
    ]
    render(<StatementSection />)
    fireEvent.click(screen.getByRole('button', { name: /Cash outflow — open/ }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByText('direct ₾')).toBeInTheDocument()
    expect(within(dialog).getByText('mapped ₾')).toBeInTheDocument()
    const bRow = within(dialog).getByText('Supplier B').closest('tr') as HTMLElement
    expect(within(bRow).getByText(/400,00/)).toBeInTheDocument()
    expect(within(bRow).getByText(/350,00/)).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Withdrawals only')).toBeInTheDocument()
    expect(within(dialog).getByText(/withdrawals only \(600,00/)).toBeInTheDocument()

    fireEvent.change(within(dialog).getByLabelText('Attribution filter'), { target: { value: 'MAPPED' } })
    fireEvent.click(within(dialog).getByRole('button', { name: /^Supplier B/ }))
    expect(within(dialog).getByText('Mapped to')).toBeInTheDocument()
    expect(within(dialog).getByText('withdrawal')).toBeInTheDocument()
    expect(within(dialog).getByText('mapped here')).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: /Map ალექსანდრე თოფურიძე/ }))
    expect(screen.getByRole('dialog', { name: 'mapping-editor' })).toHaveTextContent('editing b5')
  })

  it('unmaps a mapped bank transaction with a reason, and maps a ticked set through the bulk dialog', async () => {
    transactionsState.data = [
      tx({
        id: 'b5',
        kind: 'BANK_ROW',
        date: '2026-08-05',
        direction: 'DEBIT',
        amount: 600,
        counterpartyTin: '500000001',
        counterpartyName: 'ალექსანდრე თოფურიძე',
        sourceType: 'BANK',
        sourceRowId: 'b5',
        mappingStatus: 'MANUALLY_MAPPED',
        mappingSummary: 'Cash withdrawal — unresolved (600)',
        withdrawal: true,
        attribution: 'DIRECT',
        sourceRow: { sourceType: 'BANK', sourceRowId: 'b5' } as AuditSourceRow,
      }),
    ]
    render(<StatementSection />)
    fireEvent.click(screen.getByRole('button', { name: /Cash outflow — open/ }))
    const dialog = screen.getByRole('dialog')
    fireEvent.click(within(dialog).getByRole('button', { name: /^Supplier A/ }))

    // Unmap: reason required, void called with the mapping id.
    fireEvent.click(within(dialog).getByRole('button', { name: /Unmap ალექსანდრე თოფურიძე/ }))
    const unmap = screen.getAllByRole('dialog').at(-1) as HTMLElement
    expect(within(unmap).getByRole('button', { name: 'Unmap' })).toBeDisabled()
    fireEvent.change(within(unmap).getByLabelText('Unmap reason'), { target: { value: 'wrong group' } })
    fireEvent.click(within(unmap).getByRole('button', { name: 'Unmap' }))
    await act(async () => {
      await Promise.resolve()
    })
    expect(voidMutateAsync).toHaveBeenCalledWith({ id: 'BANK__b5', reason: 'wrong group' })

    // Bulk: tick the row, "Map selected (1)…", choose the group, confirm — explicit ids, fill mode by default.
    fireEvent.click(within(dialog).getByLabelText('Select ალექსანდრე თოფურიძე'))
    fireEvent.click(within(dialog).getByRole('button', { name: /Map selected \(1\)/ }))
    const bulk = screen.getAllByRole('dialog').at(-1) as HTMLElement
    expect(within(bulk).getByText(/This is not a rule/)).toBeInTheDocument()
    fireEvent.change(within(bulk).getByLabelText('Group'), { target: { value: 'UNDOCUMENTED_WITHDRAWAL' } })
    fireEvent.click(within(bulk).getByRole('button', { name: /Map 1 transaction/ }))
    await act(async () => {
      await Promise.resolve()
    })
    expect(bulkMutateAsync).toHaveBeenCalledWith(
      expect.objectContaining({ sourceRowIds: ['b5'], categoryCode: 'UNDOCUMENTED_WITHDRAWAL', replace: false, sourceType: 'BANK', startDate: '2026-08-01', endDate: '2026-08-15' })
    )
    expect(within(bulk).getByText(/1.*mapped/)).toBeInTheDocument()
  })

  it('sales window carries bank received / unpaid after bank per customer, like purchases', () => {
    render(<StatementSection />)
    fireEvent.click(screen.getByRole('button', { name: /^Sales — open/ }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByText('bank received ₾')).toBeInTheDocument()
    expect(within(dialog).getByText('unpaid after bank ₾')).toBeInTheDocument()
    const rowEl = within(dialog).getByText('Real Customer').closest('tr') as HTMLElement
    expect(within(rowEl).getAllByText(/900,00/).length).toBe(2)
  })

  it('shows failed and skipped checks with their figures, and flags supplier payments to counterparties not on RS.ge', () => {
    render(<StatementSection />)
    const checks = screen.getByTestId('statement-checks')
    expect(within(checks).getByText('1 check failed')).toBeInTheDocument()
    expect(within(checks).getAllByText(/RS.ge document totals vs goods lines/).length).toBe(2) // the failed purchases check and the skipped sales check
    expect(within(checks).getByText(/expected 4000,00 .* shown 3700,00/)).toBeInTheDocument()
    expect(within(checks).getByText(/1 without goods/)).toBeInTheDocument()
    expect(within(checks).getByText(/waybill-service did not answer/)).toBeInTheDocument()
    fireEvent.click(within(checks).getByRole('button', { name: 'Show all' }))
    expect(within(checks).getByText(/counterparties add up to the row/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /Cash outflow — open/ }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByText(/mapped as supplier payment · not on RS.ge 900,00/)).toBeInTheDocument()
  })

  it('warns on screen when the counterparties shown do not add up to the row shown', () => {
    const data = payload()
    data.purchases.total = 9999
    statementState.data = data
    render(<StatementSection />)
    fireEvent.click(screen.getByRole('button', { name: /Purchases — open/ }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByRole('alert')).toHaveTextContent(/add up to 3700,00 ₾, the row shows 9999,00 ₾/)
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
