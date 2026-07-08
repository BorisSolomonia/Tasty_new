import * as React from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { startOfMonth, endOfMonth } from 'date-fns'
import { AlertTriangle, ChevronRight, Download, RefreshCw, ShieldCheck } from 'lucide-react'
import { auditApi, configApi } from '@/lib/api-client'
import type {
  AuditDashboard,
  CategoryLedgerInput,
  CategoryVat,
  DualLedger,
  FormalCommission,
  InventoryLedger,
  SuppliesLine,
  UnifiedCategoryCard,
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
  const [tab, setTab] = React.useState('dashboard')

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

  // Dual-ledger drives the main dashboard tab (BOR-79) plus VAT and Supplies,
  // so it is fetched alongside the dashboard.
  const dualLedgerQuery = useQuery({
    queryKey: ['audit-dual-ledger', applied.startDate, applied.endDate, applied.product],
    queryFn: () =>
      auditApi.getDualLedger({
        startDate: applied.startDate,
        endDate: applied.endDate,
        product: applied.product || undefined,
      }),
    enabled: Boolean(applied.startDate && applied.endDate),
    staleTime: 1000 * 60 * 5,
    retry: 1,
  })

  // Formal-sales customers (commission-only) — drives the reconciliation
  // "Formal" checkboxes and the formal-commission card.
  const formalQuery = useQuery({
    queryKey: ['formal-sales-customers'],
    queryFn: () => configApi.getFormalSalesCustomers(),
    staleTime: 1000 * 60,
  })
  const formalIds = React.useMemo(
    () => new Set((formalQuery.data ?? []).map((f) => canonicalId(f.customerId))),
    [formalQuery.data]
  )

  const paidMutation = useMutation({
    mutationFn: ({ key, markedPaid }: { key: string; markedPaid: boolean }) =>
      auditApi.setManualPaid(key, markedPaid),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['audit-dashboard'] }),
  })

  // Toggle a customer's "unreal" (exception) flag from the reconciliation table.
  const unrealMutation = useMutation({
    mutationFn: ({ id, unreal }: { id: string; unreal: boolean }) =>
      unreal ? configApi.addUnrealCustomer(id) : configApi.removeUnrealCustomer(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['audit-dashboard'] })
      queryClient.invalidateQueries({ queryKey: ['audit-dual-ledger'] })
    },
  })

  // Toggle a customer's "formal" (commission-only) flag from the reconciliation
  // table. Adding uses the default 0.50 GEL/kg rate — editable in the formal card.
  const formalMutation = useMutation({
    mutationFn: async ({ id, name, formal }: { id: string; name?: string | null; formal: boolean }) => {
      if (formal) {
        await configApi.setFormalSalesCustomer({
          customerId: id,
          customerName: name ?? undefined,
          commissionPerKg: 0.5,
        })
      } else {
        await configApi.removeFormalSalesCustomer(canonicalId(id))
      }
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['formal-sales-customers'] })
      queryClient.invalidateQueries({ queryKey: ['audit-dual-ledger'] })
    },
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
  const handleToggleFormal = React.useCallback(
    (id: string, name: string | null | undefined, formal: boolean) =>
      formalMutation.mutate({ id, name, formal }),
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
            Unified dual-ledger dashboard — documented vs physical, per category
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
              <TabsTrigger value="dashboard">Dashboard</TabsTrigger>
              <TabsTrigger value="reconciliation">Reconciliation</TabsTrigger>
              <TabsTrigger value="vat">VAT</TabsTrigger>
              <TabsTrigger value="supplies">Supplies</TabsTrigger>
            </TabsList>

            <TabsContent value="dashboard" className="space-y-4">
              <UnifiedDashboardTab query={dualLedgerQuery} ledgers={data.inventoryLedgers} />
            </TabsContent>

            <TabsContent value="reconciliation" className="space-y-4">
              <ReconciliationSection
                data={data}
                onTogglePaid={handleTogglePaid}
                togglingKey={paidMutation.isPending ? paidMutation.variables?.key : undefined}
                onToggleUnreal={handleToggleUnreal}
                togglingUnrealKey={unrealMutation.isPending ? unrealMutation.variables?.id : undefined}
                formalIds={formalIds}
                onToggleFormal={handleToggleFormal}
                togglingFormalKey={formalMutation.isPending ? formalMutation.variables?.id : undefined}
              />
              <FormalCommissionSection query={dualLedgerQuery} />
              <TargetedExpenseCard data={data} />
              <ExceptionsCard data={data} />
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

// ==================== Unified dashboard (BOR-79) ====================

type DualQuery = {
  data?: DualLedger
  isLoading: boolean
  isError: boolean
  error: unknown
}

/**
 * Mirror of backend TinValidator.canonicalId: digits only, leading zeros
 * stripped (RS.ge drops leading zeros from individual TINs). Non-numeric ids
 * fall back to the trimmed string.
 */
function canonicalId(id: string): string {
  const digits = id.replace(/\D/g, '')
  return digits ? digits.replace(/^0+(?=\d)/, '') : id.trim()
}

// One collapsible split-card per category: Purchases | Sales | On-hand footer.
function UnifiedDashboardTab({ query, ledgers }: { query: DualQuery; ledgers: InventoryLedger[] }) {
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

  // Persist the per-category write-off % server-side; both the dashboard ledger
  // and the dual-ledger cards recompute at the new rate.
  const rateMutation = useMutation({
    mutationFn: (v: { category: string; percent: number }) => configApi.setWriteOffRate(v),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['audit-dashboard'] })
      queryClient.invalidateQueries({ queryKey: ['audit-dual-ledger'] })
    },
  })

  if (query.isError) {
    return (
      <Card>
        <CardContent className="p-6 text-sm text-destructive">
          Failed to load dashboard: {(query.error as Error)?.message}
        </CardContent>
      </Card>
    )
  }
  if (query.isLoading || !query.data) {
    return <Skeleton className="h-64 w-full" />
  }

  const cards = query.data.categoryCards ?? []
  const ledgerByCat = new Map(ledgers.map((l) => [l.parentCategory, l]))

  if (!cards.length) {
    return (
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Unified category dashboard</CardTitle>
          <CardDescription>No product movements found for the selected range.</CardDescription>
        </CardHeader>
      </Card>
    )
  }

  return (
    <div className="space-y-3">
      {cards.map((c) => (
        <CategoryAccordionCard
          key={c.category}
          card={c}
          ledger={ledgerByCat.get(c.category)}
          saving={saveMutation.isPending}
          onSaveField={saveField}
          savingRate={rateMutation.isPending}
          onSaveRate={(percent) => rateMutation.mutate({ category: c.category, percent })}
        />
      ))}
    </div>
  )
}

const CategoryAccordionCard = React.memo(function CategoryAccordionCard({
  card,
  ledger,
  saving,
  onSaveField,
  savingRate,
  onSaveRate,
}: {
  card: UnifiedCategoryCard
  ledger?: InventoryLedger
  saving: boolean
  onSaveField: (category: string, field: keyof CategoryLedgerInput, value: number | null) => void
  savingRate: boolean
  onSaveRate: (percent: number) => void
}) {
  const [open, setOpen] = React.useState(false)
  const [showDaily, setShowDaily] = React.useState(false)
  const label = CATEGORY_LABELS[card.category] ?? card.category

  return (
    <Card>
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex w-full flex-wrap items-center justify-between gap-2 px-4 py-3 text-left"
        aria-expanded={open}
      >
        <span className="flex items-center gap-2 font-medium">
          <ChevronRight className={`h-4 w-4 shrink-0 transition-transform ${open ? 'rotate-90' : ''}`} />
          {label}
        </span>
        <span className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs tabular-nums text-muted-foreground">
          <span>Purchased {formatNumber(card.purchaseDocKg)} kg</span>
          <span>Sold {formatNumber(card.salesDocKg)} kg</span>
          {card.vatDifference !== 0 ? (
            <span className="text-destructive">VAT diff {formatCurrency(card.vatDifference)}</span>
          ) : null}
          <span className={`font-medium ${card.onHandDocKg < 0 ? 'text-destructive' : 'text-foreground'}`}>
            On hand {formatNumber(card.onHandDocKg)} kg
          </span>
        </span>
      </button>
      {open ? (
        <CardContent className="space-y-3 p-4 pt-0">
          <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
            <PurchaseWindow
              card={card}
              saving={saving}
              onSaveField={onSaveField}
              savingRate={savingRate}
              onSaveRate={onSaveRate}
            />
            <SalesWindow card={card} saving={saving} onSaveField={onSaveField} />
          </div>
          <OnHandFooter card={card} />
          {ledger ? (
            <div className="space-y-2">
              <Button variant="ghost" size="sm" onClick={() => setShowDaily((v) => !v)}>
                {showDaily ? 'Hide daily rows' : 'Show daily rows'}
                {ledger.overageDays > 0 ? (
                  <span className="ml-2 inline-flex items-center gap-1 text-destructive">
                    <AlertTriangle className="h-3 w-3" /> {ledger.overageDays} overage day(s)
                  </span>
                ) : null}
              </Button>
              {showDaily ? <DailyRowsTable ledger={ledger} /> : null}
            </div>
          ) : null}
        </CardContent>
      ) : null}
    </Card>
  )
})

function PurchaseWindow({
  card,
  saving,
  onSaveField,
  savingRate,
  onSaveRate,
}: {
  card: UnifiedCategoryCard
  saving: boolean
  onSaveField: (category: string, field: keyof CategoryLedgerInput, value: number | null) => void
  savingRate: boolean
  onSaveRate: (percent: number) => void
}) {
  return (
    <Window title="Purchases" subtitle="Documented (RS.ge) vs physical reality">
      <Row label="Purchase Doc kg">{formatNumber(card.purchaseDocKg)}</Row>
      <Row label="Purchase Doc price (avg)">
        <EditableNumber
          value={card.purchaseDocPrice}
          disabled={saving}
          onSave={(v) => onSaveField(card.category, 'docPurchasePrice', v)}
        />
      </Row>
      <Row label="Write-off %">
        <PercentInput value={card.writeOffPercent} disabled={savingRate} onSave={onSaveRate} />
      </Row>
      <Row label="Net doc purchase kg">{formatNumber(card.netDocPurchaseKg)}</Row>
      <Row label="Net kg price doc" emphasize>
        {formatCurrency(card.netDocKgPrice)}
      </Row>
      <Row label="Purchase Real kg">
        <EditableNumber
          value={card.purchaseRealKg}
          disabled={saving}
          onSave={(v) => onSaveField(card.category, 'realPurchaseKg', v)}
        />
      </Row>
      <Row label="Purchase Real price">
        <EditableNumber
          value={card.purchaseRealPrice}
          disabled={saving}
          onSave={(v) => onSaveField(card.category, 'realPurchasePrice', v)}
        />
      </Row>
      <Row label="Debt Doc">{formatCurrency(card.debtDoc)}</Row>
      <Row label="Debt Real">{formatCurrency(card.debtReal)}</Row>
      <Row label="VAT difference" emphasize>
        <span className={card.vatDifference !== 0 ? 'text-destructive' : 'text-muted-foreground'}>
          {formatCurrency(card.vatDifference)}
        </span>
      </Row>
    </Window>
  )
}

function SalesWindow({
  card,
  saving,
  onSaveField,
}: {
  card: UnifiedCategoryCard
  saving: boolean
  onSaveField: (category: string, field: keyof CategoryLedgerInput, value: number | null) => void
}) {
  return (
    <Window title="Sales" subtitle="Real excludes unreal/formal customers; commissions segregated">
      <Row label="Sales Doc kg">{formatNumber(card.salesDocKg)}</Row>
      <Row label="Sales Doc price (avg)">
        <EditableNumber
          value={card.salesDocPrice}
          disabled={saving}
          onSave={(v) => onSaveField(card.category, 'docSalePrice', v)}
        />
      </Row>
      <Row label="Sales Doc total">{formatCurrency(card.salesDocTotal)}</Row>
      <Row label="Sales Real kg">
        <span>
          {formatNumber(card.salesRealKg)}
          <span className="ml-2 text-xs text-muted-foreground">
            (−{formatNumber(card.unrealSalesKg)} unreal · −{formatNumber(card.formalSalesKg)} formal)
          </span>
        </span>
      </Row>
      <Row label="Sales Real price">
        <EditableNumber
          value={card.salesRealPrice}
          disabled={saving}
          onSave={(v) => onSaveField(card.category, 'realSalePrice', v)}
        />
      </Row>
      <Row label="Real product sales" emphasize>
        {formatCurrency(card.realProductSales)}
      </Row>
      <Row label="Formal commission" emphasize>
        <span className="text-emerald-600">{formatCurrency(card.formalCommission)}</span>
      </Row>
      <Row label="Sales Real total" emphasize>
        {formatCurrency(card.salesRealTotal)}
      </Row>
    </Window>
  )
}

function OnHandFooter({ card }: { card: UnifiedCategoryCard }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-2 rounded-md border bg-muted/30 px-4 py-3">
      <div className="text-sm font-medium">On-Hand Inventory (doc)</div>
      <div className="text-xs tabular-nums text-muted-foreground">
        {formatNumber(card.startingInventoryKg)} starting + {formatNumber(card.netDocPurchaseKg)} net
        purchases ({formatNumber(card.writeOffPercent)}% write-off) − {formatNumber(card.salesDocKg)} sold
      </div>
      <div
        className={`text-lg font-semibold tabular-nums ${card.onHandDocKg < 0 ? 'text-destructive' : ''}`}
      >
        {formatNumber(card.onHandDocKg)} kg
      </div>
    </div>
  )
}

// Bordered label/value list used for the Purchases and Sales windows.
function Window({
  title,
  subtitle,
  children,
}: {
  title: string
  subtitle?: string
  children: React.ReactNode
}) {
  return (
    <div className="rounded-md border">
      <div className="border-b bg-muted/50 px-3 py-2">
        <div className="text-sm font-medium">{title}</div>
        {subtitle ? <div className="text-xs text-muted-foreground">{subtitle}</div> : null}
      </div>
      <dl className="divide-y text-sm">{children}</dl>
    </div>
  )
}

function Row({
  label,
  emphasize,
  children,
}: {
  label: string
  emphasize?: boolean
  children: React.ReactNode
}) {
  return (
    <div className="flex items-center justify-between gap-2 px-3 py-1.5">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className={`text-right tabular-nums ${emphasize ? 'font-medium' : ''}`}>{children}</dd>
    </div>
  )
}

// Editable write-off percent (0–100), committed on blur / Enter.
function PercentInput({
  value,
  disabled,
  onSave,
}: {
  value: number
  disabled?: boolean
  onSave: (percent: number) => void
}) {
  const [draft, setDraft] = React.useState(String(value))

  React.useEffect(() => {
    setDraft(String(value))
  }, [value])

  const commit = () => {
    const pct = Number(draft)
    if (!Number.isFinite(pct) || pct < 0 || pct > 100) {
      setDraft(String(value)) // revert invalid input
      return
    }
    if (pct !== value) onSave(pct)
  }

  return (
    <span className="inline-flex items-center gap-0.5">
      <input
        type="number"
        min={0}
        max={100}
        step="0.1"
        value={draft}
        disabled={disabled}
        onChange={(e) => setDraft(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => {
          if (e.key === 'Enter') (e.target as HTMLInputElement).blur()
        }}
        className="h-7 w-16 rounded border border-input bg-background px-1 text-right text-xs tabular-nums"
        aria-label="Write-off percent of purchased"
      />
      <span className="text-xs text-muted-foreground">%</span>
    </span>
  )
}

// Formal-commission management, shown on the Reconciliation tab next to the
// Formal checkboxes that add/remove customers.
function FormalCommissionSection({ query }: { query: DualQuery }) {
  if (query.isError || query.isLoading || !query.data) {
    return null
  }
  return (
    <FormalCommissionCard
      commissions={query.data.formalCommissions}
      total={query.data.totalFormalCommission}
    />
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
    queryClient.invalidateQueries({ queryKey: ['formal-sales-customers'] })
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

// ==================== Daily ledger rows ====================

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

// Daily write-off ledger rows, expandable inside each category accordion.
// Memoized (BOR-75): tables with hundreds of rows must only re-render when
// their ledger changes.
const DailyRowsTable = React.memo(function DailyRowsTable({ ledger }: { ledger: InventoryLedger }) {
  return (
    <div className="overflow-x-auto rounded-md border">
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
  )
})

// ==================== Reconciliation ====================

// Memoized (BOR-75): up to 200 rows; only re-renders when data/toggling change.
const ReconciliationSection = React.memo(function ReconciliationSection({
  data,
  onTogglePaid,
  togglingKey,
  onToggleUnreal,
  togglingUnrealKey,
  formalIds,
  onToggleFormal,
  togglingFormalKey,
}: {
  data: AuditDashboard
  onTogglePaid: (key: string, markedPaid: boolean) => void
  togglingKey?: string
  onToggleUnreal: (id: string, unreal: boolean) => void
  togglingUnrealKey?: string
  formalIds: Set<string>
  onToggleFormal: (id: string, name: string | null | undefined, formal: boolean) => void
  togglingFormalKey?: string
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
          aren't real partners (their sales leave Real Totals and their debt moves to Exception). Tick
          "Formal" for documentation-only customers who earn a per-kg commission instead of paper AR. Toggle
          "Paid" to override API status.
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
                <Th>Formal</Th>
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
                  <Td>
                    <input
                      type="checkbox"
                      className="h-4 w-4 cursor-pointer accent-emerald-600"
                      checked={formalIds.has(canonicalId(r.customerId))}
                      disabled={togglingFormalKey === r.customerId}
                      onChange={() =>
                        onToggleFormal(r.customerId, r.customerName, !formalIds.has(canonicalId(r.customerId)))
                      }
                      aria-label="Mark customer formal"
                      title="Formal sales customer: paper AR ignored, earns per-kg commission (default 0.50)"
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
