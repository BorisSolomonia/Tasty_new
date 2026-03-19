import * as React from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { startOfMonth } from 'date-fns'
import { productSalesApi, configApi } from '@/lib/api-client'
import { useCachedQuery } from '@/lib/use-cached-query'
import { formatDateISO } from '@/lib/utils'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import type { CustomerEntry, ProductSales } from '@/types/domain'

// ─── Default customers ────────────────────────────────────────────────────────

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

// ─── TIN normalization ────────────────────────────────────────────────────────
// RS.ge may return individual TINs as numeric (stripping leading zeros).
// e.g. "01008057492" from RS.ge becomes "1008057492".
// We normalize both sides before comparing so IDs always match.

function normId(id: string): string {
  if (!id) return ''
  const digits = id.replace(/\D/g, '')
  // Strip leading zeros but keep at least one digit
  return digits.replace(/^0+(\d)/, '$1')
}

// ─── LocalStorage helpers ─────────────────────────────────────────────────────

function loadCustomersFromLocalStorage(): CustomerState[] | null {
  try {
    const saved = localStorage.getItem(LS_KEY)
    if (saved) return JSON.parse(saved) as CustomerState[]
  } catch {
    // ignore
  }
  return null
}

function saveCustomersToLocalStorage(list: CustomerState[]) {
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

function SortIcon({ field, current, dir }: { field: SortField; current: SortField; dir: 'asc' | 'desc' }) {
  if (field !== current) return <span className="ml-1 text-muted-foreground opacity-40">↕</span>
  return <span className="ml-1">{dir === 'asc' ? '↑' : '↓'}</span>
}

// ─── Page component ───────────────────────────────────────────────────────────

export function ProductSalesPage() {
  const queryClient = useQueryClient()
  const [startDate, setStartDate] = React.useState<string>(formatDateISO(startOfMonth(new Date())))
  const [endDate, setEndDate] = React.useState<string>(formatDateISO(new Date()))
  const [fetchTrigger, setFetchTrigger] = React.useState<number | null>(null)

  // Load customers: Firebase (source of truth) → localStorage (fast cache) → defaults
  const customersQuery = useCachedQuery<CustomerState[]>({
    queryKey: ['config', 'product-sales-customers'],
    queryFn: async () => {
      const data = await configApi.getProductSalesCustomers()
      if (data && data.length > 0) return data
      // Firebase empty → fall back to localStorage or defaults
      return loadCustomersFromLocalStorage() ?? DEFAULT_CUSTOMERS.map((c) => ({ ...c, visible: true }))
    },
    cacheKey: LS_KEY,
    staleTime: 1000 * 60 * 5,
  })

  const customers: CustomerState[] = customersQuery.data ?? loadCustomersFromLocalStorage() ?? DEFAULT_CUSTOMERS.map((c) => ({ ...c, visible: true }))

  // Fire-and-forget mutation to persist to Firebase
  const saveMutation = useMutation({
    mutationFn: (list: CustomerState[]) => configApi.saveProductSalesCustomers(list),
  })
  const [sortField, setSortField] = React.useState<SortField>('name')
  const [sortDir, setSortDir] = React.useState<'asc' | 'desc'>('asc')
  const [showAddInput, setShowAddInput] = React.useState(false)
  const [newCustomerInput, setNewCustomerInput] = React.useState('')
  const [dropdownOpen, setDropdownOpen] = React.useState(false)
  const addInputRef = React.useRef<HTMLInputElement>(null)
  const dropdownRef = React.useRef<HTMLDivElement>(null)

  // Fetch the full customer list for autocomplete (lazy — only when add panel is open)
  const customersListQuery = useQuery({
    queryKey: ['config', 'customers'],
    queryFn: () => configApi.getCustomers(),
    enabled: showAddInput,
    staleTime: 1000 * 60 * 30,
    gcTime: 1000 * 60 * 60,
    retry: 1,
  })

  const query = useQuery({
    queryKey: ['product-sales', startDate, endDate, fetchTrigger],
    queryFn: () => productSalesApi.getProductSales({ startDate, endDate }),
    enabled: fetchTrigger !== null,
    staleTime: 5 * 60 * 1000,
    retry: 1,
  })

  // Build the full autocomplete source: config customers + any customers already returned by the API
  const allKnownCustomers = React.useMemo(() => {
    const map = new Map<string, string>() // normId → display name
    // Add from config customers
    const cfgList = (customersListQuery.data ?? []) as Array<{ identification: string; customerName: string }>
    cfgList.forEach(c => {
      if (c.identification) map.set(normId(c.identification), `(${c.identification}) ${c.customerName}`)
    })
    // Add from product-sales API results (already fetched)
    const apiData = (query.data ?? []) as ProductSales[]
    apiData.forEach(r => {
      if (r.customerId && !map.has(normId(r.customerId))) {
        map.set(normId(r.customerId), `(${r.customerId}) ${r.customerName}`)
      }
    })
    // Always include DEFAULT_CUSTOMERS as fallback
    DEFAULT_CUSTOMERS.forEach(c => {
      if (!map.has(normId(c.id))) map.set(normId(c.id), c.name)
    })

    return Array.from(map.entries()).map(([nid, name]) => ({ nid, name }))
  }, [customersListQuery.data, query.data])

  // Filtered autocomplete options
  const autocompleteOptions = React.useMemo(() => {
    const term = newCustomerInput.trim().toLowerCase()
    if (!term) return allKnownCustomers.slice(0, 15)
    return allKnownCustomers
      .filter(opt =>
        opt.name.toLowerCase().includes(term) ||
        opt.nid.includes(term)
      )
      .slice(0, 15)
  }, [allKnownCustomers, newCustomerInput])

  // Close dropdown when clicking outside
  React.useEffect(() => {
    function handleClickOutside(e: MouseEvent) {
      if (
        dropdownRef.current && !dropdownRef.current.contains(e.target as Node) &&
        addInputRef.current && !addInputRef.current.contains(e.target as Node)
      ) {
        setDropdownOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [])

  const handleFetch = () => {
    setFetchTrigger((k) => (k === null ? 1 : k + 1))
  }

  const updateCustomers = (list: CustomerState[]) => {
    saveCustomersToLocalStorage(list)
    queryClient.setQueryData(['config', 'product-sales-customers'], list)
    saveMutation.mutate(list)
  }

  const toggleVisible = (id: string) => {
    updateCustomers(customers.map((c) => (c.id === id ? { ...c, visible: !c.visible } : c)))
  }

  const removeCustomer = (id: string) => {
    updateCustomers(customers.filter((c) => c.id !== id))
  }

  const addCustomerFromOption = (option: { nid: string; name: string }) => {
    // Check if already in list (by normalized ID)
    if (customers.some(c => normId(c.id) === option.nid)) {
      setNewCustomerInput('')
      setDropdownOpen(false)
      setShowAddInput(false)
      return
    }
    // Extract raw id from the name string
    const match = option.name.match(/\((\d{9,11})\)/)
    const rawId = match ? match[1] : option.nid
    updateCustomers([...customers, { id: rawId, name: option.name, visible: true }])
    setNewCustomerInput('')
    setDropdownOpen(false)
    setShowAddInput(false)
  }

  const handleAddManual = () => {
    const trimmed = newCustomerInput.trim()
    if (!trimmed) return
    // Check if it matches an autocomplete option exactly
    const match = autocompleteOptions.find(
      o => o.name.toLowerCase() === trimmed.toLowerCase() || o.nid === normId(trimmed)
    )
    if (match) {
      addCustomerFromOption(match)
      return
    }
    // Free-text add: extract TIN from format "(TIN) Name" or just use as-is
    const tinMatch = trimmed.match(/\((\d{9,11})\)/)
    const id = tinMatch ? tinMatch[1] : trimmed
    if (customers.some(c => normId(c.id) === normId(id))) {
      setNewCustomerInput('')
      setDropdownOpen(false)
      setShowAddInput(false)
      return
    }
    updateCustomers([...customers, { id, name: trimmed, visible: true }])
    setNewCustomerInput('')
    setDropdownOpen(false)
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

  // Build a normalized set of visible customer IDs for matching
  // Uses normId() so "01008057492" and "1008057492" both resolve to the same key
  const visibleNormIds = React.useMemo(
    () => new Set(customers.filter((c) => c.visible).map((c) => normId(c.id))),
    [customers]
  )

  const sortedRows = React.useMemo(() => {
    if (!query.data) return []
    // Match by normalized ID — fixes leading-zero mismatches from RS.ge
    const filtered = (query.data as ProductSales[]).filter(
      (row) => visibleNormIds.has(normId(row.customerId))
    )
    return [...filtered].sort((a, b) => {
      let cmp = 0
      if (sortField === 'name') cmp = a.customerName.localeCompare(b.customerName, 'ka')
      else if (sortField === 'beef') cmp = (a.beefKg ?? 0) - (b.beefKg ?? 0)
      else if (sortField === 'pork') cmp = (a.porkKg ?? 0) - (b.porkKg ?? 0)
      else if (sortField === 'total') cmp = (a.totalKg ?? 0) - (b.totalKg ?? 0)
      return sortDir === 'asc' ? cmp : -cmp
    })
  }, [query.data, visibleNormIds, sortField, sortDir])

  const totals = React.useMemo(
    () => ({
      beef: sortedRows.reduce((s, r) => s + (r.beefKg ?? 0), 0),
      pork: sortedRows.reduce((s, r) => s + (r.porkKg ?? 0), 0),
      total: sortedRows.reduce((s, r) => s + (r.totalKg ?? 0), 0),
    }),
    [sortedRows]
  )

  const visibleCount = customers.filter(c => c.visible).length

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
          <span className="text-sm font-medium">
            მომხმარებლები ({customers.length} სულ, {visibleCount} მონიშნული)
          </span>
          <Button size="sm" variant="outline" onClick={() => {
            setShowAddInput((v) => !v)
            setNewCustomerInput('')
            setDropdownOpen(false)
          }}>
            {showAddInput ? 'გაუქმება' : '+ დამატება'}
          </Button>
        </div>

        {showAddInput && (
          <div className="mb-3 relative">
            <div className="flex gap-2">
              <div className="relative flex-1">
                <input
                  ref={addInputRef}
                  type="text"
                  placeholder="სახელი ან TIN-ის ჩაწერა..."
                  value={newCustomerInput}
                  onChange={(e) => {
                    setNewCustomerInput(e.target.value)
                    setDropdownOpen(true)
                  }}
                  onFocus={() => setDropdownOpen(true)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') handleAddManual()
                    if (e.key === 'Escape') { setDropdownOpen(false); setShowAddInput(false) }
                  }}
                  className="w-full rounded-md border bg-background px-2.5 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
                  autoFocus
                />

                {/* Dropdown */}
                {dropdownOpen && autocompleteOptions.length > 0 && (
                  <div
                    ref={dropdownRef}
                    className="absolute left-0 right-0 top-full z-50 mt-1 max-h-60 overflow-y-auto rounded-md border bg-background shadow-lg"
                  >
                    {customersListQuery.isLoading && (
                      <div className="px-3 py-2 text-xs text-muted-foreground">იტვირთება...</div>
                    )}
                    {autocompleteOptions.map((opt) => {
                      const alreadyAdded = customers.some(c => normId(c.id) === opt.nid)
                      return (
                        <button
                          key={opt.nid}
                          type="button"
                          disabled={alreadyAdded}
                          onMouseDown={(e) => {
                            e.preventDefault() // prevent input blur
                            if (!alreadyAdded) addCustomerFromOption(opt)
                          }}
                          className={`w-full px-3 py-2 text-left text-sm hover:bg-muted/60 flex items-center justify-between gap-2 ${
                            alreadyAdded ? 'opacity-40 cursor-not-allowed' : 'cursor-pointer'
                          }`}
                        >
                          <span className="truncate">{opt.name}</span>
                          {alreadyAdded && (
                            <span className="shrink-0 text-xs text-muted-foreground">✓ არის</span>
                          )}
                        </button>
                      )
                    })}
                    {autocompleteOptions.length === 0 && !customersListQuery.isLoading && (
                      <div className="px-3 py-2 text-xs text-muted-foreground">
                        შედეგი არ მოიძებნა — Enter-ით ხელით დამატება
                      </div>
                    )}
                  </div>
                )}
              </div>
              <Button size="sm" onClick={handleAddManual}>დამატება</Button>
            </div>
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
                    <td className="px-3 py-2 text-xs uppercase tracking-wide text-muted-foreground">
                      სულ ({sortedRows.length} მომხმარებელი)
                    </td>
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
