/**
 * The reconciliation workbench (old view 08), unchanged: the queue on the left,
 * the split-mapping editor on the right.
 *
 * Saving here invalidates the whole audit layer, so the three-flow strip at the
 * top of the page moves as soon as a row is classified — the mapping and the
 * aggregate it feeds are never out of step within a session.
 */
import * as React from 'react'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/cn'
import type { AuditSourceRow } from '@/lib/audit-api'
import { useAudit } from '../audit-context'
import { CollapsiblePanel } from '../collapsible-panel'
import { SectionEvidence } from '../evidence-panel'
import { MetricCard, SectionCard } from '../metric'
import { MappingEditor } from '../mapping-editor'
import { SourceRowTable, sourceRowKey } from '../source-row-table'
import { isUnresolved } from '../cross-flow'
import { fmtCount, fmtGel } from '../format'

type QueueFilter = 'ALL' | 'INVENTORY' | 'CASH' | 'DOCUMENTATION' | 'UNMAPPED' | 'OVERRIDES'

const FILTERS: { id: QueueFilter; label: string; hint: string }[] = [
  { id: 'ALL', label: 'All', hint: 'Every row in the shared feed for this period' },
  {
    id: 'INVENTORY',
    label: 'Inventory',
    hint: 'Rows carrying a quantity, so classifying them moves stock',
  },
  { id: 'CASH', label: 'Cash', hint: 'Bank statement rows' },
  { id: 'DOCUMENTATION', label: 'Documentation', hint: 'RS.ge documents and payment evidence' },
  { id: 'UNMAPPED', label: 'Only unmapped', hint: 'Status UNMAPPED — nothing allocated at all' },
  {
    id: 'OVERRIDES',
    label: 'Manual overrides',
    hint: 'Rows a person mapped or overrode by hand',
  },
]

function matches(row: AuditSourceRow, filter: QueueFilter): boolean {
  switch (filter) {
    case 'ALL':
      return true
    case 'INVENTORY':
      return typeof row.quantityKg === 'number' && Number.isFinite(row.quantityKg)
    case 'CASH':
      return row.sourceType === 'BANK'
    case 'DOCUMENTATION':
      return row.sourceType === 'RS_GE' || row.sourceType === 'CHECK'
    case 'UNMAPPED':
      return (row.status ?? 'UNMAPPED') === 'UNMAPPED'
    case 'OVERRIDES':
      return row.status === 'MANUALLY_MAPPED' || row.status === 'OVERRIDDEN'
    default:
      return true
  }
}

export function WorkbenchSection() {
  const { sourceRows, sourceRowsQuery } = useAudit()
  const [filter, setFilter] = React.useState<QueueFilter>('ALL')
  const [search, setSearch] = React.useState('')
  const [selectedKey, setSelectedKey] = React.useState<string | null>(null)

  const rows = React.useMemo(() => {
    const term = search.trim().toLowerCase()
    return sourceRows
      .filter((row) => matches(row, filter))
      .filter((row) => {
        if (!term) return true
        return [row.counterpartyName, row.productName, row.reference, row.description]
          .filter(Boolean)
          .some((field) => (field as string).toLowerCase().includes(term))
      })
  }, [sourceRows, filter, search])

  // Keep the selection pointing at the live object after a refetch.
  const selected = React.useMemo(
    () => sourceRows.find((row) => sourceRowKey(row) === selectedKey) ?? null,
    [sourceRows, selectedKey]
  )

  const queues = React.useMemo(() => {
    const unresolved = sourceRows.filter(isUnresolved)
    const sumUnresolved = (subset: AuditSourceRow[]) =>
      subset.reduce((sum, row) => sum + Math.abs(row.unresolvedAmount ?? row.amount ?? 0), 0)

    const inventory = unresolved.filter((row) => matches(row, 'INVENTORY'))
    const cash = unresolved.filter((row) => matches(row, 'CASH'))
    const documentation = unresolved.filter((row) => matches(row, 'DOCUMENTATION'))

    return [
      {
        label: 'Inventory queue',
        rows: inventory,
        description: `Rows carrying a quantity · ${fmtGel(sumUnresolved(inventory))} unresolved`,
      },
      {
        label: 'Cash queue',
        rows: cash,
        description: `Bank statement rows · ${fmtGel(sumUnresolved(cash))} unresolved`,
      },
      {
        label: 'Documentation queue',
        rows: documentation,
        description: `RS.ge and evidence rows · ${fmtGel(sumUnresolved(documentation))} unresolved`,
      },
    ]
  }, [sourceRows])

  const unresolvedInFilter = React.useMemo(
    () => rows.reduce((sum, row) => sum + Math.abs(row.unresolvedAmount ?? 0), 0),
    [rows]
  )

  return (
    <div className="space-y-3">
      {/*
        The page's general row browser, so it is where any cash-flow problem's
        evidence lands (see `evidence.ts`).
      */}
      <SectionEvidence section="workbench" />

      <div className="grid grid-cols-1 gap-2 md:grid-cols-3">
        {queues.map((queue) => (
          <MetricCard
            key={queue.label}
            label={queue.label}
            value={`${fmtCount(queue.rows.length)} unresolved`}
            tone={queue.rows.length > 0 ? 'warn' : 'good'}
            description={queue.description}
          />
        ))}
      </div>

      <div className="flex flex-wrap items-center gap-2">
        {FILTERS.map((option) => (
          <button
            key={option.id}
            type="button"
            title={option.hint}
            onClick={() => setFilter(option.id)}
            className={cn(
              'rounded-md border px-3 py-1.5 text-xs font-medium transition-colors',
              filter === option.id
                ? 'border-primary bg-primary text-primary-foreground'
                : 'border-border bg-card hover:bg-accent'
            )}
          >
            {option.label}
          </button>
        ))}
        <Input
          value={search}
          placeholder="Filter by counterparty, product, reference or description"
          className="h-8 w-full max-w-sm sm:ml-auto sm:w-72"
          onChange={(event) => setSearch(event.target.value)}
        />
      </div>

      <div className="grid grid-cols-1 gap-3 xl:grid-cols-[minmax(0,1.35fr)_minmax(0,1fr)]">
        <CollapsiblePanel
          title="Source rows"
          summary={`${fmtCount(rows.length)} of ${fmtCount(sourceRows.length)} · ${fmtGel(
            unresolvedInFilter
          )} unresolved`}
          subtitle={`${fmtCount(rows.length)} rows in this filter. Select one to classify it.`}
        >
          {sourceRowsQuery.isLoading ? (
            <Skeleton className="h-80 w-full" />
          ) : sourceRowsQuery.isError ? (
            <p className="text-sm text-destructive">
              The row feed could not be loaded, so this queue is unknown rather than empty.
            </p>
          ) : (
            <SourceRowTable
              rows={rows}
              selectedKey={selectedKey}
              onSelect={(row) => setSelectedKey(sourceRowKey(row))}
              emptyMessage="No row matches this filter."
            />
          )}
        </CollapsiblePanel>

        <SectionCard
          title="Selected row — split mapping"
          subtitle="Splits are allocations of the source amount. The remainder is derived, shown and enforced."
        >
          {selected ? (
            <MappingEditor row={selected} />
          ) : (
            <p className="text-sm text-muted-foreground">
              Select a row from the queue to open its mapping.
            </p>
          )}
        </SectionCard>
      </div>
    </div>
  )
}
