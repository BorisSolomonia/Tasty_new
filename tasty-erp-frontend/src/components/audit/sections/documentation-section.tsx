/**
 * Documentation — RS.ge rows as the primary object (old view 06), each with the
 * two consequences it carries: what it did to stock, and what it did to cash or
 * to paper cash.
 *
 * Both effects are derived here, and the derivation is stated on screen rather
 * than implied. The inventory effect comes from the row's own direction and
 * quantity; the cash effect comes from the row's mapping splits and the
 * behavioural flags on their categories. An unmapped row therefore has no cash
 * effect the audit can claim — it shows an em dash, not a zero.
 *
 * The totals card below owns every `documentation.*` figure on the page. The
 * "inventory consequence" and "cash consequence" cards this view used to carry
 * are now links: their figures live in the inventory and cash sections, one
 * scroll away, and are printed once.
 */
import * as React from 'react'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/cn'
import type { AuditCategory, AuditSourceRow } from '@/lib/audit-api'
import { useAudit } from '../audit-context'
import { CollapsiblePanel } from '../collapsible-panel'
import { SectionEvidence } from '../evidence-panel'
import { MetricRow, SectionCard, FormulaNote } from '../metric'
import { StatusBadge } from '../status-badge'
import { RuleBadge, useMappingRuleIndex } from '../rule-badge'
import { MappingDialog } from '../mapping-dialog'
import { JumpLink } from '../section-nav'
import {
  EM_DASH,
  fmtCount,
  fmtDate,
  fmtGel,
  fmtKgSigned,
  fmtText,
  gapTone,
  hasValue,
} from '../format'

/** Stock effect from the row's own direction and quantity. */
function inventoryEffect(row: AuditSourceRow): { text: string; tone: 'neutral' | 'bad' | 'good' } {
  if (!hasValue(row.quantityKg)) return { text: EM_DASH, tone: 'neutral' }
  const direction = (row.direction ?? '').toUpperCase()
  if (direction === 'PURCHASE') return { text: fmtKgSigned(Math.abs(row.quantityKg)), tone: 'bad' }
  if (direction === 'SALE') return { text: fmtKgSigned(-Math.abs(row.quantityKg)), tone: 'good' }
  return { text: fmtKgSigned(row.quantityKg), tone: 'neutral' }
}

/** Cash effect from this row's splits and the flags on their categories. */
function cashEffect(
  row: AuditSourceRow,
  categoriesByCode: Map<string, AuditCategory>
): { text: string; detail: string } {
  const splits = row.mapping?.splits ?? []
  if (splits.length === 0) return { text: EM_DASH, detail: 'Not mapped' }

  let paper = 0
  let real = 0
  for (const split of splits) {
    const category = split.categoryCode ? categoriesByCode.get(split.categoryCode) : undefined
    const amount = split.amount ?? 0
    if (!category) continue
    const signed = category.customerReceipt
      ? amount
      : category.supplierSettlement || category.nonSupplierExpense
        ? -amount
        : amount
    if (category.paperOnly) paper += signed
    else real += signed
  }

  const parts: string[] = []
  if (Math.abs(paper) > 0.005) parts.push(`${paper > 0 ? '+' : ''}${fmtGel(paper)} paper cash`)
  if (Math.abs(real) > 0.005) parts.push(`${real > 0 ? '+' : ''}${fmtGel(real)} real cash`)

  return {
    text: parts.length > 0 ? parts.join(' · ') : 'No cash effect',
    detail: `${splits.length} split${splits.length === 1 ? '' : 's'}`,
  }
}

export function DocumentationSection() {
  const { flows, sourceRows, sourceRowsQuery, categories, showEvidence } = useAudit()
  const rulesById = useMappingRuleIndex()
  const [search, setSearch] = React.useState('')
  const [onlyUnmapped, setOnlyUnmapped] = React.useState(false)
  const [mappingRow, setMappingRow] = React.useState<AuditSourceRow | null>(null)

  const documentation = flows?.documentation ?? null

  const categoriesByCode = React.useMemo(
    () => new Map(categories.map((category) => [category.code, category])),
    [categories]
  )

  const rows = React.useMemo(() => {
    const term = search.trim().toLowerCase()
    return sourceRows
      .filter((row) => row.sourceType === 'RS_GE' || row.sourceType === 'CHECK')
      .filter((row) => (onlyUnmapped ? (row.status ?? 'UNMAPPED') === 'UNMAPPED' : true))
      .filter((row) => {
        if (!term) return true
        return [row.counterpartyName, row.productName, row.reference, row.description]
          .filter(Boolean)
          .some((field) => (field as string).toLowerCase().includes(term))
      })
  }, [sourceRows, search, onlyUnmapped])

  return (
    <div className="space-y-3">
      <SectionEvidence section="documentation" />

      {/*
        The longest table on the page — every RS.ge line in the period. Shut on
        load, with the counts on the header, so the totals card below it is
        reachable without scrolling past thousands of rows.
      */}
      <CollapsiblePanel
        title="RS.ge and evidence rows"
        summary={`${fmtCount(rows.length)} shown of ${fmtCount(
          documentation?.documentRowCount
        )} · ${fmtCount(documentation?.unmappedDocumentRowCount)} unmapped (${fmtGel(
          documentation?.unmappedDocumentValue
        )})`}
        subtitle="Every row individually mappable. Raw values are shown exactly as imported."
        actions={
          <div className="flex flex-wrap items-center gap-2">
            {/*
              `documentation.rows` is every document line in the period —
              deliberately broad, so like `cash.bankRows` it hangs off an
              explicit browse action and never off a problem.
            */}
            <button
              type="button"
              className="whitespace-nowrap text-[11px] text-primary hover:underline"
              onClick={() =>
                showEvidence({ key: 'documentation.rows', label: 'All RS.ge document rows' })
              }
            >
              Browse all
            </button>
            <label className="flex items-center gap-1.5 text-[11px] text-muted-foreground">
              <input
                type="checkbox"
                checked={onlyUnmapped}
                onChange={(event) => setOnlyUnmapped(event.target.checked)}
              />
              Only unmapped
            </label>
            <Input
              value={search}
              placeholder="Filter rows"
              className="h-7 w-36 text-[11px]"
              onChange={(event) => setSearch(event.target.value)}
            />
          </div>
        }
      >
        {sourceRowsQuery.isLoading ? (
          <Skeleton className="h-64 w-full" />
        ) : rows.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            The shared row feed returned no document rows for this filter.
          </p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-[11px]">
              <thead>
                <tr className="border-b border-border text-left text-muted-foreground">
                  <th className="py-1 pr-2 font-semibold">Date</th>
                  <th className="py-1 pr-2 font-semibold">Source</th>
                  <th className="py-1 pr-2 font-semibold">Type</th>
                  <th className="py-1 pr-2 font-semibold">Counterparty</th>
                  <th className="py-1 pr-2 font-semibold">Product</th>
                  <th className="py-1 pr-2 text-right font-semibold">Qty</th>
                  <th className="py-1 pr-2 text-right font-semibold">Value</th>
                  <th className="py-1 pr-2 text-right font-semibold">Inventory effect</th>
                  <th className="py-1 pr-2 font-semibold">Cash effect</th>
                  <th className="py-1 pr-2 font-semibold">Mapping</th>
                  <th className="py-1 font-semibold" />
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => {
                  const effect = inventoryEffect(row)
                  const cashText = cashEffect(row, categoriesByCode)
                  return (
                    <tr
                      key={`${row.sourceType}:${row.sourceRowId}`}
                      className="border-b border-border/70 align-top"
                    >
                      <td className="whitespace-nowrap py-1 pr-2">{fmtDate(row.date)}</td>
                      <td className="py-1 pr-2">
                        <div className="font-medium">{row.sourceType ?? EM_DASH}</div>
                        <div className="text-[11px] text-muted-foreground">{fmtText(row.reference)}</div>
                      </td>
                      <td className="py-1 pr-2">{fmtText(row.direction)}</td>
                      <td className="max-w-[12rem] py-1 pr-2">
                        <div className="truncate" title={row.counterpartyName ?? ''}>
                          {fmtText(row.counterpartyName)}
                        </div>
                      </td>
                      <td className="max-w-[10rem] py-1 pr-2">
                        <div className="truncate" title={row.productName ?? ''}>
                          {fmtText(row.productName)}
                        </div>
                      </td>
                      <td className="py-1 pr-2 text-right tabular-nums">{fmtKgSigned(row.quantityKg)}</td>
                      <td className="py-1 pr-2 text-right tabular-nums">{fmtGel(row.amount)}</td>
                      <td
                        className={cn(
                          'py-1 pr-2 text-right tabular-nums',
                          effect.tone === 'bad' && 'text-destructive',
                          effect.tone === 'good' && 'text-success'
                        )}
                      >
                        {effect.text}
                      </td>
                      <td className="py-1 pr-2">
                        <div>{cashText.text}</div>
                        <div className="text-[11px] text-muted-foreground">{cashText.detail}</div>
                      </td>
                      <td className="py-1 pr-2">
                        <div className="flex flex-col items-start gap-1">
                          <StatusBadge status={row.status} />
                          <RuleBadge mapping={row.mapping} rulesById={rulesById} />
                        </div>
                      </td>
                      <td className="py-1">
                        <Button
                          type="button"
                          size="sm"
                          variant="outline"
                          className="h-7"
                          onClick={() => setMappingRow(row)}
                        >
                          Edit map
                        </Button>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
            <FormulaNote>
              Inventory effect is derived from each row&apos;s own direction and quantity (purchase
              adds, sale removes). Cash effect is derived from that row&apos;s mapping splits and the
              flags on their categories — paper-only categories move paper cash, the rest move real
              cash. A row nobody has mapped shows an em dash, because the audit cannot state an
              effect it has not been told.
            </FormulaNote>
          </div>
        )}
      </CollapsiblePanel>

      <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
        <SectionCard
          title="Documentation totals"
          subtitle="Every figure the documentation flow reports for this period."
        >
          <MetricRow
            label="Purchase documents"
            value={fmtGel(documentation?.documentPurchaseValue)}
            hint={fmtKgSigned(documentation?.documentPurchaseKg)}
          />
          <MetricRow
            label="Sales documents"
            value={fmtGel(documentation?.documentSalesValue)}
            hint={fmtKgSigned(documentation?.documentSalesKg)}
          />
          {/*
            `documentation.writeOffKg` and `inventory.documentWriteOffKg` are
            two fields counting the same events from two flows. Both are shown,
            under distinct labels: hiding one would hide a disagreement between
            the flows, which is exactly the kind of thing this page exists to
            surface.
          */}
          <MetricRow
            label="Write-offs recorded on documents"
            value={fmtKgSigned(documentation?.writeOffKg)}
            hint="The inventory section counts the same events in its stock arithmetic"
          />
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
            label="Flagged documents"
            value={fmtGel(documentation?.flaggedDocumentValue)}
            hint={`${fmtCount(documentation?.flaggedDocumentCount)} rows`}
            tone={gapTone(documentation?.flaggedDocumentValue)}
          />
          <MetricRow
            label="Unmapped rows"
            value={fmtCount(documentation?.unmappedDocumentRowCount)}
            hint={fmtGel(documentation?.unmappedDocumentValue)}
            tone={
              documentation && documentation.unmappedDocumentRowCount > 0 ? 'warn' : 'neutral'
            }
            drilldownKey="documentation.unmapped"
          />
          <MetricRow
            label="Document rows in period"
            value={fmtCount(documentation?.documentRowCount)}
          />
          <FormulaNote>
            Showing {fmtCount(rows.length)} rows from the shared feed above; the flow reports{' '}
            {fmtCount(documentation?.documentRowCount)} document rows for this period. Where the two
            differ, the feed was capped rather than the period being smaller.
          </FormulaNote>
        </SectionCard>

        <SectionCard
          title="What these documents did elsewhere"
          subtitle="Each consequence is measured once, in the flow that owns it."
        >
          <ul className="space-y-2">
            <li>
              <JumpLink to="inventory">Document stock, real stock and the gap</JumpLink>
              <p className="text-xs text-muted-foreground">
                The stock these purchases, sales and write-offs produced.
              </p>
            </li>
            <li>
              <JumpLink to="cash">Paper cash created, reduced and net unexplained</JumpLink>
              <p className="text-xs text-muted-foreground">
                What the paper-only rows above did to accounting cash.
              </p>
            </li>
            <li>
              <JumpLink to="cash">Checks and unsupported settlement evidence</JumpLink>
              <p className="text-xs text-muted-foreground">
                Evidence held against supplier settlement, which is not money.
              </p>
            </li>
          </ul>
        </SectionCard>
      </div>

      <MappingDialog row={mappingRow} onClose={() => setMappingRow(null)} />
    </div>
  )
}
