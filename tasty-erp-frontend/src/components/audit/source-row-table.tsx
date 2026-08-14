/**
 * The immutable source rows, exactly as imported.
 *
 * Raw description and reference are printed verbatim — Georgian partner names
 * and bank narratives are never normalised or translated here, because the
 * whole point of the terminal node of a drill-down is to show what the bank or
 * RS.ge actually said next to what the audit decided about it.
 */
import * as React from 'react'
import {
  createColumnHelper,
  flexRender,
  getCoreRowModel,
  getSortedRowModel,
  useReactTable,
  type SortingState,
} from '@tanstack/react-table'
import { ArrowDown, ArrowUp } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/cn'
import type { AuditSourceRow } from '@/lib/audit-api'
import { RuleBadge } from './rule-badge'
import { StatusBadge } from './status-badge'
import { EM_DASH, fmtDate, fmtGel, fmtKgSigned, fmtText, gapTone, toneClass } from './format'

const columnHelper = createColumnHelper<AuditSourceRow>()

export function sourceRowKey(row: AuditSourceRow): string {
  return `${row.sourceType ?? 'UNKNOWN'}:${row.sourceRowId ?? ''}`
}

export function SourceRowTable({
  rows,
  onSelect,
  selectedKey,
  onMap,
  emptyMessage = 'No source rows for this filter.',
  className,
}: {
  rows: AuditSourceRow[]
  onSelect?: (row: AuditSourceRow) => void
  selectedKey?: string | null
  /** Renders a per-row action opening the mapping editor. */
  onMap?: (row: AuditSourceRow) => void
  emptyMessage?: string
  className?: string
}) {
  const [sorting, setSorting] = React.useState<SortingState>([{ id: 'date', desc: true }])

  const columns = React.useMemo(() => {
    const base = [
      columnHelper.accessor('date', {
        header: 'Date',
        cell: (info) => <span className="whitespace-nowrap">{fmtDate(info.getValue())}</span>,
      }),
      columnHelper.display({
        id: 'source',
        header: 'Source',
        cell: ({ row }) => (
          <div className="min-w-0">
            <div className="font-medium">{row.original.sourceType ?? EM_DASH}</div>
            <div className="truncate text-[11px] text-muted-foreground">
              {fmtText(row.original.reference)}
            </div>
          </div>
        ),
      }),
      columnHelper.accessor('direction', {
        header: 'Direction',
        cell: (info) => <span className="whitespace-nowrap">{fmtText(info.getValue())}</span>,
      }),
      columnHelper.accessor('counterpartyName', {
        header: 'Counterparty',
        cell: ({ row }) => (
          <div className="min-w-0 max-w-[16rem]">
            <div className="truncate" title={row.original.counterpartyName ?? undefined}>
              {fmtText(row.original.counterpartyName)}
            </div>
            {row.original.counterpartyTin ? (
              <div className="truncate text-[11px] text-muted-foreground">
                {row.original.counterpartyTin}
              </div>
            ) : null}
          </div>
        ),
      }),
      columnHelper.accessor('productName', {
        header: 'Product',
        cell: (info) => (
          <span className="block max-w-[12rem] truncate" title={info.getValue() ?? undefined}>
            {fmtText(info.getValue())}
          </span>
        ),
      }),
      columnHelper.accessor('quantityKg', {
        header: () => <span className="block text-right">Qty</span>,
        cell: (info) => (
          <span className="block text-right tabular-nums">{fmtKgSigned(info.getValue())}</span>
        ),
      }),
      columnHelper.accessor('amount', {
        header: () => <span className="block text-right">Amount</span>,
        cell: (info) => (
          <span className="block text-right font-medium tabular-nums">{fmtGel(info.getValue())}</span>
        ),
      }),
      columnHelper.accessor('unresolvedAmount', {
        header: () => <span className="block text-right">Unresolved</span>,
        cell: (info) => (
          <span
            className={cn('block text-right tabular-nums', toneClass[gapTone(info.getValue())])}
          >
            {fmtGel(info.getValue())}
          </span>
        ),
      }),
      columnHelper.accessor('status', {
        header: 'Status',
        cell: ({ row }) => (
          <div className="flex flex-col items-start gap-1">
            <StatusBadge status={row.original.status} />
            {/* Why this row says what it says, when a rule and not a person said it. */}
            <RuleBadge mapping={row.original.mapping} />
          </div>
        ),
      }),
      columnHelper.accessor('description', {
        header: 'Raw description',
        enableSorting: false,
        cell: ({ row }) => (
          <div className="max-w-[20rem]">
            <div className="truncate" title={row.original.description ?? undefined}>
              {fmtText(row.original.description)}
            </div>
            {row.original.additionalInformation ? (
              <div
                className="truncate text-[11px] text-muted-foreground"
                title={row.original.additionalInformation}
              >
                {row.original.additionalInformation}
              </div>
            ) : null}
          </div>
        ),
      }),
    ]

    if (!onMap) return base

    return [
      ...base,
      columnHelper.display({
        id: 'actions',
        header: '',
        cell: ({ row }) => (
          <Button
            type="button"
            size="sm"
            variant="outline"
            className="h-7"
            onClick={(event) => {
              event.stopPropagation()
              onMap(row.original)
            }}
          >
            Map
          </Button>
        ),
      }),
    ]
  }, [onMap])

  const table = useReactTable({
    data: rows,
    columns,
    state: { sorting },
    onSortingChange: setSorting,
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
  })

  if (rows.length === 0) {
    return <p className="py-4 text-sm text-muted-foreground">{emptyMessage}</p>
  }

  return (
    <div className={cn('overflow-x-auto', className)}>
      <table className="w-full border-collapse text-[11px]">
        <thead>
          {table.getHeaderGroups().map((headerGroup) => (
            <tr key={headerGroup.id} className="border-b border-border">
              {headerGroup.headers.map((header) => {
                const sortable = header.column.getCanSort()
                const sorted = header.column.getIsSorted()
                return (
                  <th
                    key={header.id}
                    className="px-2 py-1 text-left font-semibold text-muted-foreground"
                  >
                    {header.isPlaceholder ? null : sortable ? (
                      <button
                        type="button"
                        className="inline-flex items-center gap-1 hover:text-foreground"
                        onClick={header.column.getToggleSortingHandler()}
                      >
                        {flexRender(header.column.columnDef.header, header.getContext())}
                        {sorted === 'asc' ? <ArrowUp className="h-3 w-3" /> : null}
                        {sorted === 'desc' ? <ArrowDown className="h-3 w-3" /> : null}
                      </button>
                    ) : (
                      flexRender(header.column.columnDef.header, header.getContext())
                    )}
                  </th>
                )
              })}
            </tr>
          ))}
        </thead>
        <tbody>
          {table.getRowModel().rows.map((row) => {
            const key = sourceRowKey(row.original)
            return (
              <tr
                key={key}
                onClick={onSelect ? () => onSelect(row.original) : undefined}
                className={cn(
                  'border-b border-border/70 align-top',
                  onSelect && 'cursor-pointer hover:bg-accent/50',
                  selectedKey === key && 'bg-accent'
                )}
              >
                {row.getVisibleCells().map((cell) => (
                  <td key={cell.id} className="px-2 py-1">
                    {flexRender(cell.column.columnDef.cell, cell.getContext())}
                  </td>
                ))}
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
