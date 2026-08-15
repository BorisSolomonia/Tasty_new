/** BOR-87 hybrid: the chart components render from real payload shapes without throwing, and expose their table view. */
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { AuditDashboard, DualLedger, InventoryLedger, UnifiedCategoryCard } from '@/types/domain'
import { AlarmStrip } from './alarm-strip'
import { DumbbellChart } from './charts/dumbbell-chart'
import { LedgerHeatmap } from './charts/ledger-heatmap'
import { InventoryWaterfall } from './charts/inventory-waterfall'
import { DailyLedgerChart } from './charts/daily-ledger-chart'
import { CashGapBars } from './charts/cash-gap-bars'

const card: UnifiedCategoryCard = {
  category: 'BEEF', purchaseDocKg: 18420, purchaseDocPrice: 19.8, writeOffPercent: 28, netDocPurchaseKg: 13262.4, netDocKgPrice: 27.5,
  purchaseRealKg: 19900, purchaseRealPrice: 21.1, debtDoc: 364716, debtReal: 419890, vatDifference: 8416.7,
  salesDocKg: 12610, salesDocPrice: 26.4, salesDocTotal: 332904, unrealSalesKg: 1150, formalSalesKg: 820, salesRealKg: 10640,
  salesRealPrice: 27.2, realProductSales: 289408, formalCommission: 410, salesRealTotal: 289818, startingInventoryKg: 0, onHandDocKg: 652.4,
}
const ledger: InventoryLedger = {
  parentCategory: 'BEEF', childProducts: [], openingStockKg: 0, totalPurchasedKg: 100, totalSoldKg: 90, totalWriteOffKg: 28,
  endingInventoryKg: -18, writeOffRatePercent: 28, overageDays: 1,
  dailyRows: [
    { date: '2026-08-01', startingInventoryKg: 0, purchasedKg: 50, soldKg: 20, writeOffKg: 14, endingInventoryKg: 16, writeOffPercent: 28, overage: false },
    { date: '2026-08-02', startingInventoryKg: 16, purchasedKg: 0, soldKg: 30, writeOffKg: 0, endingInventoryKg: -14, writeOffPercent: 28, overage: true },
  ],
}
const dual = {
  categoryCards: [card],
  purchaseShortages: [{ category: 'BEEF', docKg: 1, docPrice: 1, docTotal: 100, realKg: 1, realPrice: 1, realTotal: 80, gap: 20 }],
  saleSurpluses: [{ category: 'BEEF', docKg: 1, docPrice: 1, docTotal: 200, realKg: 1, realPrice: 1, realTotal: 230, gap: 30 }],
  totalPurchaseShortage: 20, totalSaleSurplus: 30,
} as unknown as DualLedger
const dashboard = { inventoryLedgers: [ledger], exceptions: [], exceptionDebtTotal: 0, realTotals: { excludedEntityCount: 0 } } as unknown as AuditDashboard

describe('audit-control charts', () => {
  it('DumbbellChart renders one row per category with a table view and measure tabs', () => {
    render(<DumbbellChart cards={[card]} />)
    expect(screen.getByRole('img', { name: /documented versus real/i })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Sales ₾' })).toBeInTheDocument()
    expect(screen.getByText('Table view')).toBeInTheDocument()
  })
  it('LedgerHeatmap renders and states the opening-stock caveat', () => {
    render(<LedgerHeatmap ledgers={[ledger]} />)
    expect(screen.getByRole('img', { name: /pressure by category and day/i })).toBeInTheDocument()
    expect(screen.getByText(/Opening stock is not recorded/)).toBeInTheDocument()
  })
  it('InventoryWaterfall and DailyLedgerChart render', () => {
    render(<InventoryWaterfall card={card} />)
    render(<DailyLedgerChart ledger={ledger} />)
    expect(screen.getByRole('img', { name: /inventory waterfall/i })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: /daily ledger/i })).toBeInTheDocument()
    expect(screen.getByText('not recorded')).toBeInTheDocument()
  })
  it('CashGapBars renders the previously unrendered series', () => {
    render(<CashGapBars dual={dual} />)
    expect(screen.getByRole('img', { name: /purchase shortage and sales surplus/i })).toBeInTheDocument()
  })
  it('AlarmStrip renders one chip per alarm with icon+word, linking to evidence sections', () => {
    render(<AlarmStrip dashboard={dashboard} dual={dual} />)
    const links = screen.getAllByRole('link')
    expect(links.length).toBe(5)
    expect(links[0]).toHaveAttribute('href', '#audit-heatmap')
    expect(screen.getByText(/critical:/i)).toBeInTheDocument()
  })
})
