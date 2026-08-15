/**
 * Pure derivations for the audit-control charts (BOR-87 hybrid).
 *
 * Everything the charts draw is computed here from the two payloads the page
 * already fetches — `AuditDashboard` and `DualLedger` — so it can be unit-tested
 * without rendering and so the chart components stay presentational.
 */
import type {
  AuditDashboard,
  CategoryCashGap,
  DualLedger,
  InventoryLedger,
  UnifiedCategoryCard,
} from '@/types/domain'
import { plainCategoryLabel } from './labels'

// ---------------------------------------------------------------------------
// Alarms
// ---------------------------------------------------------------------------

export type AlarmTone = 'crit' | 'warn' | 'ok'

export interface Alarm {
  id: 'overage' | 'negative-net' | 'vat-diff' | 'exceptions' | 'exception-debt'
  tone: AlarmTone
  count: number
  text: string
  /** Element id to jump to (the section that carries the evidence). */
  target: string
}

const nz = (v: number | null | undefined) => (typeof v === 'number' && Number.isFinite(v) ? v : 0)

export function deriveAlarms(
  dashboard: AuditDashboard | undefined,
  dual: DualLedger | undefined,
  format: { gel: (v: number) => string }
): Alarm[] {
  const ledgers = dashboard?.inventoryLedgers ?? []
  const cards = dual?.categoryCards ?? []

  const overageDays = ledgers.reduce((s, l) => s + nz(l.overageDays), 0)
  const overageCats = ledgers.filter((l) => nz(l.overageDays) > 0)
  const negative = cards.filter((c) => nz(c.onHandDocKg) < -0.0005)
  const vatDiffs = cards.filter((c) => Math.abs(nz(c.vatDifference)) >= 0.5)
  const vatTotal = vatDiffs.reduce((s, c) => s + nz(c.vatDifference), 0)
  const exceptions = dashboard?.exceptions?.length ?? 0
  const exceptionDebt = nz(dashboard?.exceptionDebtTotal)

  return [
    {
      id: 'overage',
      tone: overageDays > 0 ? 'crit' : 'ok',
      count: overageDays,
      text:
        overageDays > 0
          ? `overage day${overageDays === 1 ? '' : 's'} (${overageCats
              .map((l) => `${plainCategoryLabel(l.parentCategory)} ${l.overageDays}`)
              .join(', ')})`
          : 'no overage days — inventory conserved every day',
      target: 'audit-heatmap',
    },
    {
      id: 'negative-net',
      tone: negative.length > 0 ? 'crit' : 'ok',
      count: negative.length,
      text:
        negative.length > 0
          ? `negative net movement: ${negative.map((c) => plainCategoryLabel(c.category)).join(', ')}`
          : 'no category sold more than its net purchases',
      target: 'audit-categories',
    },
    {
      id: 'vat-diff',
      tone: vatDiffs.length > 0 ? 'warn' : 'ok',
      count: vatDiffs.length,
      text:
        vatDiffs.length > 0
          ? `categor${vatDiffs.length === 1 ? 'y' : 'ies'} with a doc/real VAT difference (${format.gel(vatTotal)})`
          : 'no documented/real VAT difference',
      target: 'audit-dumbbell',
    },
    {
      id: 'exception-debt',
      tone: exceptionDebt > 0.005 ? 'warn' : 'ok',
      count: dashboard?.realTotals?.excludedEntityCount ?? 0,
      text:
        exceptionDebt > 0.005
          ? `exception (paper) debt ${format.gel(exceptionDebt)} across documentation-only customers`
          : 'no exception debt',
      target: 'audit-reconciliation',
    },
    {
      id: 'exceptions',
      tone: exceptions > 0 ? 'warn' : 'ok',
      count: exceptions,
      text: exceptions > 0 ? `open reconciliation exception${exceptions === 1 ? '' : 's'}` : 'no open exceptions',
      target: 'audit-exceptions',
    },
  ]
}

// ---------------------------------------------------------------------------
// Dumbbell: documented ↔ real per category
// ---------------------------------------------------------------------------

export type DumbbellMeasure = 'purchase' | 'sales' | 'kg'

export interface DumbbellRow {
  category: string
  label: string
  doc: number
  real: number
  gap: number
  detail: string
}

export function dumbbellRows(
  cards: UnifiedCategoryCard[],
  measure: DumbbellMeasure,
  format: { gel: (v: number) => string; kg: (v: number) => string }
): DumbbellRow[] {
  return cards.map((c) => {
    let doc: number, real: number, detail: string
    switch (measure) {
      case 'purchase':
        doc = nz(c.debtDoc)
        real = nz(c.debtReal)
        detail = `VAT effect ${format.gel(nz(c.vatDifference))} (gap × 18/118)`
        break
      case 'sales':
        doc = nz(c.salesDocTotal)
        real = nz(c.salesRealTotal)
        detail = `unreal −${format.kg(nz(c.unrealSalesKg))}, formal −${format.kg(nz(c.formalSalesKg))}, commission ${format.gel(nz(c.formalCommission))}`
        break
      default:
        doc = nz(c.purchaseDocKg)
        real = nz(c.purchaseRealKg)
        detail = `write-off ${nz(c.writeOffPercent)}% → net ${format.kg(nz(c.netDocPurchaseKg))}`
    }
    return { category: c.category, label: plainCategoryLabel(c.category), doc, real, gap: real - doc, detail }
  })
}

// ---------------------------------------------------------------------------
// Heatmap: category × day pressure
// ---------------------------------------------------------------------------

export interface HeatCell {
  category: string
  date: string
  /** documented sold ÷ (start + purchased) that day; 0 when nothing was available. Capped at 1.5. */
  pressure: number
  overage: boolean
  start: number
  purchased: number
  sold: number
  ending: number
}

export interface HeatmapModel {
  dates: string[]
  rows: { category: string; label: string; cells: HeatCell[]; overageDays: number }[]
}

export function heatmapModel(ledgers: InventoryLedger[]): HeatmapModel {
  const dateSet = new Set<string>()
  ledgers.forEach((l) => l.dailyRows.forEach((r) => dateSet.add(r.date)))
  const dates = [...dateSet].sort()
  const rows = ledgers.map((l) => {
    const byDate = new Map(l.dailyRows.map((r) => [r.date, r]))
    const cells = dates.map((date) => {
      const r = byDate.get(date)
      if (!r) {
        return { category: l.parentCategory, date, pressure: 0, overage: false, start: 0, purchased: 0, sold: 0, ending: 0 }
      }
      const available = nz(r.startingInventoryKg) + nz(r.purchasedKg)
      const pressure = available > 0.0005 ? Math.min(nz(r.soldKg) / available, 1.5) : nz(r.soldKg) > 0 ? 1.5 : 0
      return {
        category: l.parentCategory,
        date,
        pressure,
        overage: Boolean(r.overage),
        start: nz(r.startingInventoryKg),
        purchased: nz(r.purchasedKg),
        sold: nz(r.soldKg),
        ending: nz(r.endingInventoryKg),
      }
    })
    return { category: l.parentCategory, label: plainCategoryLabel(l.parentCategory), cells, overageDays: nz(l.overageDays) }
  })
  return { dates, rows }
}

/** Five ordinal steps of one hue; the overage ring is a separate status mark. */
export function heatStep(pressure: number): 0 | 1 | 2 | 3 | 4 {
  if (pressure <= 0.2) return 0
  if (pressure <= 0.45) return 1
  if (pressure <= 0.7) return 2
  if (pressure <= 0.9) return 3
  return 4
}

// ---------------------------------------------------------------------------
// Waterfall: purchases − write-off − sales = net movement
// ---------------------------------------------------------------------------

export interface WaterfallStep {
  label: string
  value: number
  kind: 'unknown' | 'up' | 'down' | 'total'
  /** running level before this step (for up/down), 0 for unknown/total */
  from: number
  to: number
}

export function waterfallSteps(card: UnifiedCategoryCard): WaterfallStep[] {
  const purchases = nz(card.purchaseDocKg)
  const writeOff = purchases - nz(card.netDocPurchaseKg)
  const sales = nz(card.salesDocKg)
  const opening = nz(card.startingInventoryKg)
  const net = opening + purchases - writeOff - sales
  let run = opening
  const steps: WaterfallStep[] = []
  // Opening stock: the API has no physical-stock source yet, so it is 0 by
  // construction. Shown as a distinct "not recorded" step rather than a real bar.
  steps.push({ label: 'Opening', value: opening, kind: 'unknown', from: 0, to: opening })
  steps.push({ label: 'Purchases', value: purchases, kind: 'up', from: run, to: run + purchases })
  run += purchases
  steps.push({ label: `Write-off ${nz(card.writeOffPercent)}%`, value: -writeOff, kind: 'down', from: run, to: run - writeOff })
  run -= writeOff
  steps.push({ label: 'Sales', value: -sales, kind: 'down', from: run, to: run - sales })
  run -= sales
  steps.push({ label: 'Net movement', value: net, kind: 'total', from: 0, to: net })
  return steps
}

// ---------------------------------------------------------------------------
// Cash gap (purchase shortage / sales surplus) — the unrendered payload
// ---------------------------------------------------------------------------

export interface CashGapRow {
  category: string
  label: string
  purchaseShortage: number
  saleSurplus: number
  purchase?: CategoryCashGap
  sale?: CategoryCashGap
}

export function cashGapRows(dual: DualLedger | undefined): CashGapRow[] {
  if (!dual) return []
  const byCat = new Map<string, CashGapRow>()
  const get = (category: string) => {
    let row = byCat.get(category)
    if (!row) {
      row = { category, label: plainCategoryLabel(category), purchaseShortage: 0, saleSurplus: 0 }
      byCat.set(category, row)
    }
    return row
  }
  ;(dual.purchaseShortages ?? []).forEach((g) => {
    const row = get(g.category)
    row.purchaseShortage = nz(g.gap)
    row.purchase = g
  })
  ;(dual.saleSurpluses ?? []).forEach((g) => {
    const row = get(g.category)
    row.saleSurplus = nz(g.gap)
    row.sale = g
  })
  return [...byCat.values()].filter((r) => r.purchaseShortage !== 0 || r.saleSurplus !== 0 || r.purchase || r.sale)
}
