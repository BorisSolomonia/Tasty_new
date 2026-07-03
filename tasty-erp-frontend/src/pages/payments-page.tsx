import * as React from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { addDays } from 'date-fns'
import { paymentsApi, waybillsApi, configApi, debtsApi, ApiError } from '@/lib/api-client'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { Button } from '@/components/ui/button'
import { formatCurrency, formatDateISO, canonicalId } from '@/lib/utils'
import type { CustomerDebt, Waybill, Payment } from '@/types/domain'

// Client-side detail row: authoritative server debt + matched transaction lists
// (lists are informational for the drill-down only — never used to compute debt).
type PaymentDetail = {
  date: string
  payment: number
  source?: string
  uniqueCode?: string
  paymentId?: string
  description?: string
}
type DebtRow = CustomerDebt & { waybills: Waybill[]; payments: PaymentDetail[] }
type SortKey = 'currentDebt' | 'totalPayments' | 'totalSales' | 'startingDebt' | 'customerId' | 'customerName'

function getApiErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    const data = error.data as unknown
    if (data && typeof data === 'object') {
      const maybeMessage = (data as { message?: unknown }).message
      if (typeof maybeMessage === 'string' && maybeMessage.trim()) return maybeMessage
      const maybeError = (data as { error?: unknown }).error
      if (typeof maybeError === 'string' && maybeError.trim()) return maybeError
    }
    return `${error.status} ${error.statusText}`
  }
  if (error instanceof Error) return error.message
  return 'Unknown error'
}

const PAYMENT_CUTOFF_DATE = '2025-04-29'

export function PaymentsPage() {
  const queryClient = useQueryClient()
  const fileInputRef = React.useRef<HTMLInputElement>(null)
  const manualFileInputRef = React.useRef<HTMLInputElement>(null)
  const [selectedBank, setSelectedBank] = React.useState<'tbc' | 'bog'>('tbc')
  const [uploadStatus, setUploadStatus] = React.useState<string>('')
  const [manualUploadStatus, setManualUploadStatus] = React.useState<string>('')
  const [searchTerm, setSearchTerm] = React.useState('')
  const [sortBy, setSortBy] = React.useState<SortKey>('currentDebt')
  const [sortOrder, setSortOrder] = React.useState<'asc' | 'desc'>('desc')
  const [expandedCustomers, setExpandedCustomers] = React.useState<Set<string>>(new Set())
  const [currentPage, setCurrentPage] = React.useState(1)
  const itemsPerPage = 50

  const paymentWindowStart = formatDateISO(addDays(new Date(PAYMENT_CUTOFF_DATE), 1))

  // AUTHORITATIVE debt — single server source of truth. Every device that reads
  // this within the server's TTL window gets the identical snapshot, so the
  // numbers no longer diverge across devices. The frontend only DISPLAYS these.
  const debtsQuery = useQuery({
    queryKey: ['debts'],
    queryFn: () => debtsApi.getOverview(),
    staleTime: 1000 * 60,
    gcTime: 1000 * 60 * 5,
    retry: 1,
  })

  // Sales waybills + payments are fetched ONLY to populate the per-customer
  // drill-down detail (never to compute debt). Matched to server rows by canonical id.
  const waybillsQuery = useQuery({
    queryKey: ['waybills', 'afterCutoff'],
    queryFn: () => waybillsApi.getAll({ afterCutoffOnly: true, type: 'SALE' }),
    staleTime: 1000 * 60 * 30,
    gcTime: 1000 * 60 * 60,
    retry: 1,
  })

  const paymentsQuery = useQuery({
    queryKey: ['payments', 'afterCutoff', paymentWindowStart],
    queryFn: () => paymentsApi.getAll(paymentWindowStart),
    staleTime: 1000 * 60 * 15,
    gcTime: 1000 * 60 * 30,
    retry: 1,
  })

  // Payment status color indicators (days since last payment)
  const paymentStatusQuery = useQuery({
    queryKey: ['payments', 'status'],
    queryFn: () => paymentsApi.getStatus(),
    staleTime: 1000 * 60 * 10,
    gcTime: 1000 * 60 * 20,
    retry: 1,
  })

  const overview = debtsQuery.data
  const waybills = (waybillsQuery.data || []) as Waybill[]
  const payments = (paymentsQuery.data || []) as Payment[]
  const paymentStatus = (paymentStatusQuery.data || {}) as Record<string, import('@/types/domain').PaymentStatus>

  const excludeMutation = useMutation({
    mutationFn: ({ customerId, exclude }: { customerId: string; exclude: boolean }) =>
      exclude ? configApi.addExcludedCustomer(customerId) : configApi.removeExcludedCustomer(customerId),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['debts'] }),
  })

  const handleToggleExcluded = (customerId: string, currentlyExcluded: boolean) => {
    if (!currentlyExcluded) {
      const confirmed = window.confirm('Exclude this customer from total debt calculation?')
      if (!confirmed) return
    }
    excludeMutation.mutate({ customerId, exclude: !currentlyExcluded })
  }

  // Detail lists grouped by CANONICAL id, matched to the authoritative server rows.
  const detailsByCanonical = React.useMemo(() => {
    const map = new Map<string, { waybills: Waybill[]; payments: PaymentDetail[] }>()
    const bucket = (id: string) => {
      const key = canonicalId(id)
      let entry = map.get(key)
      if (!entry) { entry = { waybills: [], payments: [] }; map.set(key, entry) }
      return entry
    }
    waybills.forEach(wb => {
      if (!wb.customerId) return
      const afterCutoff = wb.isAfterCutoff ?? (wb as { afterCutoff?: boolean }).afterCutoff
      if (!afterCutoff) return
      bucket(wb.customerId).waybills.push(wb)
    })
    payments.forEach(p => {
      if (!p.customerId) return
      if ((p.paymentDate || '') < paymentWindowStart) return
      bucket(p.customerId).payments.push({
        date: p.paymentDate || '',
        payment: Number(p.amount) || 0,
        source: p.source ?? undefined,
        uniqueCode: p.uniqueCode ?? undefined,
        paymentId: p.id ?? undefined,
        description: p.description ?? undefined,
      })
    })
    return map
  }, [waybills, payments, paymentWindowStart])

  // Rows = authoritative server debt + matched detail lists.
  const rows = React.useMemo<DebtRow[]>(() => {
    if (!overview) return []
    return overview.customers.map(c => {
      const d = detailsByCanonical.get(canonicalId(c.customerId))
      return { ...c, waybills: d?.waybills ?? [], payments: d?.payments ?? [] }
    })
  }, [overview, detailsByCanonical])

  const sortedCustomers = React.useMemo(() => {
    let customers = rows
    if (searchTerm) {
      customers = customers.filter(c =>
        c.customerId.includes(searchTerm) ||
        c.customerName.toLowerCase().includes(searchTerm.toLowerCase())
      )
    }
    return [...customers].sort((a, b) => {
      const aVal = a[sortBy]
      const bVal = b[sortBy]
      if (typeof aVal === 'string' && typeof bVal === 'string') {
        return sortOrder === 'desc' ? bVal.localeCompare(aVal) : aVal.localeCompare(bVal)
      }
      const aNum = Number(aVal) || 0
      const bNum = Number(bVal) || 0
      return sortOrder === 'desc' ? bNum - aNum : aNum - bNum
    })
  }, [rows, searchTerm, sortBy, sortOrder])

  const totalPages = Math.ceil(sortedCustomers.length / itemsPerPage)
  const paginatedCustomers = React.useMemo(() => {
    const startIndex = (currentPage - 1) * itemsPerPage
    return sortedCustomers.slice(startIndex, startIndex + itemsPerPage)
  }, [sortedCustomers, currentPage, itemsPerPage])

  React.useEffect(() => {
    setCurrentPage(1)
  }, [searchTerm, sortBy, sortOrder])

  // Headline totals come straight from the server (exclusions already applied).
  const totalExpected = overview?.totalSales ?? 0
  const totalReceived = overview?.totalPayments ?? 0
  const totalCashReceived = overview?.totalCashPayments ?? 0
  const totalOutstanding = overview?.totalOutstanding ?? 0

  const isDataLoading = debtsQuery.isLoading

  const uploadMutation = useMutation({
    mutationFn: ({ file, bank }: { file: File; bank: 'tbc' | 'bog' }) =>
      paymentsApi.uploadExcel(file, bank),
    onSuccess: (data: any) => {
      const addedCount = data.addedCount || data.newCount || 0
      const duplicateCount = data.duplicateCount || 0
      setUploadStatus(`✅ ${addedCount} ახალი გადახდა, ${duplicateCount} დუბლიკატი`)
      void queryClient.invalidateQueries({ queryKey: ['payments'] })
      void queryClient.invalidateQueries({ queryKey: ['debts'] })
      if (fileInputRef.current) fileInputRef.current.value = ''
    },
    onError: (err) => {
      setUploadStatus(`❌ შეცდომა: ${getApiErrorMessage(err)}`)
    },
  })

  const manualUploadMutation = useMutation({
    mutationFn: (file: File) => paymentsApi.uploadManualExcel(file),
    onSuccess: () => {
      setManualUploadStatus('OK - Manual cash payments uploaded')
      void queryClient.invalidateQueries({ queryKey: ['payments'] })
      void queryClient.invalidateQueries({ queryKey: ['debts'] })
      if (manualFileInputRef.current) manualFileInputRef.current.value = ''
    },
    onError: (err) => {
      setManualUploadStatus(`Error: ${getApiErrorMessage(err)}`)
    },
  })

  const clearBankPaymentsMutation = useMutation({
    mutationFn: () => paymentsApi.deleteBankPayments(),
    onSuccess: (data) => {
      setUploadStatus(`✅ წაიშალა ${data.deleted} ბანკის გადახდა`)
      void queryClient.invalidateQueries({ queryKey: ['payments'] })
      void queryClient.invalidateQueries({ queryKey: ['debts'] })
    },
    onError: (err) => {
      setUploadStatus(`❌ წაშლა ვერ მოხერხდა: ${getApiErrorMessage(err)}`)
    },
  })

  const handleClearBankPayments = () => {
    const confirmed = window.confirm(
      'დარწმუნებული ხართ, რომ გსურთ ყველა ბანკის გადახდის წაშლა?\n\n' +
      'ეს მოქმედება წაშლის ყველა TBC და BOG გადახდას Firebase-დან.\n' +
      'ეს მოქმედება შეუქცევადია!'
    )
    if (!confirmed) return
    setUploadStatus('⏳ იშლება ბანკის გადახდები...')
    clearBankPaymentsMutation.mutate()
  }

  const handleFileSelect = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    if (file.size > 10 * 1024 * 1024) {
      setUploadStatus('❌ ფაილის ზომა ძალიან დიდია (მაქს. 10MB)')
      return
    }
    setUploadStatus('⏳ იტვირთება...')
    uploadMutation.mutate({ file, bank: selectedBank })
  }

  const handleManualFileSelect = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    if (file.size > 10 * 1024 * 1024) {
      setManualUploadStatus('Error: file exceeds 10MB')
      return
    }
    setManualUploadStatus('Uploading...')
    manualUploadMutation.mutate(file)
  }

  const handleSort = (column: SortKey) => {
    if (sortBy === column) {
      setSortOrder(sortOrder === 'desc' ? 'asc' : 'desc')
    } else {
      setSortBy(column)
      setSortOrder('desc')
    }
  }

  const toggleCustomerDetails = (customerId: string) => {
    setExpandedCustomers(prev => {
      const newSet = new Set(prev)
      if (newSet.has(customerId)) newSet.delete(customerId)
      else newSet.add(customerId)
      return newSet
    })
  }

  const handleManualPaymentAdd = async (customer: DebtRow) => {
    const date = window.prompt('Payment date (YYYY-MM-DD):', formatDateISO(new Date()))
    if (!date) return
    const amountRaw = window.prompt('Amount:', '')
    if (!amountRaw) return
    const amount = Number(amountRaw.replace(',', '.'))
    if (!Number.isFinite(amount) || amount <= 0) {
      window.alert('Amount must be a positive number')
      return
    }
    const description = window.prompt('Description (optional):', '') || undefined

    try {
      await paymentsApi.addManualCashPayment({
        customerId: customer.customerId,
        customerName: customer.customerName,
        amount,
        paymentDate: date,
        description,
      })
      void queryClient.invalidateQueries({ queryKey: ['payments'] })
      void queryClient.invalidateQueries({ queryKey: ['debts'] })
    } catch (error) {
      window.alert(`Failed to add manual payment: ${getApiErrorMessage(error)}`)
    }
  }

  const handleManualPaymentDelete = async (paymentId?: string) => {
    if (!paymentId) return
    const confirmed = window.confirm('Delete this manual payment?')
    if (!confirmed) return

    try {
      await paymentsApi.deleteManualCashPayment(paymentId)
      void queryClient.invalidateQueries({ queryKey: ['payments'] })
      void queryClient.invalidateQueries({ queryKey: ['debts'] })
    } catch (error) {
      window.alert(`Failed to delete manual payment: ${getApiErrorMessage(error)}`)
    }
  }

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-xl font-semibold tracking-tight md:text-2xl">გადახდები</h1>
        <p className="text-sm text-muted-foreground">
          ბანკის ამონაწერის ატვირთვა, გადახდების შედარება და ვალების მართვა
        </p>
      </div>

      {/* Outstanding Debt */}
      <Card className="p-5 md:p-6 text-center border-2">
        <div className="text-sm text-muted-foreground mb-2">ჯამური ვალი</div>
        {isDataLoading ? (
          <Skeleton className="h-14 w-44 mx-auto" />
        ) : (
          <div className="text-3xl md:text-4xl font-bold">
            <span className={totalOutstanding > 0 ? 'text-red-600 dark:text-red-500' : 'text-green-600 dark:text-green-500'}>
              {formatCurrency(totalOutstanding)}
            </span>
          </div>
        )}
        <div className="mt-2 text-sm text-muted-foreground">
          გაყიდვები: {isDataLoading ? '…' : formatCurrency(totalExpected)} |
          გადახდები: {isDataLoading ? '…' : formatCurrency(totalReceived)}
        </div>
      </Card>

      {/* Search Bar */}
      <Card className="p-4">
        <div className="flex flex-col md:flex-row items-start md:items-center gap-4">
          <input
            type="text"
            placeholder="მომხმარებლის ძებნა (ID ან სახელი)..."
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            className="px-4 py-3 border rounded-md focus:outline-none focus:ring-2 focus:ring-primary w-full md:w-96 text-sm"
          />
          <span className="text-sm text-muted-foreground whitespace-nowrap">
            {sortedCustomers.length} მომხმარებელი (გვერდი {currentPage}/{totalPages || 1})
          </span>
        </div>
      </Card>

      {/* Customer Analysis Table */}
      <Card className="p-4 md:p-6">
        <h2 className="text-lg font-semibold mb-4">მომხმარებელთა ანალიზი</h2>

        <div className="overflow-x-auto">
          <table className="min-w-full divide-y">
            <thead className="bg-muted">
              <tr>
                <th className="px-4 py-3 text-left text-xs font-medium uppercase">სახელი</th>
                <th
                  onClick={() => handleSort('currentDebt')}
                  className="px-4 py-3 text-left text-xs font-medium uppercase cursor-pointer hover:bg-accent"
                >
                  ვალი {sortBy === 'currentDebt' && (sortOrder === 'desc' ? '↓' : '↑')}
                </th>
                <th className="px-4 py-3 text-left text-xs font-medium uppercase">ხელით</th>
                <th
                  onClick={() => handleSort('totalPayments')}
                  className="px-4 py-3 text-left text-xs font-medium uppercase cursor-pointer hover:bg-accent"
                >
                  გადახდები {sortBy === 'totalPayments' && (sortOrder === 'desc' ? '↑' : '↓')}
                </th>
                <th
                  onClick={() => handleSort('totalSales')}
                  className="px-4 py-3 text-left text-xs font-medium uppercase cursor-pointer hover:bg-accent"
                >
                  გაყიდვები {sortBy === 'totalSales' && (sortOrder === 'desc' ? '↓' : '↑')}
                </th>
                <th
                  onClick={() => handleSort('startingDebt')}
                  className="px-4 py-3 text-left text-xs font-medium uppercase cursor-pointer hover:bg-accent"
                >
                  საწყისი {sortBy === 'startingDebt' && (sortOrder === 'desc' ? '↓' : '↑')}
                </th>
                <th
                  onClick={() => handleSort('customerId')}
                  className="px-4 py-3 text-left text-xs font-medium uppercase cursor-pointer hover:bg-accent"
                >
                  ID {sortBy === 'customerId' && (sortOrder === 'desc' ? '↓' : '↑')}
                </th>
                <th className="px-4 py-3 text-left text-xs font-medium uppercase">ჩართვა</th>
                <th className="px-4 py-3 text-left text-xs font-medium uppercase">დეტალები</th>
              </tr>
            </thead>
            <tbody className="divide-y">
              {isDataLoading ? (
                <tr>
                  <td colSpan={9} className="px-4 py-8 text-center text-sm text-muted-foreground">
                    <Skeleton className="h-4 w-48 mx-auto mb-2" />
                    <Skeleton className="h-4 w-32 mx-auto" />
                  </td>
                </tr>
              ) : paginatedCustomers.map((customer) => {
                const status = paymentStatus[customer.customerId]
                const statusColor = customer.currentDebt > 0 && status?.statusColor !== 'none'
                  ? status?.statusColor
                  : null
                const rowBgClass = statusColor === 'yellow'
                  ? 'bg-yellow-50/50 dark:bg-yellow-900/10 hover:bg-yellow-100/50 dark:hover:bg-yellow-900/20'
                  : statusColor === 'red'
                  ? 'bg-red-50/50 dark:bg-red-900/10 hover:bg-red-100/50 dark:hover:bg-red-900/20'
                  : 'hover:bg-accent/50'

                return (
                  <React.Fragment key={customer.customerId}>
                    <tr className={rowBgClass}>
                      <td className="px-4 py-3 text-sm">{customer.customerName}</td>
                      <td className={`px-4 py-3 text-sm font-medium ${
                        customer.currentDebt > 0 ? 'text-red-600 dark:text-red-500' :
                        customer.currentDebt < 0 ? 'text-green-600 dark:text-green-500' :
                        ''
                      }`}>
                        {formatCurrency(customer.currentDebt)}
                      </td>
                      <td className="px-4 py-3 text-sm">
                        <Button size="sm" variant="outline" onClick={() => handleManualPaymentAdd(customer)}>
                          დამატება
                        </Button>
                      </td>
                      <td className="px-4 py-3 text-sm">{formatCurrency(customer.totalPayments)}</td>
                      <td className="px-4 py-3 text-sm">{formatCurrency(customer.totalSales)}</td>
                      <td className="px-4 py-3 text-sm">{formatCurrency(customer.startingDebt)}</td>
                      <td className="px-4 py-3 text-sm font-medium">{customer.customerId}</td>
                      <td className="px-4 py-3 text-sm">
                        <input
                          type="checkbox"
                          checked={!customer.excluded}
                          onChange={() => handleToggleExcluded(customer.customerId, customer.excluded)}
                        />
                      </td>
                      <td className="px-4 py-3 text-sm">
                        <Button
                          size="sm"
                          variant={expandedCustomers.has(customer.customerId) ? 'default' : 'outline'}
                          onClick={() => toggleCustomerDetails(customer.customerId)}
                        >
                          {expandedCustomers.has(customer.customerId) ? 'დამალვა' : 'ნახვა'}
                        </Button>
                      </td>
                    </tr>

                    {expandedCustomers.has(customer.customerId) && (
                      <tr className="bg-muted/50">
                        <td colSpan={9} className="px-4 py-4">
                          <div className="max-h-[600px] overflow-y-auto space-y-6">
                            {/* Waybills Section */}
                            <div>
                              <h4 className="text-sm font-semibold mb-3">
                                {customer.customerName} - გაყიდვები ({customer.waybillCount} ზედნადები)
                              </h4>

                              {customer.waybills && customer.waybills.length > 0 ? (
                                <div className="space-y-3">
                                  {customer.waybills
                                    .sort((a, b) => (b.date || '').localeCompare(a.date || ''))
                                    .map((waybill, idx) => (
                                      <div key={idx} className="rounded-lg p-4 border-2 bg-blue-50 dark:bg-blue-900/20 border-blue-200">
                                        <div className="grid grid-cols-2 md:grid-cols-4 gap-4 text-sm">
                                          <div>
                                            <span className="font-medium text-muted-foreground">თარიღი:</span>
                                            <div className="font-medium">{waybill.date}</div>
                                          </div>
                                          <div>
                                            <span className="font-medium text-muted-foreground">თანხა:</span>
                                            <div className="text-red-600 dark:text-red-500 font-bold">
                                              {formatCurrency(Number(waybill.amount) || 0)}
                                            </div>
                                          </div>
                                          <div>
                                            <span className="font-medium text-muted-foreground">ზედნადები:</span>
                                            <div className="font-mono text-xs">{waybill.waybillId || waybill.id}</div>
                                          </div>
                                          <div>
                                            <span className="font-medium text-muted-foreground">ტიპი:</span>
                                            <div>{waybill.type === 'SALE' ? 'გაყიდვა' : 'შესყიდვა'}</div>
                                          </div>
                                        </div>
                                      </div>
                                    ))}

                                  <div className="mt-4 p-3 bg-blue-50 dark:bg-blue-900/20 rounded border">
                                    <div className="flex justify-between items-center text-sm">
                                      <span className="font-medium">სულ გაყიდვები:</span>
                                      <span className="font-bold">{formatCurrency(customer.totalSales)}</span>
                                    </div>
                                    <div className="flex justify-between items-center text-sm mt-1">
                                      <span className="font-medium">საწყისი ვალი:</span>
                                      <span>{formatCurrency(customer.startingDebt)}</span>
                                    </div>
                                  </div>
                                </div>
                              ) : (
                                <div className="text-muted-foreground text-center py-4">
                                  გაყიდვები არ არის ნაპოვნი
                                </div>
                              )}
                            </div>

                            {/* Payments Section */}
                            <div>
                              <h4 className="text-sm font-semibold mb-3">
                                {customer.customerName} - გადახდების დეტალები ({customer.paymentCount})
                              </h4>

                              {customer.payments && customer.payments.length > 0 ? (
                                <div className="space-y-3">
                                  {customer.payments
                                    .sort((a, b) => new Date(b.date).getTime() - new Date(a.date).getTime())
                                    .map((payment, idx) => {
                                      const isAfterCutoff = payment.date >= paymentWindowStart
                                      const isManual = (payment.source || '').toLowerCase() === 'manual-cash'

                                      return (
                                        <div key={idx} className={`rounded-lg p-4 border-2 ${
                                          isAfterCutoff
                                            ? 'bg-green-50 dark:bg-green-900/20 border-green-200'
                                            : 'bg-orange-50 dark:bg-orange-900/20 border-orange-200'
                                        }`}>
                                          <div className="grid grid-cols-2 md:grid-cols-4 gap-4 text-sm">
                                            <div>
                                              <span className="font-medium text-muted-foreground">თარიღი:</span>
                                              <div className="font-medium">{payment.date}</div>
                                            </div>
                                            <div>
                                              <span className="font-medium text-muted-foreground">თანხა:</span>
                                              <div className="text-green-600 dark:text-green-500 font-bold">
                                                {formatCurrency(payment.payment)}
                                              </div>
                                            </div>
                                            <div>
                                              <span className="font-medium text-muted-foreground">წყარო:</span>
                                              <div>{payment.source || 'Firebase'}</div>
                                            </div>
                                            <div>
                                              <span className="font-medium text-muted-foreground">კოდი:</span>
                                              <div className="text-xs font-mono truncate">
                                                {payment.uniqueCode || 'N/A'}
                                              </div>
                                            </div>
                                            {isManual && (
                                              <div>
                                                <span className="font-medium text-muted-foreground">Delete:</span>
                                                <div className="mt-1">
                                                  <Button
                                                    size="sm"
                                                    variant="outline"
                                                    onClick={() => handleManualPaymentDelete(payment.paymentId)}
                                                  >
                                                    Delete
                                                  </Button>
                                                </div>
                                              </div>
                                            )}
                                            {payment.description && (
                                              <div className="col-span-2 md:col-span-4">
                                                <span className="font-medium text-muted-foreground">აღწერა:</span>
                                                <div className="mt-1">{payment.description}</div>
                                              </div>
                                            )}
                                          </div>
                                        </div>
                                      )
                                    })}

                                  <div className="mt-4 p-3 bg-blue-50 dark:bg-blue-900/20 rounded border">
                                    <div className="flex justify-between items-center text-sm">
                                      <span className="font-medium">სულ გადახდები:</span>
                                      <span className="font-bold">{formatCurrency(customer.totalPayments)}</span>
                                    </div>
                                    <div className="flex justify-between items-center text-sm mt-1">
                                      <span className="font-medium">რაოდენობა:</span>
                                      <span>{customer.paymentCount}</span>
                                    </div>
                                  </div>
                                </div>
                              ) : (
                                <div className="text-muted-foreground text-center py-4">
                                  გადახდები არ არის ნაპოვნი
                                </div>
                              )}
                            </div>
                          </div>
                        </td>
                      </tr>
                    )}
                  </React.Fragment>
                )
              })}
            </tbody>
          </table>
        </div>

        {/* Pagination Controls */}
        {totalPages > 1 && (
          <div className="flex items-center justify-between gap-4 mt-4 px-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => setCurrentPage(p => Math.max(1, p - 1))}
              disabled={currentPage === 1}
            >
              ← წინა
            </Button>
            <div className="flex items-center gap-2 text-sm">
              <span className="text-muted-foreground">გვერდი</span>
              <span className="font-medium">{currentPage}</span>
              <span className="text-muted-foreground">/ {totalPages}</span>
              <span className="text-muted-foreground">
                ({(currentPage - 1) * itemsPerPage + 1}-{Math.min(currentPage * itemsPerPage, sortedCustomers.length)} / {sortedCustomers.length})
              </span>
            </div>
            <Button
              variant="outline"
              size="sm"
              onClick={() => setCurrentPage(p => Math.min(totalPages, p + 1))}
              disabled={currentPage === totalPages}
            >
              შემდეგი →
            </Button>
          </div>
        )}
      </Card>

      {/* Summary Stats */}
      <div className="grid grid-cols-2 gap-3 md:grid-cols-4 md:gap-4">
        <Card className="p-3 md:p-4">
          <div className="text-xs text-muted-foreground">გაყიდვები</div>
          <div className="mt-1 text-lg font-semibold md:text-xl">
            {isDataLoading ? <Skeleton className="h-6 w-24" /> : formatCurrency(totalExpected)}
          </div>
        </Card>
        <Card className="p-3 md:p-4">
          <div className="text-xs text-muted-foreground">ბანკი</div>
          <div className="mt-1 text-lg font-semibold md:text-xl">
            {isDataLoading ? <Skeleton className="h-6 w-24" /> : formatCurrency(totalReceived - totalCashReceived)}
          </div>
        </Card>
        <Card className="p-3 md:p-4">
          <div className="text-xs text-muted-foreground">ნაღდი</div>
          <div className="mt-1 text-lg font-semibold md:text-xl">
            {isDataLoading ? <Skeleton className="h-6 w-24" /> : formatCurrency(totalCashReceived)}
          </div>
        </Card>
        <Card className="p-3 md:p-4">
          <div className="text-xs text-muted-foreground">გადახდები</div>
          <div className="mt-1 text-lg font-semibold md:text-xl">
            {paymentsQuery.isLoading ? <Skeleton className="h-6 w-16" /> : payments.length}
          </div>
        </Card>
      </div>

      {/* Bank Statement Upload */}
      <Card className="p-4 md:p-5">
        <div className="text-sm font-medium mb-3">ბანკის ამონაწერის ატვირთვა</div>
        <div className="flex flex-col gap-3">
          <div className="flex gap-2">
            <Button
              variant={selectedBank === 'tbc' ? 'default' : 'outline'}
              onClick={() => setSelectedBank('tbc')}
              className="flex-1"
            >
              თიბისი ბანკი
            </Button>
            <Button
              variant={selectedBank === 'bog' ? 'default' : 'outline'}
              onClick={() => setSelectedBank('bog')}
              className="flex-1"
            >
              საქართველოს ბანკი
            </Button>
          </div>

          <div
            className="relative rounded-lg border-2 border-dashed p-8 text-center transition-colors hover:border-primary hover:bg-accent cursor-pointer"
            onClick={() => fileInputRef.current?.click()}
          >
            <input
              ref={fileInputRef}
              type="file"
              accept=".xlsx,.xls"
              onChange={handleFileSelect}
              className="hidden"
            />
            <div className="text-sm text-muted-foreground">
              დააჭირეთ Excel ფაილის ასარჩევად
            </div>
            <div className="mt-1 text-xs text-muted-foreground">
              არჩეული ბანკი: {selectedBank.toUpperCase()} | მაქს. ზომა: 10MB
            </div>
          </div>

          {uploadStatus && (
            <div className={`text-sm p-3 rounded-md ${
              uploadStatus.startsWith('✅') ? 'bg-green-100 dark:bg-green-900/20 text-green-800 dark:text-green-300' :
              uploadStatus.startsWith('❌') ? 'bg-red-100 dark:bg-red-900/20 text-red-800 dark:text-red-300' :
              'bg-blue-100 dark:bg-blue-900/20 text-blue-800 dark:text-blue-300'
            }`}>
              {uploadStatus}
            </div>
          )}

          {/* Clear Bank Payments Button */}
          <div className="border-t pt-3 mt-3">
            <Button
              variant="destructive"
              onClick={handleClearBankPayments}
              disabled={clearBankPaymentsMutation.isPending}
              className="w-full"
            >
              {clearBankPaymentsMutation.isPending ? '⏳ იშლება...' : '🗑️ ბანკის გადახდების წაშლა'}
            </Button>
            <div className="mt-1 text-xs text-muted-foreground text-center">
              წაშლის ყველა TBC და BOG გადახდას Firebase-დან
            </div>
          </div>
        </div>
      </Card>

      {/* Manual Cash Excel Upload */}
      <Card className="p-4 md:p-5">
        <div className="text-sm font-medium mb-3">ნაღდი გადახდები (Excel)</div>
        <div className="flex flex-col gap-3">
          <div
            className="relative rounded-lg border-2 border-dashed p-8 text-center transition-colors hover:border-primary hover:bg-accent cursor-pointer"
            onClick={() => manualFileInputRef.current?.click()}
          >
            <input
              ref={manualFileInputRef}
              type="file"
              accept=".xlsx,.xls"
              onChange={handleManualFileSelect}
              className="hidden"
            />
            <div className="text-sm text-muted-foreground">
              Excel ფაილის ატვირთვა (A=თარიღი, C=თანხა, E=მომხმარებელი)
            </div>
          </div>

          {manualUploadStatus && (
            <div className={`text-sm p-3 rounded-md ${
              manualUploadStatus.startsWith('OK') ? 'bg-green-100 dark:bg-green-900/20 text-green-800 dark:text-green-300' :
              manualUploadStatus.startsWith('Error') ? 'bg-red-100 dark:bg-red-900/20 text-red-800 dark:text-red-300' :
              'bg-blue-100 dark:bg-blue-900/20 text-blue-800 dark:text-blue-300'
            }`}>
              {manualUploadStatus}
            </div>
          )}
        </div>
      </Card>

      {(waybillsQuery.isError || paymentsQuery.isError) && (
        <Card className="border-destructive/30 bg-destructive/10 p-3 text-sm text-destructive md:p-4">
          <div className="font-medium">მონაცემების ჩატვირთვა ვერ მოხერხდა</div>
          <div className="mt-1 text-xs">
            {waybillsQuery.isError && <div>ზედნადებები (RS.ge): {getApiErrorMessage(waybillsQuery.error)}</div>}
            {paymentsQuery.isError && <div>გადახდები: {getApiErrorMessage(paymentsQuery.error)}</div>}
          </div>
        </Card>
      )}
    </div>
  )
}
