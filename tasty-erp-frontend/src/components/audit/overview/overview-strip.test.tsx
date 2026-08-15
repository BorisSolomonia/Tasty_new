/**
 * BOR-92: the top strip renders four tiles from one payload, says when a
 * "chosen" figure has no chosen supplier (not zero), and each tile opens its
 * own panel — the outflow tree drills group → status → counterparty and its
 * "map" button targets the unmapped-withdrawals evidence key only.
 */
import { fireEvent, render, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuditOverview } from '@/lib/audit-api'

const showEvidence = vi.fn()
const overviewState: { data: AuditOverview | undefined; isLoading: boolean; isError: boolean; error: unknown } = {
  data: undefined,
  isLoading: false,
  isError: false,
  error: null,
}
const overviewCalls: unknown[] = []

vi.mock('../audit-context', () => ({
  useAudit: () => ({
    filters: { startDate: '2026-01-01', endDate: '2026-01-31' },
    showEvidence,
  }),
}))

vi.mock('@/hooks/use-audit-flows', () => ({
  useAuditOverview: (params: unknown) => {
    overviewCalls.push(params)
    return overviewState
  },
}))

import { OverviewStrip } from './overview-strip'

function payload(supplierTin: string | null): AuditOverview {
  const chosen = supplierTin !== null
  return {
    startDate: '2026-01-01',
    endDate: '2026-01-31',
    supplierTin,
    supplierName: chosen ? 'Supplier A' : null,
    suppliers: [
      { tin: 'SUP_A', name: 'Supplier A', purchases: 2600, bankPayments: 1000, paperOutflow: 500, quantityKg: 100, rowCount: 3 },
      { tin: 'SUP_B', name: 'Supplier B', purchases: 1100, bankPayments: 400, paperOutflow: null, quantityKg: 40, rowCount: 1 },
    ],
    purchases: {
      total: 3700,
      totalKg: 140,
      chosen: chosen ? 2600 : null,
      chosenKg: chosen ? 100 : null,
      byCategory: [{ category: 'BEEF', amount: 3700, quantityKg: 140, chosenAmount: chosen ? 2600 : null, chosenQuantityKg: chosen ? 100 : null, rowCount: 4 }],
      bySupplier: [
        { tin: 'SUP_A', name: 'Supplier A', purchases: 2600, bankPayments: 1000, paperOutflow: 500, quantityKg: 100, rowCount: 3 },
        { tin: 'SUP_B', name: 'Supplier B', purchases: 1100, bankPayments: 400, paperOutflow: null, quantityKg: 40, rowCount: 1 },
      ],
    },
    bankPaymentsToSuppliers: {
      total: 1400,
      toChosen: chosen ? 1000 : null,
      bySupplier: [{ tin: 'SUP_A', name: 'Supplier A', purchases: 2600, bankPayments: 1000, paperOutflow: null, quantityKg: null, rowCount: 2 }],
    },
    cashOutflow: {
      total: 2150,
      unmapped: 550,
      mapped: 1600,
      toChosen: chosen ? 1500 : null,
      groups: [
        {
          code: 'SUPPLIER_BANK_PAYMENT',
          label: 'Supplier bank payment',
          amount: 1400,
          rowCount: 2,
          tin: null,
          children: [
            {
              code: 'CHECK_NEEDED',
              label: 'Check needed',
              amount: 1000,
              rowCount: 1,
              tin: null,
              children: [{ code: 'SUP_A', label: 'Supplier A', amount: 1000, rowCount: 1, children: null, tin: 'SUP_A' }],
            },
          ],
        },
        { code: 'UNMAPPED', label: 'Unmapped', amount: 550, rowCount: 1, tin: null, children: [] },
      ],
      paperGroups: [],
      paperTotal: 500,
      debitRowCount: 4,
      unmappedRowCount: 1,
    },
    sales: {
      total: 2500,
      totalKg: 90,
      unreal: 700,
      real: 1800,
      unrealMapped: 500,
      unrealUnmapped: 200,
      byCategory: [{ category: 'BEEF', amount: 2500, quantityKg: 90, chosenAmount: null, chosenQuantityKg: null, rowCount: 3 }],
      unrealCustomers: [{ tin: 'CUST_X', name: 'Unreal X', purchases: 700, bankPayments: null, paperOutflow: 500, quantityKg: null, rowCount: 2 }],
      unrealRowCount: 2,
    },
    inventory: {
      byCategory: [
        {
          category: 'BEEF',
          purchasedKg: 140,
          writeOffPercent: 28,
          writeOffKg: 39.2,
          soldKg: 72.8,
          netKg: 28,
          stockBySupplier: [{ tin: 'SUP_B', name: 'Supplier B', quantityKg: 28, lastPurchaseDate: '2026-01-20' }],
        },
      ],
      netKgTotal: 28,
    },
    subgroups: [],
    notes: ['Opening stock is not recorded; net kg is a period movement.'],
  }
}

describe('OverviewStrip', () => {
  beforeEach(() => {
    showEvidence.mockReset()
    overviewCalls.length = 0
    overviewState.data = payload(null)
    overviewState.isLoading = false
    overviewState.isError = false
  })

  it('renders four tiles with totals and shows "—" (not 0) for chosen figures when no supplier is chosen', () => {
    render(<OverviewStrip />)
    const tabs = screen.getAllByRole('tab')
    expect(tabs.map((t) => t.textContent)).toEqual([
      expect.stringContaining('Purchases'),
      expect.stringContaining('Bank payments to suppliers'),
      expect.stringContaining('Cash outflow'),
      expect.stringContaining('Sales'),
    ])
    expect(within(tabs[0]).getByText(/3700,00/)).toBeInTheDocument()
    expect(within(tabs[0]).getByText('—')).toBeInTheDocument()
    expect(within(tabs[0]).getByText(/no supplier chosen/)).toBeInTheDocument()
    expect(within(tabs[2]).getByText(/2150,00/)).toBeInTheDocument()
    expect(within(tabs[2]).getByText(/550,00/)).toBeInTheDocument()
    expect(within(tabs[3]).getByText(/1800,00/)).toBeInTheDocument()
    expect(screen.getByText(/Opening stock is not recorded/)).toBeInTheDocument()
    // The period comes from the audit filters, no supplier from the picker.
    expect(overviewCalls[0]).toEqual({ startDate: '2026-01-01', endDate: '2026-01-31', supplierTin: undefined })
  })

  it('re-queries with the chosen supplier and fills the chosen columns', () => {
    render(<OverviewStrip />)
    // The hook mock answers the next render with the chosen payload, as the server would.
    overviewState.data = payload('SUP_A')
    fireEvent.change(screen.getByLabelText('Chosen supplier'), { target: { value: 'SUP_A' } })
    expect(overviewCalls.at(-1)).toEqual({ startDate: '2026-01-01', endDate: '2026-01-31', supplierTin: 'SUP_A' })
    const purchases = screen.getAllByRole('tab')[0]
    expect(within(purchases).getByText(/2600,00/)).toBeInTheDocument()
  })

  it('opens the outflow tree group → status → counterparty and maps only the unmapped-withdrawal evidence', () => {
    render(<OverviewStrip />)
    fireEvent.click(screen.getAllByRole('tab')[2])
    const panel = screen.getByRole('tabpanel')
    fireEvent.click(within(panel).getByRole('button', { name: /Supplier bank payment/ }))
    fireEvent.click(within(panel).getByRole('button', { name: /Check needed/ }))
    expect(within(panel).getByText('Supplier A')).toBeInTheDocument()
    expect(within(panel).getByText('Unmapped')).toBeInTheDocument()
    fireEvent.click(within(panel).getByRole('button', { name: /Map unmapped rows/ }))
    expect(showEvidence).toHaveBeenCalledWith(expect.objectContaining({ key: 'cash.unresolvedWithdrawals' }))
  })

  it('shows LIFO stock by supplier under the purchases panel', () => {
    render(<OverviewStrip />)
    fireEvent.click(screen.getAllByRole('tab')[0])
    const panel = screen.getByRole('tabpanel')
    expect(within(panel).queryByText(/last 2026-01-20/)).toBeNull()
    fireEvent.click(within(panel).getByRole('button', { name: /Beef|BEEF/ }))
    const lot = within(panel).getByText(/last 2026-01-20/).closest('li')
    expect(lot).not.toBeNull()
    expect(within(lot as HTMLElement).getByText('Supplier B')).toBeInTheDocument()
    expect(within(lot as HTMLElement).getByText(/28/)).toBeInTheDocument()
  })

  it('names the failure instead of showing empty tiles', () => {
    overviewState.data = undefined
    overviewState.isError = true
    overviewState.error = new Error('boom')
    render(<OverviewStrip />)
    expect(screen.getByText(/Overview did not load/)).toBeInTheDocument()
  })
})
