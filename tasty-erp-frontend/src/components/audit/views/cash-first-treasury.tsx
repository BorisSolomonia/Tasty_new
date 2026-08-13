/**
 * 05 — Cash-First Treasury & Paper-Cash Control.
 *
 * Cash dominates, and the screen answers four questions at once: what the bank
 * actually did, what evidence exists that is not money, what paper-only sales
 * and payments did to accounting cash, and whether supplier settlement plus
 * asserted debt is covered by documented purchases.
 *
 * The coverage control deliberately reads `supplierSettlementAndDebt`, not
 * total withdrawals: a withdrawal does not prove a supplier was settled.
 */
import * as React from 'react'
import { AlertOctagon } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import type { AuditSourceRow } from '@/lib/audit-api'
import { useAudit } from '../audit-context'
import { ThreeFlowStrip } from '../three-flow-strip'
import { MetricRow, SectionCard, FormulaNote } from '../metric'
import { StatusBadge } from '../status-badge'
import { SupplierDebtPanel } from '../supplier-debt-panel'
import { MappingDialog } from '../mapping-dialog'
import { FeedCapNotice } from '../feed-cap-notice'
import { isUnresolved } from '../cross-flow'
import { fmtCount, fmtGel, fmtKgSigned, fmtDate, fmtText, gapTone } from '../format'

export function CashFirstTreasury() {
  const { flows, sourceRows, sourceRowsQuery, openDrilldown } = useAudit()
  const [mappingRow, setMappingRow] = React.useState<AuditSourceRow | null>(null)

  const cash = flows?.cash ?? null
  const inventory = flows?.inventory ?? null
  const documentation = flows?.documentation ?? null

  const bankRowsToMap = sourceRows
    .filter((row) => row.sourceType === 'BANK' && isUnresolved(row))
    .slice(0, 25)

  return (
    <div className="space-y-4">
      <ThreeFlowStrip />

      <FeedCapNotice />

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        {/* 1 ---------------------------------------------------------------- */}
        <SectionCard
          title="1. Bank-to-real-cash bridge"
          subtitle="What left the bank, what it was allocated to, and what evidence exists for the rest."
        >
          <MetricRow label="Bank inflows" value={fmtGel(cash?.bankInflow)} />
          <MetricRow label="Bank outflows" value={fmtGel(cash?.bankOutflow)} />
          <MetricRow
            label="Total cash withdrawals"
            value={fmtGel(cash?.cashWithdrawals)}
          />
          <MetricRow
            label="Allocated to supplier settlement"
            value={fmtGel(cash?.supplierAllocatedCashSettlements)}
            drilldownKey="cash.supplierSettlement"
          />
          <MetricRow
            label="Allocated to other spending"
            value={fmtGel(cash?.nonSupplierExpenses)}
          />
          <MetricRow
            label="Returned / redeposited"
            value={fmtGel(cash?.cashReturnedOrRedeposited)}
          />
          <MetricRow
            label="Unresolved withdrawals"
            value={fmtGel(cash?.unresolvedWithdrawalAmount)}
            tone={gapTone(cash?.unresolvedWithdrawalAmount)}
            drilldownKey="cash.unresolvedWithdrawals"
          />
          <MetricRow
            label="Unmapped bank inflows"
            value={fmtGel(cash?.unmappedInflowAmount)}
            hint={`${fmtCount(cash?.unmappedInflowCount)} money-in rows nobody has classified`}
            tone={gapTone(cash?.unmappedInflowAmount)}
            drilldownKey="cash.unmappedInflows"
          />

          <div className="mt-3 border-t border-border pt-2">
            <MetricRow
              label="Direct bank payments to suppliers"
              value={fmtGel(cash?.directBankSupplierPayments)}
            />
            <MetricRow
              label="Customer bank receipts"
              value={fmtGel(cash?.customerBankReceipts)}
            />
            <MetricRow
              label="Real customer cash receipts"
              value={fmtGel(cash?.realCustomerCashReceipts)}
            />
            <MetricRow label="Other income" value={fmtGel(cash?.otherIncome)} />
            <MetricRow
              label="Refunds and reversals"
              value={fmtGel(cash?.refundsAndReversals)}
            />
          </div>

          <div className="mt-3 border-t border-border pt-2">
            <MetricRow
              label="Checks / payment evidence on hand"
              value={fmtGel(cash?.checksOnHand)}
              hint="Evidence is not money"
              drilldownKey="cash.unsupportedChecks"
            />
            <MetricRow
              label="Checks supported by real movement"
              value={fmtGel(cash?.checksSupportedByRealMoney)}
            />
            <MetricRow
              label="Unsupported checks"
              value={fmtGel(cash?.unsupportedChecks)}
              tone={gapTone(cash?.unsupportedChecks)}
              hint={`${fmtCount(cash?.unclassifiedCheckCount)} still unclassified`}
              drilldownKey="cash.unsupportedChecks"
            />
          </div>
        </SectionCard>

        {/* 2 ---------------------------------------------------------------- */}
        <SectionCard
          title="2. Paper-cash bridge"
          subtitle="Documents can move accounting cash without money moving. Both directions stay visible."
        >
          <MetricRow
            label="On-paper sales"
            value={fmtGel(cash?.onPaperSalesValue)}
            tone={gapTone(cash?.onPaperSalesValue)}
            drilldownKey="documentation.paperOnlySales"
          />
          <MetricRow
            label="On-paper customer receipts"
            value={fmtGel(cash?.onPaperCustomerReceiptValue)}
            drilldownKey="documentation.paperOnlyReceipts"
          />
          <MetricRow
            label="Real money received against those sales"
            value={fmtGel(cash?.realMoneyReceivedAgainstPaperSales)}
          />
          <MetricRow
            label="Paper cash created"
            value={fmtGel(cash?.paperCashCreated)}
            tone={gapTone(cash?.paperCashCreated)}
            drilldownKey="cash.paperCash"
          />

          <div className="mt-3 border-t border-border pt-2">
            <MetricRow
              label="On-paper supplier payments"
              value={fmtGel(cash?.onPaperSupplierPaymentValue)}
            />
            <MetricRow
              label="Real money supporting them"
              value={fmtGel(cash?.realMoneySupportingSupplierPayments)}
            />
            <MetricRow
              label="Paper cash reduced"
              value={fmtGel(cash?.paperCashReduced)}
              tone={gapTone(cash?.paperCashReduced)}
            />
          </div>

          <div className="mt-3 border-t border-border pt-2">
            <MetricRow
              label="Net unexplained paper cash"
              value={fmtGel(cash?.netUnexplainedPaperCash)}
              tone={gapTone(cash?.netUnexplainedPaperCash)}
              drilldownKey="cash.paperCash"
            />
          </div>

          <FormulaNote>
            On-paper sales and receipts can increase accounting cash with no real money arriving.
            On-paper supplier payments and checks can reduce it with no real money leaving. Net
            unexplained paper cash is what neither side accounts for.
          </FormulaNote>
        </SectionCard>
      </div>

      {/* 3 ------------------------------------------------------------------ */}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <SectionCard
          title="3. Supplier purchase coverage control"
          subtitle="Settlement plus asserted debt, measured against documented purchases."
        >
          {cash?.coverageBreach ? (
            <div className="mb-3 flex items-start gap-2 rounded-md border border-destructive bg-destructive/10 p-3 text-sm text-destructive">
              <AlertOctagon className="mt-0.5 h-4 w-4 shrink-0" />
              <div>
                <p className="font-semibold">
                  Breach: settlement + debt exceeds documented purchases by{' '}
                  {fmtGel(cash.excessOverDocumentedPurchases)}
                </p>
                {cash.coverageBreachSubjects && cash.coverageBreachSubjects.length > 0 ? (
                  <p className="mt-1 text-xs">
                    Responsible counterparties: {cash.coverageBreachSubjects.join(', ')}
                  </p>
                ) : (
                  <p className="mt-1 text-xs">The rule named no counterparty for this breach.</p>
                )}
              </div>
            </div>
          ) : (
            <p className="mb-3 rounded-md border border-border bg-muted/40 p-2 text-xs text-muted-foreground">
              {cash
                ? 'No breach reported for this period. A positive uncovered balance below is not by itself wrong.'
                : 'The cash flow was not returned, so the control has no verdict — read this as unknown.'}
            </p>
          )}

          <MetricRow
            label="Documented purchases from suppliers"
            value={fmtGel(cash?.documentedSupplierPurchases)}
          />
          <MetricRow
            label="Direct bank supplier payments"
            value={fmtGel(cash?.directBankSupplierPayments)}
          />
          <MetricRow
            label="Supplier-allocated cash settlements"
            value={fmtGel(cash?.supplierAllocatedCashSettlements)}
            drilldownKey="cash.supplierSettlement"
          />
          <MetricRow
            label="Real outstanding supplier debt"
            value={fmtGel(cash?.realOutstandingSupplierDebt)}
            hint="Asserted by an operator, per supplier, below"
          />
          <MetricRow
            label="Settlement + debt"
            value={fmtGel(cash?.supplierSettlementAndDebt)}
          />
          <MetricRow
            label="Excess over documented purchases"
            value={fmtGel(cash?.excessOverDocumentedPurchases)}
            tone={gapTone(cash?.excessOverDocumentedPurchases)}
            hint="Positive only when the control is breached"
          />
          <MetricRow
            label="Uncovered purchase balance"
            value={fmtGel(cash?.uncoveredPurchaseBalance)}
            hint="Documented purchases − settlement & debt"
          />

          <FormulaNote>
            Total withdrawals are deliberately not an input here. Only the portion mapped to supplier
            settlement counts, because a withdrawal does not prove a supplier was settled.
          </FormulaNote>

          <div className="mt-3 border-t border-border pt-3">
            <div className="mb-2 text-xs font-semibold text-muted-foreground">
              Real outstanding debt per supplier — a manual input
            </div>
            <SupplierDebtPanel />
          </div>
        </SectionCard>

        {/* 4 ---------------------------------------------------------------- */}
        <SectionCard
          title="4. Bank rows requiring mapping"
          subtitle={`${fmtCount(cash?.unmappedInflowCount)} unmapped inflows (${fmtGel(
            cash?.unmappedInflowAmount
          )}) · ${fmtCount(cash?.unmappedOutflowCount)} unmapped outflows (${fmtGel(
            cash?.unmappedOutflowAmount
          )}) of ${fmtCount(cash?.bankRowCount)} bank rows`}
          actions={
            <button
              type="button"
              className="text-xs text-primary hover:underline"
              onClick={() => openDrilldown({ key: 'cash.bankRows', label: 'All bank statement rows' })}
            >
              Browse all bank rows
            </button>
          }
        >
          {sourceRowsQuery.isLoading ? (
            <Skeleton className="h-32 w-full" />
          ) : bankRowsToMap.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              The shared row feed returned no unresolved bank rows for this period.
            </p>
          ) : (
            <div className="divide-y divide-border">
              {bankRowsToMap.map((row) => (
                <div key={`${row.sourceRowId}`} className="flex items-center gap-2 py-2 text-xs">
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-baseline gap-2">
                      <span className="font-semibold tabular-nums">{fmtGel(row.amount)}</span>
                      <span className="text-muted-foreground">{fmtDate(row.date)}</span>
                      <span className="text-muted-foreground">{fmtText(row.direction)}</span>
                    </div>
                    <div className="truncate text-muted-foreground" title={row.description ?? ''}>
                      {fmtText(row.description)}
                    </div>
                  </div>
                  <StatusBadge status={row.status} />
                  <Button
                    type="button"
                    size="sm"
                    variant="outline"
                    className="h-7"
                    onClick={() => setMappingRow(row)}
                  >
                    Map
                  </Button>
                </div>
              ))}
            </div>
          )}
        </SectionCard>
      </div>

      {/* 5 — the other two flows, mandatory on every view -------------------- */}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <SectionCard title="Inventory consequence" subtitle="The stock side of the same period.">
          <MetricRow
            label="Document stock"
            value={fmtKgSigned(inventory?.documentStockKg)}
          />
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

        <SectionCard title="On-paper sales & receipts" subtitle="Documentation that moved paper cash.">
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
            drilldownKey="documentation.paperOnlyReceipts"
          />
          <MetricRow
            label="Real money received against paper sales"
            value={fmtGel(cash?.realMoneyReceivedAgainstPaperSales)}
          />
        </SectionCard>

        <SectionCard title="Supplier paper payments" subtitle="Evidence held against supplier settlement.">
          <MetricRow label="Checks / payment documents on hand" value={fmtGel(cash?.checksOnHand)} />
          <MetricRow
            label="Supported supplier settlement"
            value={fmtGel(cash?.checksSupportedByRealMoney)}
          />
          <MetricRow
            label="Unsupported paper settlement"
            value={fmtGel(cash?.unsupportedChecks)}
            tone={gapTone(cash?.unsupportedChecks)}
          />
          <MetricRow
            label="Unsupported supplier documents"
            value={fmtGel(documentation?.unsupportedSupplierDocumentValue)}
            hint={`${fmtCount(documentation?.unsupportedSupplierDocumentCount)} documents`}
            tone={gapTone(documentation?.unsupportedSupplierDocumentValue)}
          />
        </SectionCard>
      </div>

      <MappingDialog row={mappingRow} onClose={() => setMappingRow(null)} />
    </div>
  )
}
