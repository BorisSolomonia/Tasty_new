/**
 * 01 — Executive Control Tower.
 *
 * Three flows first, then the reconciliation each one owes, then the rules that
 * fired. Everything on this screen comes from the shared payload; nothing is
 * fetched here.
 */
import { Skeleton } from '@/components/ui/skeleton'
import { useAudit } from '../audit-context'
import { ThreeFlowStrip } from '../three-flow-strip'
import { MetricRow, SectionCard, FormulaNote } from '../metric'
import { AlertList } from '../alert-list'
import { RealStockCell } from '../real-stock-cell'
import { fmtCount, fmtGel, fmtKgSigned, gapTone, toneClass } from '../format'

export function ExecutiveControlTower() {
  const { flows, flowsQuery } = useAudit()

  const inventory = flows?.inventory ?? null
  const cash = flows?.cash ?? null
  const documentation = flows?.documentation ?? null

  const topProducts = [...(inventory?.products ?? [])]
    .sort((a, b) => Math.abs(b.gapKg ?? 0) - Math.abs(a.gapKg ?? 0))
    .slice(0, 8)

  return (
    <div className="space-y-4">
      <ThreeFlowStrip />

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <SectionCard
          title="Inventory reconciliation"
          subtitle="Document stock against manually confirmed real stock. Real stock is an input, not a derivation."
        >
          {flowsQuery.isLoading ? (
            <Skeleton className="h-40 w-full" />
          ) : topProducts.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              No product rows in this period.
            </p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-xs">
                <thead>
                  <tr className="border-b border-border text-left text-muted-foreground">
                    <th className="py-1.5 pr-2 font-semibold">Product</th>
                    <th className="py-1.5 pr-2 text-right font-semibold">Doc stock</th>
                    <th className="py-1.5 pr-2 text-right font-semibold">Real stock</th>
                    <th className="py-1.5 text-right font-semibold">Gap</th>
                  </tr>
                </thead>
                <tbody>
                  {topProducts.map((product) => (
                    <tr
                      key={product.productName ?? Math.random()}
                      className="border-b border-border/70"
                    >
                      <td className="py-1.5 pr-2">
                        <div className="max-w-[12rem] truncate font-medium" title={product.productName ?? ''}>
                          {product.productName ?? '—'}
                        </div>
                        {product.category ? (
                          <div className="text-[11px] text-muted-foreground">{product.category}</div>
                        ) : null}
                      </td>
                      <td className="py-1.5 pr-2 text-right tabular-nums">
                        {fmtKgSigned(product.documentStockKg)}
                      </td>
                      <td className="py-1.5 pr-2 text-right">
                        <RealStockCell product={product} />
                      </td>
                      <td
                        className={`py-1.5 text-right font-semibold tabular-nums ${toneClass[gapTone(product.gapKg)]}`}
                      >
                        {fmtKgSigned(product.gapKg)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <FormulaNote>
                Gap = document stock − real stock. Showing the {topProducts.length} largest of{' '}
                {fmtCount(inventory?.products?.length ?? 0)} products; the strip above totals all of them.
              </FormulaNote>
            </div>
          )}
        </SectionCard>

        <SectionCard
          title="Cash reconciliation"
          subtitle="What the bank did, what evidence exists, and what neither explains."
        >
          <MetricRow
            label="Bank cash withdrawals"
            value={fmtGel(cash?.cashWithdrawals)}
          />
          <MetricRow
            label="Withdrawals mapped to a purpose"
            value={fmtGel(cash?.mappedWithdrawalAmount)}
          />
          <MetricRow
            label="Unresolved withdrawals"
            value={fmtGel(cash?.unresolvedWithdrawalAmount)}
            tone={gapTone(cash?.unresolvedWithdrawalAmount)}
            drilldownKey="cash.unresolvedWithdrawals"
          />
          <MetricRow
            label="Checks / payment evidence on hand"
            value={fmtGel(cash?.checksOnHand)}
            hint="Evidence, not money"
            drilldownKey="cash.unsupportedChecks"
          />
          <MetricRow
            label="Checks supported by real money"
            value={fmtGel(cash?.checksSupportedByRealMoney)}
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
          {cash?.coverageBreach ? (
            <p className="mt-3 rounded-md border border-destructive bg-destructive/10 p-2 text-xs font-semibold text-destructive">
              Supplier coverage control breached by {fmtGel(cash.excessOverDocumentedPurchases)} — see
              the Cash-First Treasury view.
            </p>
          ) : null}
        </SectionCard>
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <SectionCard
          title="Documentation reconciliation"
          subtitle="RS.ge documents whose two consequences — stock and paper cash — are not both supported."
        >
          <MetricRow
            label="Paper-only sales"
            value={fmtGel(documentation?.paperOnlySalesValue)}
            hint={`${fmtCount(documentation?.paperOnlySalesCount)} documents`}
            tone={gapTone(documentation?.paperOnlySalesValue)}
            drilldownKey="documentation.paperOnlySales"
          />
          <MetricRow
            label="Paper-only customer payments"
            value={fmtGel(documentation?.paperOnlyCustomerPaymentValue)}
            hint={`${fmtCount(documentation?.paperOnlyCustomerPaymentCount)} documents`}
            tone={gapTone(documentation?.paperOnlyCustomerPaymentValue)}
            drilldownKey="documentation.paperOnlyReceipts"
          />
          <MetricRow
            label="Unsupported supplier documents"
            value={fmtGel(documentation?.unsupportedSupplierDocumentValue)}
            hint={`${fmtCount(documentation?.unsupportedSupplierDocumentCount)} documents`}
            tone={gapTone(documentation?.unsupportedSupplierDocumentValue)}
          />
          <MetricRow
            label="Unmapped RS.ge rows"
            value={fmtCount(documentation?.unmappedDocumentRowCount)}
            hint={`${fmtGel(documentation?.unmappedDocumentValue)} of document value`}
            drilldownKey="documentation.unmapped"
          />
          <MetricRow
            label="Document rows in period"
            value={fmtCount(documentation?.documentRowCount)}
            drilldownKey="documentation.rows"
          />
        </SectionCard>

        <SectionCard
          title="Top audit cases"
          subtitle="Ranked by severity, then by amount. Each carries the formula it fired on."
        >
          {flowsQuery.isLoading ? (
            <Skeleton className="h-40 w-full" />
          ) : (
            <AlertList alerts={flows?.alerts} limit={5} />
          )}
        </SectionCard>
      </div>
    </div>
  )
}
