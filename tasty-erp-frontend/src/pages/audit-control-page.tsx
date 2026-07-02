import * as React from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { startOfMonth, endOfMonth } from 'date-fns'
import { AlertTriangle, Download, RefreshCw, ShieldCheck } from 'lucide-react'
import { auditApi } from '@/lib/api-client'
import type { AuditDashboard, InventoryLedger } from '@/types/domain'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { formatCurrency, formatNumber, formatDate, formatDateISO } from '@/lib/utils'

const PRODUCT_FILTERS = [
  { value: '', label: 'All products' },
  { value: 'BEEF', label: 'Beef' },
  { value: 'PORK', label: 'Pork' },
  { value: 'OTHER', label: 'Other' },
]

export function AuditControlPage() {
  const queryClient = useQueryClient()
  const [startDate, setStartDate] = React.useState(() => formatDateISO(startOfMonth(new Date())))
  const [endDate, setEndDate] = React.useState(() => formatDateISO(endOfMonth(new Date())))
  const [product, setProduct] = React.useState('')

  // Applied filters drive the query; the inputs are staged until "Apply".
  const [applied, setApplied] = React.useState({ startDate, endDate, product })

  const dashboardQuery = useQuery({
    queryKey: ['audit-dashboard', applied.startDate, applied.endDate, applied.product],
    queryFn: () =>
      auditApi.getDashboard({
        startDate: applied.startDate,
        endDate: applied.endDate,
        product: applied.product || undefined,
      }),
    enabled: Boolean(applied.startDate && applied.endDate),
    staleTime: 1000 * 60 * 5,
    retry: 1,
  })

  const paidMutation = useMutation({
    mutationFn: ({ key, markedPaid }: { key: string; markedPaid: boolean }) =>
      auditApi.setManualPaid(key, markedPaid),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['audit-dashboard'] }),
  })

  const data = dashboardQuery.data

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-2">
        <ShieldCheck className="h-5 w-5 text-primary" />
        <div>
          <h1 className="text-lg font-semibold tracking-tight">Audit Control</h1>
          <p className="text-sm text-muted-foreground">
            Inventory, processing write-offs and debt reconciliation
          </p>
        </div>
      </div>

      <FilterBar
        startDate={startDate}
        endDate={endDate}
        product={product}
        onStartDate={setStartDate}
        onEndDate={setEndDate}
        onProduct={setProduct}
        onApply={() => setApplied({ startDate, endDate, product })}
        onExport={() => data && exportLedgerWorkbook(data)}
        canExport={Boolean(data && data.inventoryLedgers.length)}
        loading={dashboardQuery.isFetching}
      />

      {dashboardQuery.isError && (
        <Card>
          <CardContent className="p-6 text-sm text-destructive">
            Failed to load dashboard: {(dashboardQuery.error as Error)?.message}
          </CardContent>
        </Card>
      )}

      {dashboardQuery.isLoading ? (
        <LoadingState />
      ) : data ? (
        <>
          <RealTotalsCards data={data} />
          <InventorySection ledgers={data.inventoryLedgers} />
          <ReconciliationSection
            data={data}
            onTogglePaid={(key, markedPaid) => paidMutation.mutate({ key, markedPaid })}
            togglingKey={paidMutation.isPending ? paidMutation.variables?.key : undefined}
          />
          <TargetedExpenseCard data={data} />
          <ExceptionsCard data={data} />
        </>
      ) : null}
    </div>
  )
}

// ==================== Filter bar ====================

function FilterBar(props: {
  startDate: string
  endDate: string
  product: string
  onStartDate: (v: string) => void
  onEndDate: (v: string) => void
  onProduct: (v: string) => void
  onApply: () => void
  onExport: () => void
  canExport: boolean
  loading: boolean
}) {
  return (
    <Card>
      <CardContent className="flex flex-wrap items-end gap-3 p-4">
        <Field label="Start date">
          <input
            type="date"
            value={props.startDate}
            onChange={(e) => props.onStartDate(e.target.value)}
            className="h-9 rounded-md border border-input bg-background px-3 text-sm"
          />
        </Field>
        <Field label="End date">
          <input
            type="date"
            value={props.endDate}
            onChange={(e) => props.onEndDate(e.target.value)}
            className="h-9 rounded-md border border-input bg-background px-3 text-sm"
          />
        </Field>
        <Field label="Product">
          <select
            value={props.product}
            onChange={(e) => props.onProduct(e.target.value)}
            className="h-9 rounded-md border border-input bg-background px-3 text-sm"
          >
            {PRODUCT_FILTERS.map((p) => (
              <option key={p.value} value={p.value}>
                {p.label}
              </option>
            ))}
          </select>
        </Field>
        <Button onClick={props.onApply} disabled={props.loading}>
          {props.loading ? <RefreshCw className="mr-2 h-4 w-4 animate-spin" /> : null}
          Apply
        </Button>
        <Button variant="outline" onClick={props.onExport} disabled={!props.canExport}>
          <Download className="mr-2 h-4 w-4" />
          Export ledger
        </Button>
      </CardContent>
    </Card>
  )
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-xs text-muted-foreground">{label}</span>
      {children}
    </label>
  )
}

// ==================== Real totals ====================

function RealTotalsCards({ data }: { data: AuditDashboard }) {
  const t = data.realTotals
  return (
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
      <StatCard
        title="Real Total Sales"
        value={formatCurrency(t.realTotalSales)}
        hint={`${t.realEntityCount} real entities · ${formatCurrency(t.excludedSales)} excluded`}
      />
      <StatCard
        title="Real Total Purchases"
        value={formatCurrency(t.realTotalPurchases)}
        hint={`${formatCurrency(t.excludedPurchases)} excluded (non-real)`}
      />
      <StatCard
        title="Real Debt"
        value={formatCurrency(data.realDebtTotal)}
        hint="Receivable from real business partners"
      />
      <StatCard
        title="Exception Debt"
        value={formatCurrency(data.exceptionDebtTotal)}
        hint={`${t.excludedEntityCount} exception-only entities`}
      />
    </div>
  )
}

function StatCard({ title, value, hint }: { title: string; value: string; hint?: string }) {
  return (
    <Card>
      <CardHeader className="p-4 pb-2">
        <CardDescription className="text-xs">{title}</CardDescription>
        <CardTitle className="text-xl">{value}</CardTitle>
      </CardHeader>
      {hint ? <CardContent className="p-4 pt-0 text-xs text-muted-foreground">{hint}</CardContent> : null}
    </Card>
  )
}

// ==================== Inventory ====================

function InventorySection({ ledgers }: { ledgers: InventoryLedger[] }) {
  if (!ledgers.length) {
    return (
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Inventory & write-off ledger</CardTitle>
          <CardDescription>No product movements found for the selected range.</CardDescription>
        </CardHeader>
      </Card>
    )
  }
  return (
    <div className="space-y-4">
      {ledgers.map((ledger) => (
        <LedgerCard key={ledger.parentCategory} ledger={ledger} />
      ))}
    </div>
  )
}

const CATEGORY_LABELS: Record<string, string> = {
  BEEF: '🐄 Beef',
  PORK: '🐷 Pork',
  OTHER: 'Unclassified',
}

function LedgerCard({ ledger }: { ledger: InventoryLedger }) {
  const [open, setOpen] = React.useState(false)
  const isTracked = ledger.parentCategory === 'BEEF' || ledger.parentCategory === 'PORK'
  const label = CATEGORY_LABELS[ledger.parentCategory] ?? ledger.parentCategory
  return (
    <Card>
      <CardHeader className="p-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div>
            <CardTitle className="text-base">
              {label}
              {!isTracked ? (
                <span className="ml-2 rounded bg-muted px-2 py-0.5 align-middle text-xs font-normal text-muted-foreground">
                  passthrough · no write-off
                </span>
              ) : null}
            </CardTitle>
            <CardDescription>
              {ledger.childProducts.length} child product(s) aggregated
              {isTracked && ledger.overageDays > 0 ? (
                <span className="ml-2 inline-flex items-center gap-1 text-destructive">
                  <AlertTriangle className="h-3 w-3" /> {ledger.overageDays} overage day(s)
                </span>
              ) : null}
            </CardDescription>
          </div>
          <Button variant="ghost" size="sm" onClick={() => setOpen((v) => !v)}>
            {open ? 'Hide daily rows' : 'Show daily rows'}
          </Button>
        </div>
        <div className="mt-2 grid grid-cols-2 gap-2 text-sm sm:grid-cols-5">
          <Metric label="Opening kg" value={formatNumber(ledger.openingStockKg)} />
          <Metric label="Purchased kg" value={formatNumber(ledger.totalPurchasedKg)} />
          <Metric label="Sold kg" value={formatNumber(ledger.totalSoldKg)} />
          <Metric label="posib Write-off kg" value={formatNumber(ledger.totalWriteOffKg)} />
          <Metric label="On hand kg" value={formatNumber(ledger.endingInventoryKg)} />
        </div>
      </CardHeader>
      {open ? (
        <CardContent className="p-0">
          <div className="overflow-x-auto">
            <table className="min-w-full divide-y text-sm">
              <thead className="bg-muted/50 text-xs uppercase text-muted-foreground">
                <tr>
                  <Th>Date</Th>
                  <Th right>Start</Th>
                  <Th right>Purchased</Th>
                  <Th right>Sold</Th>
                  <Th right>posib Write-off</Th>
                  <Th right>%</Th>
                  <Th right>Ending</Th>
                  <Th>Flag</Th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {ledger.dailyRows.map((r) => (
                  <tr key={r.date} className={r.overage ? 'bg-destructive/5' : undefined}>
                    <Td>{formatDate(r.date)}</Td>
                    <Td right>{formatNumber(r.startingInventoryKg)}</Td>
                    <Td right>{formatNumber(r.purchasedKg)}</Td>
                    <Td right>{formatNumber(r.soldKg)}</Td>
                    <Td right>{formatNumber(r.writeOffKg)}</Td>
                    <Td right>{formatNumber(r.writeOffPercent)}%</Td>
                    <Td right>{formatNumber(r.endingInventoryKg)}</Td>
                    <Td>
                      {r.overage ? (
                        <span className="inline-flex items-center gap-1 text-destructive">
                          <AlertTriangle className="h-3 w-3" /> overage
                        </span>
                      ) : (
                        <span className="text-muted-foreground">ok</span>
                      )}
                    </Td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </CardContent>
      ) : null}
    </Card>
  )
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-md border bg-muted/30 px-3 py-2">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="font-medium">{value}</div>
    </div>
  )
}

// ==================== Reconciliation ====================

function ReconciliationSection({
  data,
  onTogglePaid,
  togglingKey,
}: {
  data: AuditDashboard
  onTogglePaid: (key: string, markedPaid: boolean) => void
  togglingKey?: string
}) {
  const rows = data.reconciliation
  // Only show customers carrying a non-zero balance to keep the table focused.
  const visible = React.useMemo(
    () => rows.filter((r) => Math.abs(r.currentDebt) > 0.005).slice(0, 200),
    [rows]
  )
  return (
    <Card>
      <CardHeader className="p-4">
        <CardTitle className="text-base">Debt reconciliation</CardTitle>
        <CardDescription>
          Real receivables vs documentation exceptions. Toggle "Paid" to override API status.
        </CardDescription>
      </CardHeader>
      <CardContent className="p-0">
        <div className="overflow-x-auto">
          <table className="min-w-full divide-y text-sm">
            <thead className="bg-muted/50 text-xs uppercase text-muted-foreground">
              <tr>
                <Th>Customer</Th>
                <Th>Type</Th>
                <Th right>Sales</Th>
                <Th right>Payments</Th>
                <Th right>Real debt</Th>
                <Th right>Exception debt</Th>
                <Th>Override</Th>
              </tr>
            </thead>
            <tbody className="divide-y">
              {visible.map((r) => (
                <tr key={r.customerId} className={r.manuallyMarkedPaid ? 'opacity-60' : undefined}>
                  <Td>
                    <div className="font-medium">{r.customerName}</div>
                    <div className="text-xs text-muted-foreground">{r.customerId}</div>
                  </Td>
                  <Td>
                    <span
                      className={
                        r.realEntity
                          ? 'rounded bg-primary/10 px-2 py-0.5 text-xs text-primary'
                          : 'rounded bg-amber-500/10 px-2 py-0.5 text-xs text-amber-600'
                      }
                    >
                      {r.realEntity ? 'Real' : 'Exception'}
                    </span>
                  </Td>
                  <Td right>{formatCurrency(r.totalSales)}</Td>
                  <Td right>{formatCurrency(r.totalPayments)}</Td>
                  <Td right>{formatCurrency(r.realDebt)}</Td>
                  <Td right>{formatCurrency(r.exceptionDebt)}</Td>
                  <Td>
                    <Button
                      size="sm"
                      variant={r.manuallyMarkedPaid ? 'success' : 'outline'}
                      disabled={togglingKey === r.customerId}
                      onClick={() => onTogglePaid(r.customerId, !r.manuallyMarkedPaid)}
                    >
                      {r.manuallyMarkedPaid ? 'Paid ✓' : 'Mark paid'}
                    </Button>
                  </Td>
                </tr>
              ))}
              {!visible.length ? (
                <tr>
                  <Td>
                    <span className="text-muted-foreground">No outstanding balances.</span>
                  </Td>
                </tr>
              ) : null}
            </tbody>
          </table>
        </div>
      </CardContent>
    </Card>
  )
}

// ==================== Targeted expense ====================

function TargetedExpenseCard({ data }: { data: AuditDashboard }) {
  const t = data.targetedExpense
  return (
    <Card>
      <CardHeader className="p-4 pb-2">
        <CardTitle className="text-base">Targeted ID expense</CardTitle>
        <CardDescription>
          Expenses isolated for ID <span className="font-mono">{t.targetId}</span> ({t.matchCount} match
          {t.matchCount === 1 ? '' : 'es'}). Heuristic: payer ID or description match.
        </CardDescription>
      </CardHeader>
      <CardContent className="p-4 pt-0">
        <div className="text-2xl font-semibold">{formatCurrency(t.totalExpense)}</div>
        {t.matches.length ? (
          <div className="mt-3 overflow-x-auto">
            <table className="min-w-full divide-y text-sm">
              <thead className="bg-muted/50 text-xs uppercase text-muted-foreground">
                <tr>
                  <Th>Date</Th>
                  <Th>Source</Th>
                  <Th right>Amount</Th>
                  <Th>Matched on</Th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {t.matches.slice(0, 50).map((m, i) => (
                  <tr key={m.paymentId ?? i}>
                    <Td>{formatDate(m.date)}</Td>
                    <Td>{m.source}</Td>
                    <Td right>{formatCurrency(m.amount)}</Td>
                    <Td>{m.matchedOnDescription ? 'description' : 'payer ID'}</Td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
      </CardContent>
    </Card>
  )
}

// ==================== Exceptions ====================

function ExceptionsCard({ data }: { data: AuditDashboard }) {
  const queryClient = useQueryClient()
  const [description, setDescription] = React.useState('')

  const addMutation = useMutation({
    mutationFn: () => auditApi.saveException({ type: 'MANUAL', description, manual: true }),
    onSuccess: () => {
      setDescription('')
      queryClient.invalidateQueries({ queryKey: ['audit-dashboard'] })
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (id: string) => auditApi.deleteException(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['audit-dashboard'] }),
  })

  return (
    <Card>
      <CardHeader className="p-4 pb-2">
        <CardTitle className="text-base">Reconciliation exceptions</CardTitle>
        <CardDescription>{data.exceptions.length} tracked exception(s)</CardDescription>
      </CardHeader>
      <CardContent className="space-y-3 p-4 pt-0">
        <div className="flex flex-wrap items-center gap-2">
          <input
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="Describe a new exception…"
            className="h-9 flex-1 rounded-md border border-input bg-background px-3 text-sm"
          />
          <Button
            size="sm"
            disabled={!description.trim() || addMutation.isPending}
            onClick={() => addMutation.mutate()}
          >
            Add exception
          </Button>
        </div>
        <ul className="divide-y rounded-md border">
          {data.exceptions.length ? (
            data.exceptions.map((e) => (
              <li key={e.id} className="flex items-center justify-between gap-2 px-3 py-2 text-sm">
                <div>
                  <span className="rounded bg-muted px-2 py-0.5 text-xs">{e.type}</span>{' '}
                  <span>{e.description}</span>
                  {e.customerId ? (
                    <span className="ml-1 text-xs text-muted-foreground">({e.customerId})</span>
                  ) : null}
                </div>
                <Button
                  size="sm"
                  variant="ghost"
                  disabled={deleteMutation.isPending}
                  onClick={() => e.id && deleteMutation.mutate(e.id)}
                >
                  Remove
                </Button>
              </li>
            ))
          ) : (
            <li className="px-3 py-2 text-sm text-muted-foreground">No exceptions tracked.</li>
          )}
        </ul>
      </CardContent>
    </Card>
  )
}

// ==================== Table primitives ====================

function Th({ children, right }: { children: React.ReactNode; right?: boolean }) {
  return <th className={`px-3 py-2 font-medium ${right ? 'text-right' : 'text-left'}`}>{children}</th>
}

function Td({ children, right }: { children: React.ReactNode; right?: boolean }) {
  return <td className={`px-3 py-2 ${right ? 'text-right tabular-nums' : ''}`}>{children}</td>
}

function LoadingState() {
  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {Array.from({ length: 4 }).map((_, i) => (
          <Skeleton key={i} className="h-24 w-full" />
        ))}
      </div>
      <Skeleton className="h-48 w-full" />
      <Skeleton className="h-64 w-full" />
    </div>
  )
}

// ==================== Export ====================

async function exportLedgerWorkbook(data: AuditDashboard) {
  // xlsx (~130 KB gzipped) is loaded only when the user actually exports,
  // keeping it out of the initial page chunk.
  const XLSX = await import('xlsx')
  const rows: Array<Record<string, string | number>> = []
  for (const ledger of data.inventoryLedgers) {
    for (const r of ledger.dailyRows) {
      rows.push({
        Category: ledger.parentCategory,
        Date: r.date,
        'Starting Inventory (kg)': r.startingInventoryKg,
        'Purchased (kg)': r.purchasedKg,
        'Sold (kg)': r.soldKg,
        'Ending Inventory (kg)': r.endingInventoryKg,
        'posib Write-off (kg)': r.writeOffKg,
        'posib Write-off (%)': r.writeOffPercent,
        Overage: r.overage ? 'YES' : '',
      })
    }
  }

  const sheet = XLSX.utils.json_to_sheet(rows)
  const workbook = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(workbook, sheet, 'Daily Ledger')
  XLSX.writeFile(workbook, `audit-ledger_${data.startDate}_${data.endDate}.xlsx`)
}
