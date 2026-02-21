import * as React from 'react'
import { useQuery } from '@tanstack/react-query'
import { startOfMonth } from 'date-fns'
import { productSalesApi } from '@/lib/api-client'
import { formatDateISO } from '@/lib/utils'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import type { CustomerEntry, ProductSales } from '@/types/domain'

// ─── Default customers (from starting_customers.txt) ─────────────────────────

const DEFAULT_CUSTOMERS: CustomerEntry[] = [
  { id: '01008057492', name: '(01008057492-დღგ) ნინო მუშკუდიანი' },
  { id: '204900358', name: '(204900358-დღგ) შპს ვარაზის ხევი 95' },
  { id: '402297787', name: '(402297787-დღგ) შპს ასი-100' },
  { id: '404401036', name: '(404401036-დღგ) შპს MSR' },
  { id: '404869585', name: '(404869585-დღგ) შპს MASURO' },
  { id: '405129999', name: '(405129999-დღგ) შპს ბუდვაიზერი - სამსონი' },
  { id: '405226973', name: '(405226973-დღგ) შპს  ნარნია' },
  { id: '405270987', name: '(405270987-დღგ) შპს ნეკაფე' },
  { id: '405404771', name: '(405404771-დღგ) შპს  ბრაუჰაუს ტიფლისი' },
  { id: '405587949', name: '(405587949-დღგ) შპს აკიდო 2023' },
  { id: '405604190', name: '(405604190-დღგ) შპს ბუკა202' },
  { id: '431441843', name: '(431441843-დღგ) შპს  მესი 2022' },
  { id: '406256171', name: '(406256171-დღგ) შპს  ნოვა იმპორტი' },
  { id: '404699073', name: '(404699073-დღგ) შპს სენე გრუპი' },
  { id: '01006019107', name: '(01006019107-დღგ) გურანდა ლაღაძე' },
  { id: '405488431', name: '(405488431-დღგ) შპს ათუ' },
  { id: '405108226', name: '(405108226-დღგ) შპს სოთო' },
  { id: '400362139', name: '(400362139-დღგ) შპს დერ ფშორ' },
  { id: '431443547', name: '(431443547-დღგ) შპს რესტორატორი 2025' },
  { id: '01008048153', name: '(01008048153-დღგ) ანთიმოზ ცქიტიშვილი' },
  { id: '404555806', name: '(404555806-დღგ) შპს ფორკ' },
  { id: '404622164', name: '(404622164-დღგ) შპს ვერაზე' },
  { id: '402357418', name: '(402357418-დღგ) შპს ასი-200' },
  { id: '402342130', name: '(402342130-დღგ) შპს ასიათასი' },
  { id: '406404314', name: '(406404314-დღგ) შპს თოში' },
  { id: '01717066840', name: '(01717066840) ნანა კალანდაძე' },
  { id: '405790041', name: '(405790041-დღგ) შპს  კუდიგორა' },
]

const LS_KEY = 'product_sales_customers'

type CustomerState = CustomerEntry & { visible: boolean }
type SortField = 'name' | 'beef' | 'pork' | 'total'

// ─── LocalStorage helpers ─────────────────────────────────────────────────────

function loadCustomers(): CustomerState[] {
  try {
    const saved = localStorage.getItem(LS_KEY)
    if (saved) return JSON.parse(saved) as CustomerState[]
  } catch {
    // ignore
  }
  return DEFAULT_CUSTOMERS.map((c) => ({ ...c, visible: true }))
}

function saveCustomers(list: CustomerState[]) {
  localStorage.setItem(LS_KEY, JSON.stringify(list))
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function formatKg(val: number | undefined | null): string {
  if (val == null) return '0.0'
  return new Intl.NumberFormat('ka-GE', {
    minimumFractionDigits: 1,
    maximumFractionDigits: 1,
  }).format(val)
}

function extractTin(input: string): string {
  const match = input.match(/\((\d{9,11})(?:-[^)]+)?\)/)
  return match ? match[1] : input.trim()
}

function SortIcon({ field, current, dir }: { field: SortField; current: SortField; dir: 'asc' | 'desc' }) {
  if (field !== current) return <span className="ml-1 text-muted-foreground opacity-40">↕</span>
  return <span className="ml-1">{dir === 'asc' ? '↑' : '↓'}</span>
}

// ─── Page component ───────────────────────────────────────────────────────────

export function ProductSalesPage() {
  const [startDate, setStartDate] = React.useState<string>(formatDateISO(startOfMonth(new Date())))
  const [endDate, setEndDate] = React.useState<string>(formatDateISO(new Date()))
  const [fetchTrigger, setFetchTrigger] = React.useState<number | null>(null)
  const [customers, setCustomers] = React.useState<CustomerState[]>(loadCustomers)
  const [sortField, setSortField] = React.useState<SortField>('name')
  const [sortDir, setSortDir] = React.useState<'asc' | 'desc'>('asc')
  const [showAddInput, setShowAddInput] = React.useState(false)
  const [newCustomerInput, setNewCustomerInput] = React.useState('')

  const query = useQuery({
    queryKey: ['product-sales', startDate, endDate, fetchTrigger],
    queryFn: () => productSalesApi.getProductSales({ startDate, endDate }),
    enabled: fetchTrigger !== null,
    staleTime: 5 * 60 * 1000,
    retry: 1,
  })

  const handleFetch = () => {
    setFetchTrigger((k) => (k === null ? 1 : k + 1))
  }

  const updateCustomers = (list: CustomerState[]) => {
    saveCustomers(list)
    setCustomers(list)
  }

  const toggleVisible = (id: string) => {
    updateCustomers(customers.map((c) => (c.id === id ? { ...c, visible: !c.visible } : c)))
  }

  const removeCustomer = (id: string) => {
    updateCustomers(customers.filter((c) => c.id !== id))
  }

  const handleAddCustomer = () => {
    const trimmed = newCustomerInput.trim()
    if (!trimmed) return
    const id = extractTin(trimmed)
    if (customers.some((c) => c.id === id)) {
      setNewCustomerInput('')
      setShowAddInput(false)
      return
    }
    updateCustomers([...customers, { id, name: trimmed, visible: true }])
    setNewCustomerInput('')
    setShowAddInput(false)
  }

  const toggleSort = (field: SortField) => {
    if (sortField === field) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'))
    } else {
      setSortField(field)
      setSortDir('asc')
    }
  }

  const visibleIds = React.useMemo(
    () => new Set(customers.filter((c) => c.visible).map((c) => c.id)),
    [customers]
  )

  const sortedRows = React.useMemo(() => {
    if (!query.data) return []
    const filtered = query.data.filter((row: ProductSales) => visibleIds.has(row.customerId))
    return [...filtered].sort((a, b) => {
      let cmp = 0
      if (sortField === 'name') cmp = a.customerName.localeCompare(b.customerName, 'ka')
      else if (sortField === 'beef') cmp = (a.beefKg ?? 0) - (b.beefKg ?? 0)
      else if (sortField === 'pork') cmp = (a.porkKg ?? 0) - (b.porkKg ?? 0)
      else if (sortField === 'total') cmp = (a.totalKg ?? 0) - (b.totalKg ?? 0)
      return sortDir === 'asc' ? cmp : -cmp
    })
  }, [query.data, visibleIds, sortField, sortDir])

  const totals = React.useMemo(
    () => ({
      beef: sortedRows.reduce((s, r) => s + (r.beefKg ?? 0), 0),
      pork: sortedRows.reduce((s, r) => s + (r.porkKg ?? 0), 0),
      total: sortedRows.reduce((s, r) => s + (r.totalKg ?? 0), 0),
    }),
    [sortedRows]
  )

  return (
    <div className="space-y-4">
      {/* Header */}
      <div>
        <h1 className="text-xl font-semibold tracking-tight md:text-2xl">გაყიდვები კატეგორიებით</h1>
        <p className="text-sm text-muted-foreground">საქონელი და ღორის ხორცი კგ-ებში მომხმარებლებზე</p>
      </div>

      {/* Date Range + Fetch */}
      <Card className="p-3 md:p-4">
        <div className="flex flex-wrap items-end gap-3">
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">დასაწყისი</label>
            <input
              type="date"
              value={startDate}
              onChange={(e) => setStartDate(e.target.value)}
              className="rounded-md border bg-background px-2.5 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
            />
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">დასასრული</label>
            <input
              type="date"
              value={endDate}
              onChange={(e) => setEndDate(e.target.value)}
              className="rounded-md border bg-background px-2.5 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
            />
          </div>
          <Button onClick={handleFetch} disabled={query.isFetching} className="shrink-0">
            {query.isFetching ? 'იტვირთება…' : 'მოძებნა'}
          </Button>
        </div>
      </Card>

      {/* Customer Panel */}
      <Card className="p-3 md:p-4">
        <div className="mb-2 flex items-center justify-between gap-2">
          <span className="text-sm font-medium">მომხმარებლები ({customers.length})</span>
          <Button size="sm" variant="outline" onClick={() => setShowAddInput((v) => !v)}>
            {showAddInput ? 'გაუქმება' : '+ დამატება'}
          </Button>
        </div>

        {showAddInput && (
          <div className="mb-3 flex gap-2">
            <input
              type="text"
              placeholder="TIN ან სახელი (მაგ: (123456789) სახელი)"
              value={newCustomerInput}
              onChange={(e) => setNewCustomerInput(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleAddCustomer()}
              className="flex-1 rounded-md border bg-background px-2.5 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
              autoFocus
            />
            <Button size="sm" onClick={handleAddCustomer}>დამატება</Button>
          </div>
        )}

        <div className="max-h-52 overflow-y-auto space-y-0.5">
          {customers.map((c) => (
            <div key={c.id} className="flex items-center gap-2 rounded px-1 py-0.5 hover:bg-muted/40">
              <input
                type="checkbox"
                id={`cust-${c.id}`}
                checked={c.visible}
                onChange={() => toggleVisible(c.id)}
                className="h-4 w-4 rounded border-border accent-primary"
              />
              <label htmlFor={`cust-${c.id}`} className="flex-1 cursor-pointer truncate text-xs">
                {c.name}
              </label>
              <button
                onClick={() => removeCustomer(c.id)}
                className="shrink-0 rounded p-0.5 text-muted-foreground hover:bg-destructive/10 hover:text-destructive"
                title="წაშლა"
              >
                ✕
              </button>
            </div>
          ))}
          {customers.length === 0 && (
            <p className="py-2 text-center text-xs text-muted-foreground">მომხმარებელი არ არის</p>
          )}
        </div>
      </Card>

      {/* Results */}
      {query.isError && (
        <Card className="border-destructive p-4">
          <div className="text-sm font-medium text-destructive">შეცდომა მონაცემების ჩატვირთვისას</div>
          <div className="mt-1 text-xs text-muted-foreground">
            {query.error instanceof Error ? query.error.message : 'Unknown error'}
          </div>
          <Button size="sm" variant="outline" className="mt-3" onClick={handleFetch}>
            თავიდან ცდა
          </Button>
        </Card>
      )}

      {fetchTrigger === null && !query.isError && (
        <Card className="p-8 text-center text-sm text-muted-foreground">
          აირჩიეთ თარიღები და დააჭირეთ „მოძებნა" ღილაკს
        </Card>
      )}

      {(query.isFetching || (!query.isError && fetchTrigger !== null)) && (
        <Card className="overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <TableHeader
                sortField={sortField}
                sortDir={sortDir}
                onSort={toggleSort}
              />
              <tbody>
                {query.isFetching
                  ? Array.from({ length: 8 }).map((_, i) => (
                      <tr key={i} className="border-b">
                        <td className="px-3 py-2 text-muted-foreground">{i + 1}</td>
                        <td className="px-3 py-2"><Skeleton className="h-4 w-40" /></td>
                        <td className="px-3 py-2"><Skeleton className="h-4 w-16" /></td>
                        <td className="px-3 py-2"><Skeleton className="h-4 w-16" /></td>
                        <td className="px-3 py-2"><Skeleton className="h-4 w-16" /></td>
                      </tr>
                    ))
                  : sortedRows.map((row, i) => (
                      <tr key={row.customerId} className="border-b last:border-0 hover:bg-muted/30">
                        <td className="px-3 py-2 text-right text-muted-foreground">{i + 1}</td>
                        <td className="px-3 py-2 max-w-[200px] truncate font-medium" title={row.customerName}>
                          {row.customerName}
                        </td>
                        <td className="px-3 py-2 text-right text-amber-600 dark:text-amber-400">
                          {formatKg(row.beefKg)}
                        </td>
                        <td className="px-3 py-2 text-right text-rose-600 dark:text-rose-400">
                          {formatKg(row.porkKg)}
                        </td>
                        <td className="px-3 py-2 text-right font-medium">{formatKg(row.totalKg)}</td>
                      </tr>
                    ))}

                {/* Totals row */}
                {!query.isFetching && sortedRows.length > 0 && (
                  <tr className="border-t-2 bg-muted/30 font-semibold">
                    <td className="px-3 py-2" />
                    <td className="px-3 py-2 text-xs uppercase tracking-wide text-muted-foreground">სულ</td>
                    <td className="px-3 py-2 text-right text-amber-600 dark:text-amber-400">{formatKg(totals.beef)}</td>
                    <td className="px-3 py-2 text-right text-rose-600 dark:text-rose-400">{formatKg(totals.pork)}</td>
                    <td className="px-3 py-2 text-right">{formatKg(totals.total)}</td>
                  </tr>
                )}

                {!query.isFetching && query.data && sortedRows.length === 0 && (
                  <tr>
                    <td colSpan={5} className="px-3 py-8 text-center text-sm text-muted-foreground">
                      მონაცემები ვერ მოიძებნა არჩეული მომხმარებლებისთვის
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </Card>
      )}
    </div>
  )
}

// ─── TableHeader sub-component ────────────────────────────────────────────────

function TableHeader({
  sortField,
  sortDir,
  onSort,
}: {
  sortField: SortField
  sortDir: 'asc' | 'desc'
  onSort: (f: SortField) => void
}) {
  const th = (label: React.ReactNode, field: SortField, align = 'text-right') => (
    <th
      className={`cursor-pointer select-none whitespace-nowrap px-3 py-2 text-xs font-medium uppercase tracking-wide text-muted-foreground hover:text-foreground ${align}`}
      onClick={() => onSort(field)}
    >
      {label}
      <SortIcon field={field} current={sortField} dir={sortDir} />
    </th>
  )

  return (
    <thead className="border-b bg-muted/40">
      <tr>
        <th className="w-10 px-3 py-2 text-right text-xs font-medium uppercase tracking-wide text-muted-foreground">#</th>
        {th('მომხმარებელი', 'name', 'text-left')}
        {th('🐄 საქონელი (კგ)', 'beef')}
        {th('🐷 ღორი (კგ)', 'pork')}
        {th('სულ (კგ)', 'total')}
      </tr>
    </thead>
  )
}
