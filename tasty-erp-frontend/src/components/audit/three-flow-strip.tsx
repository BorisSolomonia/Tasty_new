/**
 * The three summary cards — Inventory, Cash, Documentation.
 *
 * Mandatory in every one of the eight variants (BOR-89: "every view contains
 * all three flows"). It reads the canonical payload from context rather than
 * taking it as a prop, so no variant can quietly show a different scope.
 */
import { Boxes, FileText, Wallet } from 'lucide-react'
import { Skeleton } from '@/components/ui/skeleton'
import { MetricCard } from './metric'
import { fmtGel, fmtGelSigned, fmtKgSigned, fmtCount, gapTone } from './format'
import { inventoryGapKey } from './drilldown-keys'
import { useAudit } from './audit-context'

export function ThreeFlowStrip() {
  const { flows, flowsQuery } = useAudit()

  if (flowsQuery.isLoading) {
    return (
      <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
        <Skeleton className="h-28" />
        <Skeleton className="h-28" />
        <Skeleton className="h-28" />
      </div>
    )
  }

  const inventory = flows?.inventory ?? null
  const cash = flows?.cash ?? null
  const documentation = flows?.documentation ?? null

  const inventoryDescription = inventory
    ? `Document stock vs manually confirmed real stock · ${fmtCount(
        inventory.positiveGapProducts
      )} positive / ${fmtCount(inventory.negativeGapProducts)} negative products`
    : 'Inventory flow not returned for this period'

  const cashDescription = cash
    ? `Net unexplained paper cash · unresolved withdrawals ${fmtGel(cash.unresolvedWithdrawalAmount)}`
    : 'Cash flow not returned for this period'

  const documentationDescription = documentation
    ? `Flagged RS.ge documents · ${fmtCount(documentation.flaggedDocumentCount)} rows, ${fmtCount(
        documentation.unmappedDocumentRowCount
      )} unmapped`
    : 'Documentation flow not returned for this period'

  return (
    <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
      <MetricCard
        label="Inventory"
        value={fmtKgSigned(inventory?.gapKg)}
        tone={gapTone(inventory?.gapKg)}
        description={inventoryDescription}
        icon={<Boxes className="h-4 w-4 text-muted-foreground" />}
        drilldownKey={inventoryGapKey(inventory?.gapKg)}
      />
      <MetricCard
        label="Cash"
        value={fmtGelSigned(cash?.netUnexplainedPaperCash)}
        tone={gapTone(cash?.netUnexplainedPaperCash)}
        description={cashDescription}
        icon={<Wallet className="h-4 w-4 text-muted-foreground" />}
        drilldownKey="cash.paperCash"
      />
      {/*
        No drill-down on this card: the backend resolves no "flagged documents"
        set, and expanding it into every document row would change the scope
        under the reader. The precise subsets inside each view stay drillable.
      */}
      <MetricCard
        label="Documentation"
        value={fmtGel(documentation?.flaggedDocumentValue)}
        tone={gapTone(documentation?.flaggedDocumentValue)}
        description={documentationDescription}
        icon={<FileText className="h-4 w-4 text-muted-foreground" />}
      />
    </div>
  )
}
