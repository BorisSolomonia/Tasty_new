/**
 * 02 — Three-Lane Flow Map.
 *
 * The same payload as every other view, laid out as three synchronised lanes so
 * the moment one flow diverges from the others is visible.
 *
 * Selecting a node filters the other two lanes. The link is not invented: it is
 * the `subjects` list the fired rules carry. When the selected node's rules name
 * nobody, the view says so instead of showing a plausible-looking selection.
 */
import * as React from 'react'
import { ArrowRight, Maximize2, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/cn'
import type { AuditAlert } from '@/lib/audit-api'
import { useAudit } from '../audit-context'
import { ThreeFlowStrip } from '../three-flow-strip'
import { SectionCard } from '../metric'
import { AlertList } from '../alert-list'
import { alertsByFlow, alertsTouching, subjectsOf, type FlowKey } from '../cross-flow'
import { inventoryGapKey, type DrilldownKey } from '../drilldown-keys'
import { fmtCount, fmtGel, fmtKgSigned, gapTone, toneClass, type Tone } from '../format'

interface LaneNode {
  id: string
  label: string
  value: string
  tone: Tone
  /**
   * Only where the backend resolves an expansion of exactly this figure. A node
   * without one still filters the other lanes; it just does not offer to open a
   * different set of rows under the same name.
   */
  drilldownKey?: DrilldownKey
}

interface Selection {
  flow: FlowKey
  node: LaneNode
}

export function ThreeLaneFlowMap() {
  const { flows } = useAudit()
  const [selection, setSelection] = React.useState<Selection | null>(null)

  const inventory = flows?.inventory ?? null
  const cash = flows?.cash ?? null
  const documentation = flows?.documentation ?? null

  const lanes: Record<FlowKey, LaneNode[]> = {
    INVENTORY: [
      node('purchase', 'RS purchases', fmtKgSigned(inventory?.documentPurchaseKg), 'neutral', undefined),
      node('sale', 'RS sales', fmtKgSigned(inventory?.documentSaleKg), 'neutral', undefined),
      node('writeoff', 'Write-offs', fmtKgSigned(inventory?.documentWriteOffKg), 'neutral', undefined),
      node('docstock', 'Document stock', fmtKgSigned(inventory?.documentStockKg), gapTone(inventory?.documentStockKg), undefined),
      node('realstock', 'Real stock (confirmed)', fmtKgSigned(inventory?.realStockKg), 'neutral', undefined),
      node('gap', 'Gap', fmtKgSigned(inventory?.gapKg), gapTone(inventory?.gapKg), inventoryGapKey(inventory?.gapKg)),
    ],
    CASH: [
      node('inflow', 'Bank inflows', fmtGel(cash?.bankInflow), 'neutral', undefined),
      node('outflow', 'Bank outflows', fmtGel(cash?.bankOutflow), 'neutral', undefined),
      node('withdrawals', 'Cash withdrawals', fmtGel(cash?.cashWithdrawals), 'neutral', undefined),
      node('mapped', 'Mapped to a purpose', fmtGel(cash?.mappedWithdrawalAmount), 'neutral', undefined),
      node('checks', 'Checks on hand', fmtGel(cash?.checksOnHand), 'neutral', 'cash.unsupportedChecks'),
      node('paper', 'Net unexplained paper cash', fmtGel(cash?.netUnexplainedPaperCash), gapTone(cash?.netUnexplainedPaperCash), 'cash.paperCash'),
    ],
    DOCUMENTATION: [
      node('purchasedocs', 'Purchase documents', fmtGel(documentation?.documentPurchaseValue), 'neutral', undefined),
      node('saledocs', 'Sales documents', fmtGel(documentation?.documentSalesValue), 'neutral', undefined),
      node('paperonly', 'Paper-only sales', fmtGel(documentation?.paperOnlySalesValue), gapTone(documentation?.paperOnlySalesValue), 'documentation.paperOnlySales'),
      node('unsupported', 'Unsupported supplier docs', fmtGel(documentation?.unsupportedSupplierDocumentValue), gapTone(documentation?.unsupportedSupplierDocumentValue), undefined),
      node('unmapped', 'Unmapped rows', fmtCount(documentation?.unmappedDocumentRowCount), 'warn', 'documentation.unmapped'),
      node('flagged', 'Flagged', fmtGel(documentation?.flaggedDocumentValue), gapTone(documentation?.flaggedDocumentValue), undefined),
    ],
  }

  const grouped = alertsByFlow(flows?.alerts)

  // The anchor: rules that fired on the selected node, or failing that every
  // rule in the selected lane.
  const anchorAlerts: AuditAlert[] = React.useMemo(() => {
    if (!selection) return []
    const laneAlerts = grouped[selection.flow]
    const exact = laneAlerts.filter((alert) => alert.drilldownKey === selection.node.drilldownKey)
    return exact.length > 0 ? exact : laneAlerts
  }, [selection, grouped])

  const linkedSubjects = React.useMemo(() => subjectsOf(anchorAlerts), [anchorAlerts])

  const laneAlerts = (flow: FlowKey): { alerts: AuditAlert[]; note: string | null } => {
    if (!selection) return { alerts: grouped[flow], note: null }
    if (flow === selection.flow) return { alerts: anchorAlerts, note: null }
    if (linkedSubjects.length === 0) {
      return {
        alerts: [],
        note: 'The rules behind the selected node name no product or counterparty, so nothing links to this lane.',
      }
    }
    const touching = alertsTouching(grouped[flow], linkedSubjects)
    return {
      alerts: touching,
      note: touching.length === 0 ? 'No rule in this lane names any of the linked subjects.' : null,
    }
  }

  return (
    <div className="space-y-4">
      <ThreeFlowStrip />

      {selection ? (
        <div className="flex flex-wrap items-center gap-2 rounded-lg border border-primary/40 bg-primary/5 px-3 py-2 text-sm">
          <span className="font-medium">Filtered by {selection.node.label}</span>
          <span className="text-muted-foreground">
            · {fmtCount(anchorAlerts.length)} rules ·{' '}
            {linkedSubjects.length > 0
              ? `${fmtCount(linkedSubjects.length)} linked subjects: ${linkedSubjects.slice(0, 5).join(', ')}${linkedSubjects.length > 5 ? '…' : ''}`
              : 'no linked subjects'}
          </span>
          <Button
            type="button"
            size="sm"
            variant="ghost"
            className="ml-auto h-7"
            onClick={() => setSelection(null)}
          >
            <X className="mr-1 h-3.5 w-3.5" />
            Clear
          </Button>
        </div>
      ) : (
        <p className="text-sm text-muted-foreground">
          Select a node to filter the other two lanes to the exceptions that name the same products
          or counterparties.
        </p>
      )}

      <Lane
        title="Inventory lane"
        subtitle="Documented movement, then the manually confirmed reality it is measured against."
        flow="INVENTORY"
        nodes={lanes.INVENTORY}
        selection={selection}
        onSelect={setSelection}
        {...laneAlerts('INVENTORY')}
      />
      <Lane
        title="Cash lane"
        subtitle="Statement, then mapped movement, then the evidence that is not money."
        flow="CASH"
        nodes={lanes.CASH}
        selection={selection}
        onSelect={setSelection}
        {...laneAlerts('CASH')}
      />
      <Lane
        title="Documentation lane"
        subtitle="RS.ge source rows and the two consequences each one carries."
        flow="DOCUMENTATION"
        nodes={lanes.DOCUMENTATION}
        selection={selection}
        onSelect={setSelection}
        {...laneAlerts('DOCUMENTATION')}
      />
    </div>
  )
}

function node(
  id: string,
  label: string,
  value: string,
  tone: Tone,
  drilldownKey?: DrilldownKey
): LaneNode {
  return { id, label, value, tone, drilldownKey }
}

function Lane({
  title,
  subtitle,
  flow,
  nodes,
  selection,
  onSelect,
  alerts,
  note,
}: {
  title: string
  subtitle: string
  flow: FlowKey
  nodes: LaneNode[]
  selection: Selection | null
  onSelect: (selection: Selection | null) => void
  alerts: AuditAlert[]
  note: string | null
}) {
  const { openDrilldown } = useAudit()
  const dimmed = selection !== null && selection.flow !== flow

  return (
    <SectionCard title={title} subtitle={subtitle}>
      <div className="flex items-stretch gap-2 overflow-x-auto pb-2">
        {nodes.map((laneNode, index) => {
          const active = selection?.flow === flow && selection.node.id === laneNode.id
          return (
            <React.Fragment key={laneNode.id}>
              {index > 0 ? (
                <div className="flex shrink-0 items-center text-muted-foreground">
                  <ArrowRight className="h-4 w-4" />
                </div>
              ) : null}
              <div
                className={cn(
                  'w-44 shrink-0 rounded-lg border p-3',
                  active ? 'border-primary bg-primary/10' : 'border-border bg-card',
                  dimmed && !active && 'opacity-60'
                )}
              >
                <button
                  type="button"
                  className="w-full text-left"
                  onClick={() => onSelect(active ? null : { flow, node: laneNode })}
                >
                  <div className="text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
                    {laneNode.label}
                  </div>
                  <div className={cn('mt-1 text-base font-bold tabular-nums', toneClass[laneNode.tone])}>
                    {laneNode.value}
                  </div>
                </button>
                {laneNode.drilldownKey ? (
                  <button
                    type="button"
                    className="mt-1.5 inline-flex items-center gap-1 text-[11px] text-primary hover:underline"
                    onClick={() =>
                      openDrilldown({
                        key: laneNode.drilldownKey as string,
                        label: laneNode.label,
                      })
                    }
                  >
                    <Maximize2 className="h-3 w-3" />
                    Source rows
                  </button>
                ) : null}
              </div>
            </React.Fragment>
          )
        })}
      </div>

      <div className="mt-2 border-t border-border pt-2">
        <div className="text-xs font-semibold text-muted-foreground">Exceptions in this lane</div>
        {note ? (
          <p className="py-3 text-sm text-muted-foreground">{note}</p>
        ) : (
          <AlertList alerts={alerts} limit={3} emptyMessage="No rule fired in this lane." />
        )}
      </div>
    </SectionCard>
  )
}
