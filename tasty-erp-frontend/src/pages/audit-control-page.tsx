import * as React from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { startOfMonth, endOfMonth } from 'date-fns'
import { AlertTriangle, Download, RefreshCw, ShieldCheck } from 'lucide-react'
import { auditApi, configApi } from '@/lib/api-client'
import type {
  AuditDashboard,
  CategoryCashGap,
  CategoryLedgerInput,
  CategoryVat,
  DualLedger,
  FormalCommission,
  InventoryLedger,
  SuppliesLine,
} from '@/types/domain'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { formatCurrency, formatNumber, formatDate, formatDateISO } from '@/lib/utils'

const PRODUCT_FILTERS = [
  { value: '', label: 'All products' },
  { value: 'BEEF', label: 'Beef' },
  { value: 'PORK', label: 'Pork' },
  { value: 'SHEEP', label: 'Sheep' },
  { value: 'CHICKEN', label: 'Chicken' },
  { value: 'FAT', label: 'Fat' },
  { value: 'OTHER_FOOD', label: 'Other food' },
  { value: 'SUPPLIES', label: 'Supplies' },
  { value: 'OTHER', label: 'Other' },
]

export function AuditControlPage() {
  const queryClient = useQueryClient()
  const [startDate, setStartDate] = React.useState(() => formatDateISO(startOfMonth(new Date())))
  const [endDate, setEndDate] = React.useState(() => formatDateISO(endOfMonth(new Date())))
  const [product, setProduct] = React.useState('')

  // Applied filters drive the query; the inputs are staged until "Apply".
  const [applied, setApplied] = React.useState({ startDate, endDate, product })
  const [tab, setTab] = React.useState('inventory')

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

  // Dual-ledger (BOR-76) is fetched lazily — only once its tab is opened.
  const dualLedgerQuery = useQuery({
    queryKey: ['audit-dual-ledger', applied.startDate, applied.endDate, applied.product],
    queryFn: () =>
      auditApi.getDualLedger({
        startDate: applied.startDate,
        endDate: applied.endDate,
        product: applied.product || undefined,
      }),
    enabled:
      Boolean(applied.startDate && applied.endDate) &&
      (tab === 'dual-ledger' || tab === 'vat' || tab === 'supplies'),
    staleTime: 1000 * 60 * 5,
    retry: 1,
  })

  const paidMutation = useMutation({
    mutationFn: ({ key, markedPaid }: { key: string; markedPaid: boolean }) =>
      auditApi.setManualPaid(key, markedPaid),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['audit-dashboard'] }),
  })

  // Toggle a customer's "unreal" (exception) flag from the reconciliation table.
  const unrealMutation = useMutation({
    mutationFn: ({ id, unreal }: { id: string; unreal: boolean }) =>
      unreal ? configApi.addUnrealCustomer(id) : configApi.removeUnrealCustomer(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['audit-dashboard'] }),
  })

  const data = dashboardQuery.data

  // Stable callbacks so memoized children (ReconciliationSection) don't re-render
  // on every parent render (BOR-75 memoization).
  const handleTogglePaid = React.useCallback(
    (key: string, markedPaid: boolean) => paidMutation.mutate({ key, markedPaid }),
    // mutate is stable per TanStack Query docs
    // eslint-disable-next-line react-hooks/exhaustive-deps
    []
  )
  const handleToggleUnreal = React.useCallback(
    (id: string, unreal: boolean) => unrealMutation.mutate({ id, unreal }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    []
  )

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

          <Tabs value={tab} onValueChange={setTab}>
            <TabsList className="flex-wrap">
              <TabsTrigger value="inventory">Inventory</TabsTrigger>
              <TabsTrigger value="reconciliation">Reconciliation</TabsTrigger>
              <TabsTrigger value="dual-ledger">Dual-Ledger</TabsTrigger>
              <TabsTrigger value="vat">VAT</TabsTrigger>
              <TabsTrigger value="supplies">Supplies</TabsTrigger>
            </TabsList>

            <TabsContent value="inventory" className="space-y-4">
              <InventorySection ledgers={data.inventoryLedgers} />
            </TabsContent>

            <TabsContent value="reconciliation" className="space-y-4">
              <ReconciliationSection
                data={data}
                onTogglePaid={handleTogglePaid}
                togglingKey={paidMutation.isPending ? paidMutation.variables?.key : undefined}
                onToggleUnreal={handleToggleUnreal}
                togglingUnrealKey={unrealMutation.isPending ? unrealMutation.variables?.id : undefined}
              />
              <TargetedExpenseCard data={data} />
              <ExceptionsCard data={data} />
            </TabsContent>

            <TabsContent value="dual-ledger" className="space-y-4">
              <DualLedgerTab query={dualLedgerQuery} />
            </TabsContent>

            <TabsContent value="vat" className="space-y-4">
              <VatTab query={dualLedgerQuery} />
            </TabsContent>

            <TabsContent value="supplies" className="space-y-4">
              <SuppliesTab query={dualLedgerQuery} />
            </TabsContent>
          </Tabs>
        </>
      ) : null}
    </div>
  )
}

// ==================== Dual-Ledger (BOR-76) ====================

type DualQuery = {
  data?: DualLedger
  isLoading: boolean
  isError: boolean
  error: unknown
}

function DualLedgerTab({ query }: { query: DualQuery }) {
  const queryClient = useQueryClient()

  const inputsQuery = useQuery({
    queryKey: ['dual-ledger-inputs'],
    queryFn: () => configApi.getDualLedgerInputs(),
    staleTime: 1000 * 60,
  })
  const inputsByCat = React.useMemo(() => {
    const m = new Map<string, CategoryLedgerInput>()
    ;(inputsQuery.data ?? []).forEach((i) => m.set(i.category, i))
    return m
  }, [inputsQuery.data])

  const saveMutation = useMutation({
    mutationFn: (entry: CategoryLedgerInput) => configApi.setDualLedgerInput(entry),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['audit-dual-ledger'] })
      queryClient.invalidateQueries({ queryKey: ['dual-ledger-inputs'] })
    },
  })

  // Merge one field into the category's override object so other overrides survive.
  const saveField = React.useCallback(
    (category: string, field: keyof CategoryLedgerInput, value: number | null) => {
      const cur = inputsByCat.get(category) ?? { category }
      saveMutation.mutate({ ...cur, category, [field]: value })
    },
    [inputsByCat, saveMutation]
  )

  if (query.isError) {
    return (
      <Card>
        <CardContent className="p-6 text-sm text-destructive">
          Failed to load dual-ledger: {(query.error as Error)?.message}
        </CardContent>
      </Card>
    )
  }
  if (query.isLoading || !query.data) {
    return <Skeleton className="h-64 w-full" />
  }
  const d = query.data
  const saving = saveMutation.isPending

  return (
    <>
      <CashGapCard
        title="Purchase Cash Shortage"
        subtitle="Real price paid vs the documented purchase price. Negative = cash shortage (paid more than paper)."
        rows={d.purchaseShortages}
        kind="purchase"
        total={d.totalPurchaseShortage}
        saving={saving}
        onSaveField={saveField}
      />
      <CashGapCard
        title="Sales Cash Surplus"
        subtitle="Real price received vs the documented sale price. Positive = cash surplus (received more than paper)."
        rows={d.saleSurpluses}
        kind="sale"
        total={d.totalSaleSurplus}
        saving={saving}
        onSaveField={saveField}
      />
      <FormalCommissionCard commissions={d.formalCommissions} total={d.totalFormalCommission} />
    </>
  )
}

// Documented | Real | Gap table, reused for purchase shortage & sale surplus.
function CashGapCard({
  title,
  subtitle,
  rows,
  kind,
  total,
  saving,
  onSaveField,
}: {
  title: string
  subtitle: string
  rows: CategoryCashGap[]
  kind: 'purchase' | 'sale'
  total: number
  saving: boolean
  onSaveField: (category: string, field: keyof CategoryLedgerInput, value: number | null) => void
}) {
  const docPriceField: keyof CategoryLedgerInput = kind === 'purchase' ? 'docPurchasePrice' : 'docSalePrice'
  const realPriceField: keyof CategoryLedgerInput = kind === 'purchase' ? 'realPurchasePrice' : 'realSalePrice'
  const realKgField: keyof CategoryLedgerInput = kind === 'purchase' ? 'realPurchaseKg' : 'realSaleKg'

  return (
    <Card>
      <CardHeader className="p-4 pb-2">
        <CardTitle className="text-base">{title}</CardTitle>
        <CardDescription>{subtitle}</CardDescription>
      </CardHeader>
      <CardContent className="p-0">
        <div className="overflow-x-auto">
          <table className="min-w-full divide-y text-sm">
            <thead className="bg-muted/50 text-xs uppercase text-muted-foreground">
              <tr>
                <Th>Category</Th>
                <Th right>Doc kg</Th>
                <Th right>Doc price</Th>
                <Th right>Doc total</Th>
                <Th right>Real kg</Th>
                <Th right>Real price</Th>
                <Th right>Real total</Th>
                <Th right>Gap</Th>
              </tr>
            </thead>
            <tbody className="divide-y">
              {rows.map((r) => (
                <tr key={r.category}>
                  <Td>{CATEGORY_LABELS[r.category] ?? r.category}</Td>
                  <Td right>{formatNumber(r.docKg)}</Td>
                  <Td right>
                    <EditableNumber
                      value={r.docPrice}
                      disabled={saving}
                      onSave={(v) => onSaveField(r.category, docPriceField, v)}
                    />
                  </Td>
                  <Td right>{formatCurrency(r.docTotal)}</Td>
                  <Td right>
                    <EditableNumber
                      value={r.realKg}
                      disabled={saving}
                      onSave={(v) => onSaveField(r.category, realKgField, v)}
                    />
                  </Td>
                  <Td right>
                    <EditableNumber
                      value={r.realPrice}
                      disabled={saving}
                      onSave={(v) => onSaveField(r.category, realPriceField, v)}
                    />
                  </Td>
                  <Td right>{formatCurrency(r.realTotal)}</Td>
                  <Td right>
                    <span className={gapClass(r.gap)}>{formatCurrency(r.gap)}</span>
                  </Td>
                </tr>
              ))}
              {!rows.length ? (
                <tr>
                  <Td>
                    <span className="text-muted-foreground">No movements in range.</span>
                  </Td>
                </tr>
              ) : null}
            </tbody>
            {rows.length ? (
              <tfoot>
                <tr className="border-t font-medium">
                  <Td>Total</Td>
                  <Td right> </Td>
                  <Td right> </Td>
                  <Td right> </Td>
                  <Td right> </Td>
                  <Td right> </Td>
                  <Td right> </Td>
                  <Td right>
                    <span className={gapClass(total)}>{formatCurrency(total)}</span>
                  </Td>
                </tr>
              </tfoot>
            ) : null}
          </table>
        </div>
      </CardContent>
    </Card>
  )
}

function FormalCommissionCard({
  commissions,
  total,
}: {
  commissions: FormalCommission[]
  total: number
}) {
  const queryClient = useQueryClient()
  const [customerId, setCustomerId] = React.useState('')
  const [customerName, setCustomerName] = React.useState('')

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['audit-dual-ledger'] })
  }
  const addMutation = useMutation({
    mutationFn: () =>
      configApi.setFormalSalesCustomer({
        customerId,
        customerName: customerName || undefined,
        commissionPerKg: 0.5,
      }),
    onSuccess: () => {
      setCustomerId('')
      setCustomerName('')
      invalidate()
    },
  })
  const rateMutation = useMutation({
    mutationFn: (v: { id: string; name?: string | null; rate: number }) =>
      configApi.setFormalSalesCustomer({ customerId: v.id, customerName: v.name ?? undefined, commissionPerKg: v.rate }),
    onSuccess: invalidate,
  })
  const removeMutation = useMutation({
    mutationFn: (id: string) => configApi.removeFormalSalesCustomer(id),
    onSuccess: invalidate,
  })

  return (
    <Card>
      <CardHeader className="p-4 pb-2">
        <CardTitle className="text-base">Formal Sales — Commission AR</CardTitle>
        <CardDescription>
          Documentation-only customers. Their RS.ge invoice AR is ignored; real receivable = documented kg ×
          commission. Total commission: <span className="font-medium">{formatCurrency(total)}</span>
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3 p-4 pt-0">
        <div className="flex flex-wrap items-center gap-2">
          <input
            value={customerId}
            onChange={(e) => setCustomerId(e.target.value)}
            placeholder="Customer TIN / ID"
            className="h-9 w-40 rounded-md border border-input bg-background px-3 text-sm"
          />
          <input
            value={customerName}
            onChange={(e) => setCustomerName(e.target.value)}
            placeholder="Name (optional)"
            className="h-9 flex-1 rounded-md border border-input bg-background px-3 text-sm"
          />
          <Button
            size="sm"
            disabled={!customerId.trim() || addMutation.isPending}
            onClick={() => addMutation.mutate()}
          >
            Add formal customer
          </Button>
        </div>
        <div className="overflow-x-auto">
          <table className="min-w-full divide-y text-sm">
            <thead className="bg-muted/50 text-xs uppercase text-muted-foreground">
              <tr>
                <Th>Customer</Th>
                <Th right>Documented kg</Th>
                <Th right>Documented AR (ignored)</Th>
                <Th right>Commission /kg</Th>
                <Th right>Commission AR</Th>
                <Th> </Th>
              </tr>
            </thead>
            <tbody className="divide-y">
              {commissions.map((c) => (
                <tr key={c.customerId}>
                  <Td>
                    <div className="font-medium">{c.customerName || c.customerId}</div>
                    <div className="text-xs text-muted-foreground">{c.customerId}</div>
                  </Td>
                  <Td right>{formatNumber(c.documentedKg)}</Td>
                  <Td right className="text-muted-foreground line-through">
                    {formatCurrency(c.documentedAr)}
                  </Td>
                  <Td right>
                    <EditableNumber
                      value={c.commissionPerKg}
                      disabled={rateMutation.isPending}
                      onSave={(v) =>
                        rateMutation.mutate({ id: c.customerId, name: c.customerName, rate: v ?? 0 })
                      }
                    />
                  </Td>
                  <Td right className="font-medium text-emerald-600">
                    {formatCurrency(c.commissionAr)}
                  </Td>
                  <Td>
                    <Button
                      size="sm"
                      variant="ghost"
                      disabled={removeMutation.isPending}
                      onClick={() => removeMutation.mutate(c.customerId)}
                    >
                      Remove
                    </Button>
                  </Td>
                </tr>
              ))}
              {!commissions.length ? (
                <tr>
                  <Td>
                    <span className="text-muted-foreground">No formal-sales customers configured.</span>
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

// ==================== VAT tab (BOR-76) ====================

function VatTab({ query }: { query: DualQuery }) {
  if (query.isError) {
    return (
      <Card>
        <CardContent className="p-6 text-sm text-destructive">
          Failed to load VAT: {(query.error as Error)?.message}
        </CardContent>
      </Card>
    )
  }
  if (query.isLoading || !query.data) {
    return <Skeleton className="h-64 w-full" />
  }
  const rows: CategoryVat[] = query.data.vat
  const total = query.data.totalVatPayable
  const hasProjection = rows.some((r) => r.projectedVatPayable != null)

  return (
    <Card>
      <CardHeader className="p-4 pb-2">
        <CardTitle className="text-base">VAT (18% inclusive)</CardTitle>
        <CardDescription>
          Actual liability from documented RS.ge amounts: <b>VAT payable = output − input</b>. The write-off %
          and kg columns are context; the "Projected" column is a what-if at that write-off and does not change
          the filed figure.
        </CardDescription>
      </CardHeader>
      <CardContent className="p-0">
        <div className="overflow-x-auto">
          <table className="min-w-full divide-y text-sm">
            <thead className="bg-muted/50 text-xs uppercase text-muted-foreground">
              <tr>
                <Th>Category</Th>
                <Th right>Purchase gross</Th>
                <Th right>Input VAT</Th>
                <Th right>Sales gross</Th>
                <Th right>Output VAT</Th>
                <Th right>VAT payable</Th>
                <Th right>Write-off %</Th>
                <Th right>Purch. kg</Th>
                <Th right>Sold kg</Th>
                {hasProjection ? <Th right>Projected</Th> : null}
              </tr>
            </thead>
            <tbody className="divide-y">
              {rows.map((r) => (
                <tr key={r.category}>
                  <Td>{CATEGORY_LABELS[r.category] ?? r.category}</Td>
                  <Td right>{formatCurrency(r.purchaseGross)}</Td>
                  <Td right>{formatCurrency(r.purchaseVat)}</Td>
                  <Td right>{formatCurrency(r.salesGross)}</Td>
                  <Td right>{formatCurrency(r.salesVat)}</Td>
                  <Td right>
                    <span className={r.vatPayable >= 0 ? 'font-medium' : 'font-medium text-emerald-600'}>
                      {formatCurrency(r.vatPayable)}
                    </span>
                  </Td>
                  <Td right>{formatNumber(r.writeOffPercent)}%</Td>
                  <Td right>{formatNumber(r.documentedPurchaseKg)}</Td>
                  <Td right>{formatNumber(r.documentedSoldKg)}</Td>
                  {hasProjection ? (
                    <Td right>
                      {r.projectedVatPayable != null ? (
                        <span className="text-muted-foreground">{formatCurrency(r.projectedVatPayable)}</span>
                      ) : (
                        <span className="text-muted-foreground">—</span>
                      )}
                    </Td>
                  ) : null}
                </tr>
              ))}
              {!rows.length ? (
                <tr>
                  <Td>
                    <span className="text-muted-foreground">No movements in range.</span>
                  </Td>
                </tr>
              ) : null}
            </tbody>
            {rows.length ? (
              <tfoot>
                <tr className="border-t font-medium">
                  <Td>Total VAT payable</Td>
                  <Td right> </Td>
                  <Td right> </Td>
                  <Td right> </Td>
                  <Td right> </Td>
                  <Td right>{formatCurrency(total)}</Td>
                  <Td right> </Td>
                  <Td right> </Td>
                  <Td right> </Td>
                  {hasProjection ? <Td right> </Td> : null}
                </tr>
              </tfoot>
            ) : null}
          </table>
        </div>
        {query.data.totalSuppliesInputVat > 0 ? (
          <div className="border-t px-4 py-2 text-xs text-muted-foreground">
            Total already nets a deductible supplies input-VAT credit of{' '}
            <span className="font-medium">{formatCurrency(query.data.totalSuppliesInputVat)}</span> (see the
            Supplies tab).
          </div>
        ) : null}
      </CardContent>
    </Card>
  )
}

// ==================== Supplies tab (purchase-only expenses) ====================

function SuppliesTab({ query }: { query: DualQuery }) {
  if (query.isError) {
    return (
      <Card>
        <CardContent className="p-6 text-sm text-destructive">
          Failed to load supplies: {(query.error as Error)?.message}
        </CardContent>
      </Card>
    )
  }
  if (query.isLoading || !query.data) {
    return <Skeleton className="h-64 w-full" />
  }
  const rows: SuppliesLine[] = query.data.supplies
  return (
    <Card>
      <CardHeader className="p-4 pb-2">
        <CardTitle className="text-base">Supplies (purchase-only)</CardTitle>
        <CardDescription>
          Non-sold purchases — car maintenance, spare parts, etc. Excluded from the meat inventory and cash-gap
          math; their input VAT is deductible and already credited in the VAT tab. Assign products to the
          Supplies category on the Product Categories page.
        </CardDescription>
      </CardHeader>
      <CardContent className="p-0">
        <div className="overflow-x-auto">
          <table className="min-w-full divide-y text-sm">
            <thead className="bg-muted/50 text-xs uppercase text-muted-foreground">
              <tr>
                <Th>Product</Th>
                <Th right>Qty (kg)</Th>
                <Th right>Spend</Th>
                <Th right>Input VAT</Th>
              </tr>
            </thead>
            <tbody className="divide-y">
              {rows.map((r) => (
                <tr key={r.productName}>
                  <Td>{r.productName}</Td>
                  <Td right>{formatNumber(r.quantityKg)}</Td>
                  <Td right>{formatCurrency(r.amount)}</Td>
                  <Td right>{formatCurrency(r.inputVat)}</Td>
                </tr>
              ))}
              {!rows.length ? (
                <tr>
                  <Td>
                    <span className="text-muted-foreground">
                      No supplies purchases in range. Tag products as "Supplies" on the Product Categories page.
                    </span>
                  </Td>
                </tr>
              ) : null}
            </tbody>
            {rows.length ? (
              <tfoot>
                <tr className="border-t font-medium">
                  <Td>Total</Td>
                  <Td right> </Td>
                  <Td right>{formatCurrency(query.data.totalSuppliesSpend)}</Td>
                  <Td right>{formatCurrency(query.data.totalSuppliesInputVat)}</Td>
                </tr>
              </tfoot>
            ) : null}
          </table>
        </div>
      </CardContent>
    </Card>
  )
}

// Small editable numeric input reused across the dual-ledger tables.
function EditableNumber({
  value,
  onSave,
  disabled,
}: {
  value: number | null
  onSave: (value: number | null) => void
  disabled?: boolean
}) {
  const [draft, setDraft] = React.useState(value == null ? '' : String(value))

  React.useEffect(() => {
    setDraft(value == null ? '' : String(value))
  }, [value])

  const commit = () => {
    const raw = draft.trim()
    const n = raw === '' ? null : Number(raw)
    if (raw !== '' && (!Number.isFinite(n as number) || (n as number) < 0)) {
      setDraft(value == null ? '' : String(value))
      return
    }
    if (n !== value) onSave(n)
  }

  return (
    <input
      type="number"
      min={0}
      step="0.01"
      value={draft}
      disabled={disabled}
      onChange={(e) => setDraft(e.target.value)}
      onBlur={commit}
      onKeyDown={(e) => {
        if (e.key === 'Enter') (e.target as HTMLInputElement).blur()
      }}
      className="h-7 w-20 rounded border border-input bg-background px-1 text-right text-xs tabular-nums"
    />
  )
}

function gapClass(gap: number) {
  if (gap < -0.005) return 'font-medium text-destructive'
  if (gap > 0.005) return 'font-medium text-emerald-600'
  return 'text-muted-foreground'
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
  SHEEP: '🐑 Sheep',
  CHICKEN: '🐔 Chicken',
  FAT: 'Fat',
  OTHER_FOOD: 'Other food',
  SUPPLIES: '🔧 Supplies',
  OTHER: 'Other',
}

// Memoized (BOR-75): filter-input keystrokes re-render the page; ledger tables
// with hundreds of daily rows must only re-render when their ledger changes.
const LedgerCard = React.memo(function LedgerCard({ ledger }: { ledger: InventoryLedger }) {
  const [open, setOpen] = React.useState(false)
  const queryClient = useQueryClient()
  const isTracked = ledger.parentCategory === 'BEEF' || ledger.parentCategory === 'PORK'
  const label = CATEGORY_LABELS[ledger.parentCategory] ?? ledger.parentCategory

  // Persist the per-category write-off % server-side, then refetch the dashboard
  // so the whole ledger (box, daily rows, ending inventory, overage flags)
  // recomputes at the new rate — single source of truth, shared across devices.
  const rateMutation = useMutation({
    mutationFn: (percent: number) =>
      configApi.setWriteOffRate({ category: ledger.parentCategory, percent }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['audit-dashboard'] }),
  })
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
          {isTracked ? (
            <WriteOffMetric
              ratePercent={ledger.writeOffRatePercent ?? 28}
              writeOffKg={ledger.totalWriteOffKg}
              saving={rateMutation.isPending}
              onSave={(pct) => rateMutation.mutate(pct)}
            />
          ) : (
            <Metric label="posib Write-off kg" value={formatNumber(ledger.totalWriteOffKg)} />
          )}
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
})

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-md border bg-muted/30 px-3 py-2">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="font-medium">{value}</div>
    </div>
  )
}

// "posib Write-off kg" box with an editable % on top. Typing a new percentage and
// blurring (or pressing Enter) persists it per category; the dashboard then
// refetches and the write-off amount here — plus the daily rows below — recompute.
function WriteOffMetric({
  ratePercent,
  writeOffKg,
  saving,
  onSave,
}: {
  ratePercent: number
  writeOffKg: number
  saving: boolean
  onSave: (percent: number) => void
}) {
  const [draft, setDraft] = React.useState(String(ratePercent))

  // Re-sync the input when the persisted rate changes (e.g. after refetch).
  React.useEffect(() => {
    setDraft(String(ratePercent))
  }, [ratePercent])

  const commit = () => {
    const pct = Number(draft)
    if (!Number.isFinite(pct) || pct < 0 || pct > 100) {
      setDraft(String(ratePercent)) // revert invalid input
      return
    }
    if (pct !== ratePercent) {
      onSave(pct)
    }
  }

  return (
    <div className="rounded-md border bg-muted/30 px-3 py-2">
      <div className="flex items-center justify-between gap-1">
        <span className="text-xs text-muted-foreground">posib Write-off kg</span>
        <div className="flex items-center gap-0.5">
          <input
            type="number"
            min={0}
            max={100}
            step="0.1"
            value={draft}
            disabled={saving}
            onChange={(e) => setDraft(e.target.value)}
            onBlur={commit}
            onKeyDown={(e) => {
              if (e.key === 'Enter') (e.target as HTMLInputElement).blur()
            }}
            className="h-6 w-14 rounded border border-input bg-background px-1 text-right text-xs tabular-nums"
            aria-label="Write-off percent of purchased"
            title="Write-off as % of purchased kg"
          />
          <span className="text-xs text-muted-foreground">%</span>
        </div>
      </div>
      <div className="mt-0.5 font-medium tabular-nums">
        {saving ? <span className="text-muted-foreground">…</span> : formatNumber(writeOffKg)}
      </div>
    </div>
  )
}

// ==================== Reconciliation ====================

// Memoized (BOR-75): up to 200 rows; only re-renders when data/toggling change.
const ReconciliationSection = React.memo(function ReconciliationSection({
  data,
  onTogglePaid,
  togglingKey,
  onToggleUnreal,
  togglingUnrealKey,
}: {
  data: AuditDashboard
  onTogglePaid: (key: string, markedPaid: boolean) => void
  togglingKey?: string
  onToggleUnreal: (id: string, unreal: boolean) => void
  togglingUnrealKey?: string
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
          Real receivables vs documentation exceptions. Tick "Unreal" for customers RS.ge documents but who
          aren't real partners (their sales leave Real Totals and their debt moves to Exception). Toggle "Paid"
          to override API status.
        </CardDescription>
      </CardHeader>
      <CardContent className="p-0">
        <div className="overflow-x-auto">
          <table className="min-w-full divide-y text-sm">
            <thead className="bg-muted/50 text-xs uppercase text-muted-foreground">
              <tr>
                <Th>Customer</Th>
                <Th>Type</Th>
                <Th>Unreal</Th>
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
                  <Td>
                    <input
                      type="checkbox"
                      className="h-4 w-4 cursor-pointer accent-amber-600"
                      checked={!r.realEntity}
                      disabled={togglingUnrealKey === r.customerId}
                      onChange={() => onToggleUnreal(r.customerId, r.realEntity)}
                      aria-label="Mark customer unreal"
                      title="Mark this customer as unreal (documentation-only)"
                    />
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
})

// ==================== Targeted expense ====================

// Memoized (BOR-75): match table re-renders only when the dashboard data changes.
const TargetedExpenseCard = React.memo(function TargetedExpenseCard({ data }: { data: AuditDashboard }) {
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
})

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

function Td({
  children,
  right,
  className,
}: {
  children: React.ReactNode
  right?: boolean
  className?: string
}) {
  return (
    <td className={`px-3 py-2 ${right ? 'text-right tabular-nums' : ''} ${className ?? ''}`}>{children}</td>
  )
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
