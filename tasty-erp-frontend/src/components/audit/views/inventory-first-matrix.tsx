/**
 * 04 — Inventory-First Matrix.
 *
 * Inventory dominates the screen, but cash and documentation stay present and
 * reconcilable: the matrix carries the cash gap and flagged-document count that
 * each product is responsible for, so the other two flows are never off-screen.
 */
import * as React from 'react'
import { ArrowDown, ArrowUp } from 'lucide-react'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/cn'
import type { AuditProductRow } from '@/lib/audit-api'
import { useAudit } from '../audit-context'
import { ThreeFlowStrip } from '../three-flow-strip'
import { MetricRow, SectionCard, FormulaNote } from '../metric'
import { RealStockCell } from '../real-stock-cell'
import { inventoryGapKey } from '../drilldown-keys'
import { fmtCount, fmtGel, fmtKgSigned, fmtPercent, gapTone, toneClass } from '../format'

type SortKey = 'productName' | 'purchaseKg' | 'saleKg' | 'writeOffKg' | 'documentStockKg' | 'gapKg' | 'relatedCashGap' | 'flaggedDocumentCount'

export function InventoryFirstMatrix() {
  const { flows, flowsQuery, openDrilldown } = useAudit()
  const [search, setSearch] = React.useState('')
  const [sortKey, setSortKey] = React.useState<SortKey>('gapKg')
  const [descending, setDescending] = React.useState(true)

  const inventory = flows?.inventory ?? null
  const cash = flows?.cash ?? null
  const documentation = flows?.documentation ?? null

  const rows = React.useMemo(() => {
    const products = inventory?.products ?? []
    const filtered = search.trim()
      ? products.filter((product) =>
          (product.productName ?? '').toLowerCase().includes(search.trim().toLowerCase())
        )
      : products
    const sorted = [...filtered].sort((a, b) => {
      if (sortKey === 'productName') {
        return (a.productName ?? '').localeCompare(b.productName ?? '')
      }
      const aValue = Math.abs(Number(a[sortKey] ?? 0))
      const bValue = Math.abs(Number(b[sortKey] ?? 0))
      return aValue - bValue
    })
    return descending ? sorted.reverse() : sorted
  }, [inventory, search, sortKey, descending])

  const toggleSort = (key: SortKey) => {
    if (key === sortKey) setDescending((current) => !current)
    else {
      setSortKey(key)
      setDescending(true)
    }
  }

  const header = (key: SortKey, label: string, alignRight = true) => (
    <th className={cn('py-1.5 pr-2 font-semibold', alignRight && 'text-right')}>
      <button
        type="button"
        className={cn('inline-flex items-center gap-1 hover:text-foreground', alignRight && 'justify-end')}
        onClick={() => toggleSort(key)}
      >
        {label}
        {sortKey === key ? (
          descending ? <ArrowDown className="h-3 w-3" /> : <ArrowUp className="h-3 w-3" />
        ) : null}
      </button>
    </th>
  )

  return (
    <div className="space-y-4">
      <ThreeFlowStrip />

      <SectionCard
        title="Product reconciliation matrix"
        subtitle="Documented movement per product, the confirmed reality beside it, and the cash and document consequences attributed to it."
        actions={
          <Input
            value={search}
            placeholder="Filter products"
            className="h-8 w-44"
            onChange={(event) => setSearch(event.target.value)}
          />
        }
      >
        {flowsQuery.isLoading ? (
          <Skeleton className="h-64 w-full" />
        ) : rows.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            {inventory?.products?.length
              ? 'No product matches this filter.'
              : 'The inventory flow returned no product rows for this period.'}
          </p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr className="border-b border-border text-left text-muted-foreground">
                  {header('productName', 'Product', false)}
                  {header('purchaseKg', 'Purchase kg')}
                  {header('saleKg', 'Sale kg')}
                  {header('writeOffKg', 'Write-off kg')}
                  <th className="py-1.5 pr-2 text-right font-semibold">Write-off %</th>
                  {header('documentStockKg', 'Doc stock')}
                  <th className="py-1.5 pr-2 text-right font-semibold">Real stock</th>
                  {header('gapKg', 'Gap kg')}
                  {header('relatedCashGap', 'Related cash gap')}
                  {header('flaggedDocumentCount', 'Flagged docs')}
                </tr>
              </thead>
              <tbody>
                {rows.map((product) => (
                  <ProductRow key={product.productName ?? Math.random()} product={product} />
                ))}
              </tbody>
            </table>
            <FormulaNote>
              Gap kg = document stock − real stock. Write-off % is the rate actually applied, as sent
              by the backend — it is not recomputed here. Related cash gap and flagged documents are
              the backend&apos;s attribution of the other two flows to this product.
            </FormulaNote>
          </div>
        )}
      </SectionCard>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <SectionCard
          title="Cash linked to inventory anomalies"
          subtitle="The money side of the same period. Present, not hidden behind the matrix."
        >
          <MetricRow
            label="Σ related cash gap across products"
            value={fmtGel(
              (inventory?.products ?? []).reduce((sum, product) => sum + (product.relatedCashGap ?? 0), 0)
            )}
            hint="Sum of the per-product attribution in the matrix above"
          />
          <MetricRow
            label="Unresolved withdrawals"
            value={fmtGel(cash?.unresolvedWithdrawalAmount)}
            tone={gapTone(cash?.unresolvedWithdrawalAmount)}
            drilldownKey="cash.unresolvedWithdrawals"
          />
          <MetricRow
            label="Supplier-allocated cash settlements"
            value={fmtGel(cash?.supplierAllocatedCashSettlements)}
            drilldownKey="cash.supplierSettlement"
          />
          <MetricRow
            label="Unsupported checks"
            value={fmtGel(cash?.unsupportedChecks)}
            tone={gapTone(cash?.unsupportedChecks)}
            drilldownKey="cash.unsupportedChecks"
          />
          <MetricRow
            label="Net unexplained paper cash"
            value={fmtGel(cash?.netUnexplainedPaperCash)}
            tone={gapTone(cash?.netUnexplainedPaperCash)}
            drilldownKey="cash.paperCash"
          />
        </SectionCard>

        <SectionCard
          title="Documentation linked to inventory anomalies"
          subtitle="Which documents produced the stock the matrix is arguing about."
        >
          <MetricRow
            label="Document purchases"
            value={fmtKgSigned(documentation?.documentPurchaseKg)}
            hint={fmtGel(documentation?.documentPurchaseValue)}
          />
          <MetricRow
            label="Document sales"
            value={fmtKgSigned(documentation?.documentSalesKg)}
            hint={fmtGel(documentation?.documentSalesValue)}
          />
          <MetricRow
            label="Write-offs"
            value={fmtKgSigned(documentation?.writeOffKg)}
          />
          <MetricRow
            label="Flagged documents"
            value={fmtCount(documentation?.flaggedDocumentCount)}
            hint={fmtGel(documentation?.flaggedDocumentValue)}
            tone={gapTone(documentation?.flaggedDocumentValue)}
          />
          <MetricRow
            label="Individually unmapped rows"
            value={fmtCount(documentation?.unmappedDocumentRowCount)}
            tone={documentation && documentation.unmappedDocumentRowCount > 0 ? 'warn' : 'neutral'}
            drilldownKey="documentation.unmapped"
          />
          <button
            type="button"
            className="mt-3 text-xs text-primary hover:underline"
            onClick={() =>
              openDrilldown({
                key: 'documentation.unmapped',
                label: 'Unmapped RS.ge rows',
              })
            }
          >
            Open the unmapped rows and classify them →
          </button>
        </SectionCard>
      </div>
    </div>
  )
}

function ProductRow({ product }: { product: AuditProductRow }) {
  const { openDrilldown } = useAudit()
  // The gap drill-downs are split by sign, and the backend filters them by
  // product through `subject`.
  const gapKey = inventoryGapKey(product.gapKg)

  return (
    <tr className="border-b border-border/70">
      <td className="py-1.5 pr-2">
        <button
          type="button"
          className="max-w-[14rem] truncate text-left font-medium hover:underline disabled:no-underline disabled:opacity-100"
          title={gapKey ? product.productName ?? '' : 'No gap figure for this product to expand'}
          disabled={!gapKey}
          onClick={() =>
            gapKey &&
            openDrilldown({
              key: gapKey,
              subject: product.productName ?? undefined,
              label: `Inventory gap — ${product.productName ?? ''}`,
            })
          }
        >
          {product.productName ?? '—'}
        </button>
        {product.category ? (
          <div className="text-[11px] text-muted-foreground">{product.category}</div>
        ) : null}
      </td>
      <td className="py-1.5 pr-2 text-right tabular-nums">{fmtKgSigned(product.purchaseKg)}</td>
      <td className="py-1.5 pr-2 text-right tabular-nums">{fmtKgSigned(product.saleKg)}</td>
      <td className="py-1.5 pr-2 text-right tabular-nums">{fmtKgSigned(product.writeOffKg)}</td>
      <td className="py-1.5 pr-2 text-right tabular-nums">{fmtPercent(product.writeOffPercent)}</td>
      <td className="py-1.5 pr-2 text-right tabular-nums">{fmtKgSigned(product.documentStockKg)}</td>
      <td className="py-1.5 pr-2 text-right">
        <RealStockCell product={product} />
      </td>
      <td className={cn('py-1.5 pr-2 text-right font-semibold tabular-nums', toneClass[gapTone(product.gapKg)])}>
        {fmtKgSigned(product.gapKg)}
      </td>
      <td className={cn('py-1.5 pr-2 text-right tabular-nums', toneClass[gapTone(product.relatedCashGap)])}>
        {fmtGel(product.relatedCashGap)}
      </td>
      <td className="py-1.5 text-right tabular-nums">{fmtCount(product.flaggedDocumentCount)}</td>
    </tr>
  )
}
