/**
 * Inventory — the product reconciliation matrix (old view 04), unchanged in
 * substance: documented movement per product, the confirmed reality beside it,
 * and the cash and document consequences the backend attributes to it.
 *
 * Its two "linked" cards are kept, but as links rather than as copies. The
 * figures they used to reprint — unresolved withdrawals, unsupported checks,
 * net unexplained paper cash, document purchases and sales — each have exactly
 * one home elsewhere on this page, and the whole point of the consolidation is
 * that the reader can be sent there instead of shown a second instance.
 *
 * The one number computed here rather than copied is Σ related cash gap, which
 * is the sum of the matrix's own column and exists nowhere else.
 */
import * as React from 'react'
import { ArrowDown, ArrowUp } from 'lucide-react'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/cn'
import type { AuditProductRow } from '@/lib/audit-api'
import { useAudit } from '../audit-context'
import { CollapsiblePanel } from '../collapsible-panel'
import { SectionEvidence } from '../evidence-panel'
import { MetricRow, SectionCard, FormulaNote } from '../metric'
import { RealStockCell } from '../real-stock-cell'
import { inventoryGapKey } from '../drilldown-keys'
import { JumpLink } from '../section-nav'
import { fmtCount, fmtGel, fmtKgSigned, fmtPercent, gapTone, toneClass } from '../format'

type SortKey =
  | 'productName'
  | 'purchaseKg'
  | 'saleKg'
  | 'writeOffKg'
  | 'documentStockKg'
  | 'gapKg'
  | 'relatedCashGap'
  | 'flaggedDocumentCount'

export function InventorySection() {
  const { flows, flowsQuery, showEvidence } = useAudit()
  const [search, setSearch] = React.useState('')
  const [sortKey, setSortKey] = React.useState<SortKey>('gapKg')
  const [descending, setDescending] = React.useState(true)

  const inventory = flows?.inventory ?? null

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
    <th className={cn('py-1 pr-2 font-semibold', alignRight && 'text-right')}>
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
    <div className="space-y-3">
      <SectionEvidence section="inventory" />

      <CollapsiblePanel
        title="Product reconciliation matrix"
        summary={`${fmtCount(rows.length)} of ${fmtCount(
          inventory?.products?.length
        )} products · gap ${fmtKgSigned(inventory?.gapKg)}`}
        subtitle="Documented movement per product, the confirmed reality beside it, and the cash and document consequences attributed to it."
        actions={
          <Input
            value={search}
            placeholder="Filter products"
            className="h-7 w-36 text-[11px]"
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
            <table className="w-full text-[11px]">
              <thead>
                <tr className="border-b border-border text-left text-muted-foreground">
                  {header('productName', 'Product', false)}
                  {header('purchaseKg', 'Purchase kg')}
                  {header('saleKg', 'Sale kg')}
                  {header('writeOffKg', 'Write-off kg')}
                  <th className="py-1 pr-2 text-right font-semibold">Write-off %</th>
                  {header('documentStockKg', 'Doc stock')}
                  <th className="py-1 pr-2 text-right font-semibold">Real stock</th>
                  {header('gapKg', 'Gap kg')}
                  {header('relatedCashGap', 'Related cash gap')}
                  {header('flaggedDocumentCount', 'Flagged docs')}
                </tr>
              </thead>
              <tbody>
                {rows.map((product, index) => (
                  // A stable key. `Math.random()` here remounted every row whose
                  // productName was null on each render, wiping the operator's
                  // half-typed real-stock entry (BOR-82 finding FE-6).
                  <ProductRow key={product.productName ?? `__unnamed_${index}`} product={product} />
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
      </CollapsiblePanel>

      <div className="grid grid-cols-1 gap-3 lg:grid-cols-3">
        <SectionCard
          title="Period totals"
          subtitle="The stock side of this period, summed across every product above."
        >
          <MetricRow label="Document purchases" value={fmtKgSigned(inventory?.documentPurchaseKg)} />
          <MetricRow label="Document sales" value={fmtKgSigned(inventory?.documentSaleKg)} />
          <MetricRow
            label="Write-offs in the stock arithmetic"
            value={fmtKgSigned(inventory?.documentWriteOffKg)}
            hint="The documentation section reports its own count of the same events"
          />
          <MetricRow label="Document stock" value={fmtKgSigned(inventory?.documentStockKg)} />
          <MetricRow label="Real stock (confirmed)" value={fmtKgSigned(inventory?.realStockKg)} />
          <MetricRow
            label="Gap"
            value={fmtKgSigned(inventory?.gapKg)}
            tone={gapTone(inventory?.gapKg)}
          />
          <MetricRow
            label="Products with a positive gap"
            value={fmtCount(inventory?.positiveGapProducts)}
          />
          <MetricRow
            label="Products with a negative gap"
            value={fmtCount(inventory?.negativeGapProducts)}
          />
        </SectionCard>

        <SectionCard
          title="Cash linked to inventory anomalies"
          subtitle="The money side of the same period. Linked rather than reprinted — each figure has one home."
        >
          <MetricRow
            label="Σ related cash gap across products"
            value={fmtGel(
              (inventory?.products ?? []).reduce(
                (sum, product) => sum + (product.relatedCashGap ?? 0),
                0
              )
            )}
            hint="Sum of the per-product attribution in the matrix above"
          />
          <ul className="mt-3 space-y-1.5">
            <li>
              <JumpLink to="cash">Unresolved withdrawals and unmapped inflows</JumpLink>
            </li>
            <li>
              <JumpLink to="cash">Unsupported checks and supplier settlement</JumpLink>
            </li>
            <li>
              <JumpLink to="cash">Net unexplained paper cash</JumpLink>
            </li>
          </ul>
          <FormulaNote>
            Those figures live in the cash section and are shown there once. A product&apos;s own
            share of them is the “related cash gap” column above.
          </FormulaNote>
        </SectionCard>

        <SectionCard
          title="Documentation linked to inventory anomalies"
          subtitle="Which documents produced the stock this matrix is arguing about."
        >
          {/*
            Named "Σ … across products" on purpose. The documentation section
            reports its own flagged-document count for the period; this is the
            sum of the matrix column, which is the backend's attribution of
            those documents to products. Two different figures must not share
            one label.
          */}
          <MetricRow
            label="Σ flagged documents across products"
            value={fmtCount(
              (inventory?.products ?? []).reduce(
                (sum, product) => sum + (product.flaggedDocumentCount ?? 0),
                0
              )
            )}
            hint="Σ of the flagged-document column above, attributed per product"
          />
          <ul className="mt-3 space-y-1.5">
            <li>
              <JumpLink to="documentation">Purchase and sales document values</JumpLink>
            </li>
            <li>
              <JumpLink to="documentation">Unmapped RS.ge rows</JumpLink>
            </li>
          </ul>
          <button
            type="button"
            className="mt-3 text-xs text-primary hover:underline"
            onClick={() =>
              showEvidence({ key: 'documentation.unmapped', label: 'Unmapped RS.ge rows' })
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
  const { showEvidence } = useAudit()
  // The gap drill-downs are split by sign, and the backend filters them by
  // product through `subject`.
  const gapKey = inventoryGapKey(product.gapKg)

  return (
    <tr className="border-b border-border/70">
      <td className="py-1 pr-2">
        <button
          type="button"
          className="max-w-[14rem] truncate text-left font-medium hover:underline disabled:no-underline disabled:opacity-100"
          title={gapKey ? product.productName ?? '' : 'No gap figure for this product to expand'}
          disabled={!gapKey}
          onClick={() =>
            gapKey &&
            showEvidence({
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
      <td className="py-1 pr-2 text-right tabular-nums">{fmtKgSigned(product.purchaseKg)}</td>
      <td className="py-1 pr-2 text-right tabular-nums">{fmtKgSigned(product.saleKg)}</td>
      <td className="py-1 pr-2 text-right tabular-nums">{fmtKgSigned(product.writeOffKg)}</td>
      <td className="py-1 pr-2 text-right tabular-nums">{fmtPercent(product.writeOffPercent)}</td>
      <td className="py-1 pr-2 text-right tabular-nums">{fmtKgSigned(product.documentStockKg)}</td>
      <td className="py-1 pr-2 text-right">
        <RealStockCell product={product} />
      </td>
      <td className={cn('py-1 pr-2 text-right font-semibold tabular-nums', toneClass[gapTone(product.gapKg)])}>
        {fmtKgSigned(product.gapKg)}
      </td>
      <td className={cn('py-1 pr-2 text-right tabular-nums', toneClass[gapTone(product.relatedCashGap)])}>
        {fmtGel(product.relatedCashGap)}
      </td>
      <td className="py-1 text-right tabular-nums">{fmtCount(product.flaggedDocumentCount)}</td>
    </tr>
  )
}
