/**
 * BOR-89 "Audit Control" — eight UX variants over one payload.
 *
 * The page owns the period and product filters, the operator name and the
 * drill-down dialog. Every variant below renders the same `AuditFlowsDto`
 * fetched once by `AuditProvider`, so switching variants changes the shape of
 * the argument and never the scope of the data.
 */
import * as React from 'react'
import { RefreshCw } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/cn'
import {
  ALL_PRODUCTS,
  AuditProvider,
  DEFAULT_START_DATE,
  useAudit,
} from '@/components/audit/audit-context'
import { DataWarnings } from '@/components/audit/data-warnings'
import { DrilldownDialog } from '@/components/audit/drilldown-dialog'
import { OperatorPicker } from '@/components/audit/operator-picker'
import { PendingNotice } from '@/components/audit/pending-notice'
import { ExecutiveControlTower } from '@/components/audit/views/executive-control-tower'
import { ThreeLaneFlowMap } from '@/components/audit/views/three-lane-flow-map'
import { ExceptionInvestigator } from '@/components/audit/views/exception-investigator'
import { InventoryFirstMatrix } from '@/components/audit/views/inventory-first-matrix'
import { CashFirstTreasury } from '@/components/audit/views/cash-first-treasury'
import { DocumentationLedger } from '@/components/audit/views/documentation-ledger'
import { RiskHeatmap } from '@/components/audit/views/risk-heatmap'
import { ReconciliationWorkbench } from '@/components/audit/views/reconciliation-workbench'

const VIEW_STORAGE_KEY = 'tasty.audit.view'

interface ViewDefinition {
  id: string
  number: string
  title: string
  blurb: string
  Component: () => React.JSX.Element
}

const VIEWS: ViewDefinition[] = [
  {
    id: 'control-tower',
    number: '01',
    title: 'Executive Control Tower',
    blurb: 'Three flows first, then each reconciliation, then the rules that fired.',
    Component: ExecutiveControlTower,
  },
  {
    id: 'flow-map',
    number: '02',
    title: 'Three-Lane Flow Map',
    blurb: 'Synchronised lanes. Selecting a node filters the other two.',
    Component: ThreeLaneFlowMap,
  },
  {
    id: 'investigator',
    number: '03',
    title: 'Exception Investigator',
    blurb: 'Organised around cases rather than totals, with each case’s anatomy.',
    Component: ExceptionInvestigator,
  },
  {
    id: 'inventory-matrix',
    number: '04',
    title: 'Inventory-First Matrix',
    blurb: 'Product reconciliation dominates; cash and documents stay attached.',
    Component: InventoryFirstMatrix,
  },
  {
    id: 'treasury',
    number: '05',
    title: 'Cash-First Treasury',
    blurb: 'Bank bridge, paper-cash bridge and the supplier coverage control.',
    Component: CashFirstTreasury,
  },
  {
    id: 'ledger',
    number: '06',
    title: 'Documentation Ledger',
    blurb: 'RS.ge rows with their stock and cash consequences, each mappable.',
    Component: DocumentationLedger,
  },
  {
    id: 'heatmap',
    number: '07',
    title: 'Risk Heatmap',
    blurb: 'Severity weight per flow, month concentration and cross-flow clusters.',
    Component: RiskHeatmap,
  },
  {
    id: 'workbench',
    number: '08',
    title: 'Reconciliation Workbench',
    blurb: 'The daily queue beside the split-mapping editor.',
    Component: ReconciliationWorkbench,
  },
]

function readStoredView(): string {
  try {
    const stored = window.localStorage.getItem(VIEW_STORAGE_KEY)
    return VIEWS.some((view) => view.id === stored) ? (stored as string) : VIEWS[0].id
  } catch {
    return VIEWS[0].id
  }
}

export function AuditPage() {
  return (
    <AuditProvider>
      <AuditWorkspace />
    </AuditProvider>
  )
}

function AuditWorkspace() {
  const { flows, flowsQuery, filters, setFilters, refreshAll } = useAudit()
  const [viewId, setViewId] = React.useState<string>(readStoredView)

  const activeView = VIEWS.find((view) => view.id === viewId) ?? VIEWS[0]
  const ActiveComponent = activeView.Component

  const selectView = (id: string) => {
    setViewId(id)
    try {
      window.localStorage.setItem(VIEW_STORAGE_KEY, id)
    } catch {
      // Selection still works for this session without storage.
    }
  }

  const productOptions = React.useMemo(() => {
    const names = (flows?.inventory?.products ?? [])
      .map((product) => product.productName)
      .filter((name): name is string => Boolean(name))
    return [...new Set(names)].sort((a, b) => a.localeCompare(b))
  }, [flows])

  return (
    <div className="space-y-4">
      {/* Header ---------------------------------------------------------- */}
      <div className="flex flex-col gap-3 lg:flex-row lg:items-end lg:justify-between">
        <div>
          <h1 className="text-xl font-bold tracking-tight">Audit Control — Inventory × Cash × Documentation</h1>
          <p className="mt-0.5 text-sm text-muted-foreground">
            Eight ways to read one audit. Every view renders the same payload for the same period —
            they differ in emphasis, never in scope.
          </p>
        </div>
        <OperatorPicker />
      </div>

      <div className="flex flex-wrap items-end gap-3 rounded-lg border border-border bg-card p-3">
        <div>
          <Label htmlFor="audit-start" className="text-xs text-muted-foreground">
            From
          </Label>
          <input
            id="audit-start"
            type="date"
            value={filters.startDate}
            max={filters.endDate}
            onChange={(event) => setFilters({ startDate: event.target.value || DEFAULT_START_DATE })}
            className="mt-1 block h-9 rounded-md border border-input bg-background px-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          />
        </div>
        <div>
          <Label htmlFor="audit-end" className="text-xs text-muted-foreground">
            To
          </Label>
          <input
            id="audit-end"
            type="date"
            value={filters.endDate}
            min={filters.startDate}
            onChange={(event) => setFilters({ endDate: event.target.value })}
            className="mt-1 block h-9 rounded-md border border-input bg-background px-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          />
        </div>
        <div className="min-w-[12rem]">
          <Label htmlFor="audit-product" className="text-xs text-muted-foreground">
            Product
          </Label>
          <select
            id="audit-product"
            value={filters.product}
            onChange={(event) => setFilters({ product: event.target.value })}
            className="mt-1 block h-9 w-full rounded-md border border-input bg-background px-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            <option value={ALL_PRODUCTS}>All products</option>
            {productOptions.map((name) => (
              <option key={name} value={name}>
                {name}
              </option>
            ))}
          </select>
        </div>
        <Button
          type="button"
          variant="outline"
          className="h-9"
          onClick={refreshAll}
          disabled={flowsQuery.isFetching}
        >
          <RefreshCw className={cn('mr-2 h-4 w-4', flowsQuery.isFetching && 'animate-spin')} />
          {flowsQuery.isFetching ? 'Refreshing…' : 'Refresh'}
        </Button>
        <p className="ml-auto max-w-xs text-[11px] leading-snug text-muted-foreground">
          Changing the period or product refetches the one shared payload, so all eight views move
          together.
        </p>
      </div>

      {/* Warnings apply to every view, so they sit above the switcher ----- */}
      <DataWarnings warnings={flows?.dataWarnings} error={flowsQuery.error} />

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-[16rem_minmax(0,1fr)]">
        {/* Switcher ------------------------------------------------------ */}
        <nav aria-label="Audit views" className="xl:sticky xl:top-16 xl:self-start">
          <ul className="flex gap-2 overflow-x-auto pb-2 xl:flex-col xl:overflow-visible xl:pb-0">
            {VIEWS.map((view) => (
              <li key={view.id} className="shrink-0 xl:shrink">
                <button
                  type="button"
                  onClick={() => selectView(view.id)}
                  className={cn(
                    'w-full rounded-md border px-3 py-2 text-left transition-colors',
                    view.id === activeView.id
                      ? 'border-primary bg-primary text-primary-foreground'
                      : 'border-border bg-card hover:bg-accent'
                  )}
                >
                  <div className="whitespace-nowrap text-xs font-semibold">
                    {view.number} — {view.title}
                  </div>
                  <div
                    className={cn(
                      'mt-0.5 hidden text-[11px] leading-snug xl:block',
                      view.id === activeView.id
                        ? 'text-primary-foreground/80'
                        : 'text-muted-foreground'
                    )}
                  >
                    {view.blurb}
                  </div>
                </button>
              </li>
            ))}
          </ul>

          <p className="mt-3 hidden rounded-md border border-border bg-muted/40 p-3 text-[11px] leading-snug text-muted-foreground xl:block">
            <span className="font-semibold text-foreground">Rule for all eight views.</span> Every
            view contains all three flows. Real inventory is manually editable and normally zero.
            Bank and RS.ge source rows stay individually mappable, and every manual mapping or
            override is permanently logged against a self-declared operator name.
          </p>
        </nav>

        {/* Active view --------------------------------------------------- */}
        <div className="min-w-0">
          {flowsQuery.isLoading ? (
            <div className="space-y-3">
              <PendingNotice active what="the three-flow payload for this period" />
              <Skeleton className="h-28 w-full" />
              <Skeleton className="h-64 w-full" />
            </div>
          ) : (
            <ActiveComponent />
          )}
        </div>
      </div>

      <DrilldownDialog />
    </div>
  )
}
