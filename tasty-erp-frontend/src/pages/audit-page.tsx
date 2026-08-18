/**
 * BOR-91 "Audit Control" — one page.
 *
 * This replaces the eight-variant switcher BOR-89 shipped. Eight renderings of
 * one payload turned out to be eight places to look for the same figure:
 * `netUnexplainedPaperCash` appeared in four of them, the three-flow strip in
 * all eight. The page is now a single scroll with sticky in-page navigation,
 * and the invariant that makes it readable is that **a metric appears in
 * exactly one place**. Where a section needs to acknowledge another flow, it
 * links to the figure rather than reprinting it.
 *
 * What each old view became:
 *
 *  - 01 Executive Control Tower  → Problems (top cases) + the totals now owned
 *                                  by the Cash, Inventory and Documentation
 *                                  sections
 *  - 02 Three-Lane Flow Map      → DELIBERATELY FOLDED. Its lane graphic showed
 *                                  purchases → sales → write-offs → document
 *                                  stock, and every one of those numbers is in
 *                                  the inventory section's period totals, where
 *                                  it sits beside the real stock it is measured
 *                                  against. The lane visual restated them in a
 *                                  prettier order; it added no capability the
 *                                  rest of the page lacks, and its node-selects
 *                                  -filters-other-lanes behaviour is the same
 *                                  cross-flow link the Problems panel makes
 *                                  from the alerts' own subjects. Duplication,
 *                                  not capability — so it is gone rather than
 *                                  ported.
 *  - 03 Exception Investigator   → Problems (case anatomy)
 *  - 04 Inventory-First Matrix   → Inventory section
 *  - 05 Cash-First Treasury      → Cash section (the base of this page)
 *  - 06 Documentation Ledger     → Documentation section
 *  - 07 Risk Heatmap             → Problems (risk by rule, monthly
 *                                  concentration, cross-flow clusters)
 *  - 08 Reconciliation Workbench → Workbench section
 *
 * The period and product filters and the operator name stay owned by this page,
 * and every section renders the one `AuditFlowsDto` that `AuditProvider`
 * fetches once.
 *
 * Two later corrections, both about height and where evidence lands:
 *
 *  - every table-bearing block is a `CollapsiblePanel`, shut on load with its
 *    count and headline figure on the header. The page was ~133,000px tall on
 *    a 1280px viewport; the summaries it exists to show were separated by tens
 *    of thousands of pixels of rows nobody had asked for.
 *  - a problem's evidence no longer opens in a dialog. It scrolls to the
 *    section that browses that kind of row and renders there, under a chip
 *    naming the problem, with a Clear that restores the section. See
 *    `evidence.ts` for the key → section routing.
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
  EARLIEST_AUDIT_DATE,
  isSaneAuditDate,
  useAudit,
} from '@/components/audit/audit-context'
import { DataWarnings } from '@/components/audit/data-warnings'
import { FeedCapNotice } from '@/components/audit/feed-cap-notice'
import { OperatorPicker } from '@/components/audit/operator-picker'
import { PendingNotice } from '@/components/audit/pending-notice'
import { ThreeFlowStrip } from '@/components/audit/three-flow-strip'
import { ProblemsPanel } from '@/components/audit/problems-panel'
import { RulesPanel } from '@/components/audit/rules-panel'
import { CategoryManager } from '@/components/audit/category-manager'
import { SubgroupManager } from '@/components/audit/subgroup-manager'
import { StatementSection } from '@/components/audit/statement/statement-section'
import { AUDIT_SECTIONS, PageSection } from '@/components/audit/section-nav'
import { CashSection } from '@/components/audit/sections/cash-section'
import { InventorySection } from '@/components/audit/sections/inventory-section'
import { DocumentationSection } from '@/components/audit/sections/documentation-section'
import { WorkbenchSection } from '@/components/audit/sections/workbench-section'

export function AuditPage() {
  return (
    <AuditProvider>
      <AuditWorkspace />
    </AuditProvider>
  )
}

function AuditWorkspace() {
  const { flows, flowsQuery, filters, setFilters, refreshAll } = useAudit()
  const activeSection = useActiveSection(!flowsQuery.isLoading)

  const productOptions = React.useMemo(() => {
    const names = (flows?.inventory?.products ?? [])
      .map((product) => product.productName)
      .filter((name): name is string => Boolean(name))
    return [...new Set(names)].sort((a, b) => a.localeCompare(b))
  }, [flows])

  return (
    <div className="space-y-3">
      {/* Header ---------------------------------------------------------- */}
      <div className="flex flex-col gap-3 lg:flex-row lg:items-end lg:justify-between">
        <div>
          <h1 className="text-xl font-bold tracking-tight">
            Audit Control — Inventory × Cash × Documentation
          </h1>
          <p className="mt-0.5 text-sm text-muted-foreground">
            One period, one payload, one page. Each figure is shown once, in the flow that owns it.
          </p>
        </div>
        <OperatorPicker />
      </div>

      <div className="flex flex-wrap items-end gap-3 rounded-lg border border-border bg-card p-2">
        <div>
          <Label htmlFor="audit-start" className="text-xs text-muted-foreground">
            From
          </Label>
          <input
            id="audit-start"
            type="date"
            value={filters.startDate}
            min={EARLIEST_AUDIT_DATE}
            max={filters.endDate}
            onChange={(event) => {
              // A date input fires on every keystroke: typing 2023 emits 0002, 0020, 0202 first.
              // Only a finished, sane date is committed — never a request for the year 202.
              const next = event.target.value
              if (!next) return
              if (isSaneAuditDate(next) && next <= filters.endDate) setFilters({ startDate: next })
            }}
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
            onChange={(event) => {
              const next = event.target.value
              if (isSaneAuditDate(next) && next >= filters.startDate) setFilters({ endDate: next })
            }}
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
        <p className="ml-auto max-w-md self-center text-[10px] leading-snug text-muted-foreground">
          Changing the period or product refetches the one shared payload. Real inventory is a
          manual input, source rows stay individually mappable, and every manual mapping or override
          is logged against a self-declared operator name.
        </p>
      </div>

      {/* Rendered once for the whole page, not once per section ------------ */}
      <DataWarnings warnings={flows?.dataWarnings} error={flowsQuery.error} />
      <FeedCapNotice />

      {/* BOR-92 v2: the statement — purchases → bank to suppliers → cash outflow → inventory → sales → inflows, each total | chosen */}
      <StatementSection />

      <ThreeFlowStrip />

      {/* Sticky in-page navigation ---------------------------------------- */}
      <nav
        aria-label="Audit sections"
        className="sticky top-14 z-20 -mx-1 border-b border-border bg-background/90 px-1 py-2 backdrop-blur"
      >
        <ul className="flex gap-2 overflow-x-auto">
          {AUDIT_SECTIONS.map((section) => (
            <li key={section.id} className="shrink-0">
              <a
                href={`#${section.id}`}
                title={section.hint}
                className={cn(
                  'block rounded-md border px-3 py-1.5 text-xs font-medium transition-colors',
                  activeSection === section.id
                    ? 'border-primary bg-primary text-primary-foreground'
                    : 'border-border bg-card hover:bg-accent'
                )}
              >
                {section.label}
              </a>
            </li>
          ))}
        </ul>
      </nav>

      {flowsQuery.isLoading ? (
        <div className="space-y-3">
          <PendingNotice active what="the three-flow payload for this period" />
          <Skeleton className="h-28 w-full" />
          <Skeleton className="h-64 w-full" />
        </div>
      ) : (
        <div className="space-y-5">
          <PageSection
            id="problems"
            title="Problems"
            description="Every rule that fired, one line each, with its arithmetic one click inside the row, and where the unresolved rows concentrate. Each case opens its own source rows — never a wider set."
          >
            <ProblemsPanel />
          </PageSection>

          <PageSection
            id="cash"
            title="Cash"
            description="What the bank did, what documents did to accounting cash, and whether supplier settlement plus asserted debt is covered by documented purchases."
          >
            <CashSection />
          </PageSection>

          <PageSection
            id="inventory"
            title="Inventory"
            description="Documented movement per product against the manually confirmed real stock. Real stock is an input, not a derivation, and is normally zero until someone confirms it."
          >
            <InventorySection />
          </PageSection>

          <PageSection
            id="documentation"
            title="Documentation"
            description="RS.ge rows with the two consequences each one carries — what it did to stock, and what it did to cash or paper cash. Every row is individually mappable."
          >
            <DocumentationSection />
          </PageSection>

          <PageSection
            id="workbench"
            title="Reconciliation workbench"
            description="The daily queue beside the split-mapping editor. Saving here moves the three-flow strip at the top of this page immediately."
          >
            <WorkbenchSection />
          </PageSection>

          <PageSection
            id="rules"
            title="Rules, categories & statuses"
            description="Classifications that apply beyond the transaction they were made on, and the categories they assert. A rule is created only by explicit choice and can be revoked, which un-maps everything it created."
          >
            <div className="space-y-4">
              <RulesPanel />
              <CategoryManager />
              <SubgroupManager />
            </div>
          </PageSection>
        </div>
      )}
    </div>
  )
}

/**
 * Which section the reader is in.
 *
 * Anchor links alone leave the nav showing nothing once the reader scrolls, so
 * the observer marks the section currently crossing the top third of the
 * viewport. It is a reading aid only — no data depends on it, and if
 * IntersectionObserver is unavailable the nav simply stays unhighlighted.
 */
function useActiveSection(enabled: boolean): string | null {
  const [active, setActive] = React.useState<string | null>(null)

  React.useEffect(() => {
    if (!enabled || typeof IntersectionObserver === 'undefined') return

    const elements = AUDIT_SECTIONS.map((section) => document.getElementById(section.id)).filter(
      (element): element is HTMLElement => element !== null
    )
    if (elements.length === 0) return

    const observer = new IntersectionObserver(
      (entries) => {
        const visible = entries
          .filter((entry) => entry.isIntersecting)
          .sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top)
        if (visible.length > 0) setActive(visible[0].target.id)
      },
      { rootMargin: '-15% 0px -70% 0px' }
    )

    elements.forEach((element) => observer.observe(element))
    return () => observer.disconnect()
    // Re-registered when the sections appear; the section list itself is static.
  }, [enabled])

  return active
}
