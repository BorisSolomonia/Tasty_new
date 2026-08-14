/**
 * Cash — the bank bridge, the paper-cash bridge and the supplier coverage
 * control. This is the base the consolidated page was built on (old view 05).
 *
 * Two changes from that view, both to keep every figure in exactly one place:
 *
 *  - the supplier-directed money (direct bank payments, allocated cash
 *    settlements) now appears only inside the coverage control, where it is an
 *    input to the breach arithmetic, instead of once there and once in the
 *    bridge above it;
 *  - the three "other flow" cards it used to carry are gone. On a single page
 *    the inventory and documentation sections are one scroll away, and a second
 *    copy of a figure is a second thing a reader can mistake for a separate
 *    finding.
 *
 * The coverage control still reads `supplierSettlementAndDebt`, never total
 * withdrawals: a withdrawal does not prove a supplier was settled.
 */
import * as React from 'react'
import { AlertOctagon } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import type { AuditSourceRow } from '@/lib/audit-api'
import { useAudit } from '../audit-context'
import { MetricRow, SectionCard, FormulaNote } from '../metric'
import { StatusBadge } from '../status-badge'
import { RuleBadge } from '../rule-badge'
import { SupplierDebtPanel } from '../supplier-debt-panel'
import { MappingDialog } from '../mapping-dialog'
import { isUnresolved } from '../cross-flow'
import { JumpLink } from '../section-nav'
import { fmtCount, fmtGel, fmtDate, fmtText, gapTone } from '../format'

export function CashSection() {
  const { flows, sourceRows, sourceRowsQuery, openDrilldown } = useAudit()
  const [mappingRow, setMappingRow] = React.useState<AuditSourceRow | null>(null)

  const cash = flows?.cash ?? null

  const bankRowsToMap = sourceRows
    .filter((row) => row.sourceType === 'BANK' && isUnresolved(row))
    .slice(0, 25)

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        {/* 1 ---------------------------------------------------------------- */}
        <SectionCard
          title="1. Bank-to-real-cash bridge"
          subtitle="What the bank did, what was allocated to a purpose, and what evidence exists for the rest."
        >
          <MetricRow label="Bank inflows" value={fmtGel(cash?.bankInflow)} />
          <MetricRow label="Bank outflows" value={fmtGel(cash?.bankOutflow)} />
          <MetricRow label="Total cash withdrawals" value={fmtGel(cash?.cashWithdrawals)} />
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
            label="Unmapped bank inflows"
            value={fmtGel(cash?.unmappedInflowAmount)}
            hint={`${fmtCount(cash?.unmappedInflowCount)} money-in rows nobody has classified`}
            tone={gapTone(cash?.unmappedInflowAmount)}
            drilldownKey="cash.unmappedInflows"
          />

          <div className="mt-3 border-t border-border pt-2">
            <MetricRow label="Allocated to other spending" value={fmtGel(cash?.nonSupplierExpenses)} />
            <MetricRow
              label="Returned / redeposited"
              value={fmtGel(cash?.cashReturnedOrRedeposited)}
            />
            <MetricRow label="Customer bank receipts" value={fmtGel(cash?.customerBankReceipts)} />
            <MetricRow
              label="Real customer cash receipts"
              value={fmtGel(cash?.realCustomerCashReceipts)}
            />
            <MetricRow label="Other income" value={fmtGel(cash?.otherIncome)} />
            <MetricRow label="Refunds and reversals" value={fmtGel(cash?.refundsAndReversals)} />
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

          <FormulaNote>
            Money that settled suppliers is not listed here — it is an input to the coverage control
            below, and appears there once.
          </FormulaNote>
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

          {/*
            The single home of `netUnexplainedPaperCash`. It used to appear in
            four of the eight views; four copies of one figure read as four
            findings.
          */}
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
          <MetricRow label="Settlement + debt" value={fmtGel(cash?.supplierSettlementAndDebt)} />
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
            settlement counts, because a withdrawal does not prove a supplier was settled. The
            purchases side of this control is documented in the{' '}
            <JumpLink to="documentation">documentation section</JumpLink>.
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
            /*
              The one place `cash.bankRows` is reachable. It is deliberately
              broad — every statement row, mapped or not — so it hangs off an
              explicit "browse all" and never off a problem.
            */
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
                    <RuleBadge mapping={row.mapping} className="mt-1" />
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

      <MappingDialog row={mappingRow} onClose={() => setMappingRow(null)} />
    </div>
  )
}
