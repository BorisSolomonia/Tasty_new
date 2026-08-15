import { describe, expect, it } from 'vitest'
import type { AuditDashboard, DualLedger, InventoryLedger, UnifiedCategoryCard } from '@/types/domain'
import { cashGapRows, deriveAlarms, dumbbellRows, heatStep, heatmapModel, waterfallSteps } from './derive'

const gel = (v: number) => `₾${Math.round(v)}`
const kg = (v: number) => `${Math.round(v)} kg`

const card = (over: Partial<UnifiedCategoryCard> = {}): UnifiedCategoryCard => ({
  category: 'BEEF',
  purchaseDocKg: 18420,
  purchaseDocPrice: 19.8,
  writeOffPercent: 28,
  netDocPurchaseKg: 13262.4,
  netDocKgPrice: 27.5,
  purchaseRealKg: 19900,
  purchaseRealPrice: 21.1,
  debtDoc: 364716,
  debtReal: 419890,
  vatDifference: 8416.7,
  salesDocKg: 12610,
  salesDocPrice: 26.4,
  salesDocTotal: 332904,
  unrealSalesKg: 1150,
  formalSalesKg: 820,
  salesRealKg: 10640,
  salesRealPrice: 27.2,
  realProductSales: 289408,
  formalCommission: 410,
  salesRealTotal: 289818,
  startingInventoryKg: 0,
  onHandDocKg: 652.4,
  ...over,
})

const ledger = (over: Partial<InventoryLedger> = {}): InventoryLedger => ({
  parentCategory: 'BEEF',
  childProducts: [],
  openingStockKg: 0,
  totalPurchasedKg: 100,
  totalSoldKg: 90,
  totalWriteOffKg: 28,
  endingInventoryKg: -18,
  writeOffRatePercent: 28,
  overageDays: 1,
  dailyRows: [
    { date: '2026-08-01', startingInventoryKg: 0, purchasedKg: 50, soldKg: 20, writeOffKg: 14, endingInventoryKg: 16, writeOffPercent: 28, overage: false },
    { date: '2026-08-02', startingInventoryKg: 16, purchasedKg: 0, soldKg: 30, writeOffKg: 0, endingInventoryKg: -14, writeOffPercent: 28, overage: true },
    { date: '2026-08-03', startingInventoryKg: -14, purchasedKg: 50, soldKg: 40, writeOffKg: 14, endingInventoryKg: -18, writeOffPercent: 28, overage: false },
  ],
  ...over,
})

describe('waterfallSteps', () => {
  it('chains opening (not recorded) → +purchases → −write-off → −sales = net, and the identity holds', () => {
    const steps = waterfallSteps(card())
    expect(steps.map((s) => s.kind)).toEqual(['unknown', 'up', 'down', 'down', 'total'])
    const [, purchases, writeOff, sales, net] = steps
    expect(purchases.value).toBeCloseTo(18420)
    expect(writeOff.value).toBeCloseTo(-(18420 - 13262.4))
    expect(sales.value).toBeCloseTo(-12610)
    expect(net.value).toBeCloseTo(0 + 18420 - (18420 - 13262.4) - 12610)
    expect(net.value).toBeCloseTo(card().onHandDocKg, 0)
    // each step starts where the previous ended
    expect(writeOff.from).toBeCloseTo(purchases.to)
    expect(sales.from).toBeCloseTo(writeOff.to)
    expect(sales.to).toBeCloseTo(net.to)
  })
})

describe('heatmapModel / heatStep', () => {
  it('computes pressure = sold ÷ available and marks overage cells', () => {
    const m = heatmapModel([ledger()])
    expect(m.dates).toEqual(['2026-08-01', '2026-08-02', '2026-08-03'])
    const [d1, d2, d3] = m.rows[0].cells
    expect(d1.pressure).toBeCloseTo(20 / 50)
    expect(d2.pressure).toBeCloseTo(1.5) // sold 30 against 16 available → capped
    expect(d2.overage).toBe(true)
    expect(d3.pressure).toBeCloseTo(40 / (-14 + 50))
    expect(m.rows[0].overageDays).toBe(1)
  })

  it('fills missing dates for a category with empty cells (aligned columns)', () => {
    const other = ledger({ parentCategory: 'PORK', dailyRows: [ledger().dailyRows[0]] })
    const m = heatmapModel([ledger(), other])
    expect(m.rows[1].cells).toHaveLength(3)
    expect(m.rows[1].cells[2]).toMatchObject({ pressure: 0, overage: false })
  })

  it('steps are ordinal and monotonic', () => {
    expect([0, 0.3, 0.6, 0.8, 1, 1.5].map(heatStep)).toEqual([0, 1, 2, 3, 4, 4])
  })
})

describe('dumbbellRows', () => {
  it('reads the right pair per measure and labels the gap as real − documented', () => {
    const c = card()
    expect(dumbbellRows([c], 'purchase', { gel, kg })[0]).toMatchObject({ doc: c.debtDoc, real: c.debtReal, gap: c.debtReal - c.debtDoc })
    expect(dumbbellRows([c], 'sales', { gel, kg })[0]).toMatchObject({ doc: c.salesDocTotal, real: c.salesRealTotal })
    expect(dumbbellRows([c], 'kg', { gel, kg })[0]).toMatchObject({ doc: c.purchaseDocKg, real: c.purchaseRealKg })
    expect(dumbbellRows([c], 'purchase', { gel, kg })[0].detail).toContain('VAT effect')
  })
})

describe('cashGapRows', () => {
  it('merges the two unrendered series by category', () => {
    const dual = {
      purchaseShortages: [{ category: 'BEEF', docKg: 1, docPrice: 1, docTotal: 100, realKg: 1, realPrice: 1, realTotal: 80, gap: 20 }],
      saleSurpluses: [{ category: 'BEEF', docKg: 1, docPrice: 1, docTotal: 200, realKg: 1, realPrice: 1, realTotal: 230, gap: 30 }],
    } as unknown as DualLedger
    const rows = cashGapRows(dual)
    expect(rows).toHaveLength(1)
    expect(rows[0]).toMatchObject({ category: 'BEEF', purchaseShortage: 20, saleSurplus: 30 })
    expect(cashGapRows(undefined)).toEqual([])
  })
})

describe('deriveAlarms', () => {
  it('raises critical alarms for overage days and negative net movement, warnings for VAT diff / exception debt', () => {
    const dashboard = {
      inventoryLedgers: [ledger()],
      exceptions: [{ type: 'MANUAL' }],
      exceptionDebtTotal: 61200,
      realTotals: { excludedEntityCount: 9 },
    } as unknown as AuditDashboard
    const dual = { categoryCards: [card({ onHandDocKg: -18 })] } as unknown as DualLedger
    const alarms = deriveAlarms(dashboard, dual, { gel })
    const byId = Object.fromEntries(alarms.map((a) => [a.id, a]))
    expect(byId['overage']).toMatchObject({ tone: 'crit', count: 1 })
    expect(byId['overage'].text).toContain('Beef 1')
    expect(byId['negative-net']).toMatchObject({ tone: 'crit', count: 1 })
    expect(byId['vat-diff']).toMatchObject({ tone: 'warn', count: 1 })
    expect(byId['exception-debt']).toMatchObject({ tone: 'warn', count: 9 })
    expect(byId['exceptions']).toMatchObject({ tone: 'warn', count: 1 })
    // every alarm points at a section id the page renders
    expect(alarms.every((a) => a.target.startsWith('audit-'))).toBe(true)
  })

  it('is all-clear (ok tone, zero counts) when nothing is wrong', () => {
    const dashboard = { inventoryLedgers: [ledger({ overageDays: 0 })], exceptions: [], exceptionDebtTotal: 0, realTotals: { excludedEntityCount: 0 } } as unknown as AuditDashboard
    const dual = { categoryCards: [card({ onHandDocKg: 5, vatDifference: 0 })] } as unknown as DualLedger
    expect(deriveAlarms(dashboard, dual, { gel }).every((a) => a.tone === 'ok')).toBe(true)
  })
})
