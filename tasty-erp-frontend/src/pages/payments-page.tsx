import * as React from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { addDays } from 'date-fns'
import { paymentsApi, waybillsApi, configApi, ApiError } from '@/lib/api-client'
import { useCachedQuery } from '@/lib/use-cached-query'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { Button } from '@/components/ui/button'
import { formatCurrency, formatDateISO } from '@/lib/utils'
import type { CustomerAnalysis, Payment, InitialDebt } from '@/types/domain'

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
  const [sortBy, setSortBy] = React.useState<keyof CustomerAnalysis>('currentDebt')
  const [sortOrder, setSortOrder] = React.useState<'asc' | 'desc'>('desc')
  const [expandedCustomers, setExpandedCustomers] = React.useState<Set<string>>(new Set())
  const [currentPage, setCurrentPage] = React.useState(1)
  const itemsPerPage = 50
  const [excludedCustomers, setExcludedCustomers] = React.useState<Set<string>>(() => {
    if (typeof window === 'undefined') return new Set()
    const raw = window.localStorage.getItem('tasty-erp-excluded-customers')
    if (!raw) return new Set()
    try {
      const list = JSON.parse(raw) as string[]
      return new Set(list)
    } catch {
      return new Set()
    }
  })

  const paymentWindowStart = formatDateISO(addDays(new Date(PAYMENT_CUTOFF_DATE), 1))

  // Pre-aggregated customer sales totals from RS.ge via waybill-service
  // ~50 objects (one per customer), takes 1-3 min on first load, cached 30 min
  const salesTotalsQuery = useQuery({
    queryKey: ['waybills', 'salesTotals'],
    queryFn: () => waybillsApi.getCustomerSalesTotals(),
    staleTime: 1000 * 60 * 30, // Cache for 30 minutes
    gcTime: 1000 * 60 * 60,   // Keep in memory for 1 hour
    retry: 1,
  })

  // Bank + manual cash payments from Firebase
  const paymentsQuery = useQuery({
    queryKey: ['payments', 'afterCutoff', paymentWindowStart],
    queryFn: () => paymentsApi.getAll(paymentWindowStart),
    staleTime: 1000 * 60 * 15,
    gcTime: 1000 * 60 * 30,
    retry: 1,
  })

  // Initial debts (starting balances) from config-service — shared cache with waybills page
  const initialDebtsQuery = useCachedQuery({
    queryKey: ['config', 'initialDebts'],
    queryFn: async () => {
      const result = await configApi.getInitialDebts()
      if (Array.isArray(result)) return result as InitialDebt[]
      if (typeof result === 'object' && result !== null) {
        return Object.entries(result).map(([customerId, data]: [string, any]) => ({
          customerId,
          customerName: data.name || data.customerName || customerId,
          debt: Number(data.debt || data.amount || 0),
          date: data.date || PAYMENT_CUTOFF_DATE,
        }))
      }
      return [] as InitialDebt[]
    },
    cacheKey: 'initial_debts',
    cacheTTL: 7 * 24 * 60 * 60 * 1000, // Cache for 7 days
    staleTime: 1000 * 60 * 60 * 4,     // Refetch after 4 hours
    gcTime: 1000 * 60 * 60 * 24,       // Keep in memory for 24 hours
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

  const payments = (paymentsQuery.data || []) as Payment[]
  const salesTotals = salesTotalsQuery.data ?? []
  const initialDebts = initialDebtsQuery.data ?? []
  const paymentStatus = (paymentStatusQuery.data || {}) as Record<string, import('@/types/domain').PaymentStatus>

  const handleToggleExcluded = (customerId: string) => {
    setExcludedCustomers(prev => {
      const next = new Set(prev)
      if (next.has(customerId)) {
        next.delete(customerId)
      } else {
        const confirmed = window.confirm('Exclude this customer from total debt calculation?')
        if (!confirmed) return prev
        next.add(customerId)
      }
      window.localStorage.setItem('tasty-erp-excluded-customers', JSON.stringify(Array.from(next)))
      return next
    })
  }

  // Build payment detail lists per customer (bank + manual cash, filtered by cutoff + source)
  // Only include authorized sources: tbc, bog, manual-cash (same filter as waybills page)
  const customerPaymentLists = React.useMemo(() => {
    const map = new Map<string, CustomerAnalysis['payments']>()
    payments.forEach(p => {
      if (!p.customerId) return
      if (p.source !== 'tbc' && p.source !== 'bog' && p.source !== 'manual-cash') return
      const paymentDate = p.paymentDate || ''
      if (paymentDate < paymentWindowStart) return
      if (!map.has(p.customerId)) map.set(p.customerId, [])
      map.get(p.customerId)!.push({
        customerId: p.customerId,
        payment: Number(p.amount) || 0,
        date: paymentDate,
        isAfterCutoff: true,
        source: p.source || 'unknown',
        uniqueCode: p.uniqueCode ?? undefined,
        paymentId: p.id,
        description: p.description ?? undefined,
        balance: Number(p.balance) || 0,
      })
    })
    return map
  }, [payments, paymentWindowStart])

  // Build customer analysis from RS.ge sales totals + Firebase payments + initial debts
  // Formula: currentDebt = startingDebt + totalSales - totalPayments (bank + cash)
  const customerAnalysis = React.useMemo((): Record<string, CustomerAnalysis> => {
    // Sales map from pre-aggregated RS.ge data
    const salesMap = new Map<string, { name: string; total: number; count: number }>()
    salesTotals.forEach(s => {
      salesMap.set(s.customerId, {
        name: s.customerName,
        total: Number(s.totalSales) || 0,
        count: s.saleCount || 0,
      })
    })

    // Initial debts map
    const debtsMap = new Map<string, InitialDebt>()
    initialDebts.forEach(d => debtsMap.set(d.customerId, d))

    // Customer names from payments (for customers without sales)
    const paymentNames = new Map<string, string>()
    payments.forEach(p => {
      if (p.customerId && p.customerName && !paymentNames.has(p.customerId)) {
        paymentNames.set(p.customerId, typeof p.customerName === 'string' ? p.customerName : '')
      }
    })

    // Union of all customer IDs across all data sources
    const allIds = new Set<string>([
      ...salesMap.keys(),
      ...customerPaymentLists.keys(),
      ...debtsMap.keys(),
    ])

    const analysis: Record<string, CustomerAnalysis> = {}

    allIds.forEach(customerId => {
      const salesData = salesMap.get(customerId)
      const paymentList = customerPaymentLists.get(customerId) || []
      const cashPaymentList = paymentList.filter(p => p.source === 'manual-cash' || p.source === 'cash')
      const debtEntry = debtsMap.get(customerId)

      const customerName = salesData?.name ||
        paymentNames.get(customerId) ||
        debtEntry?.customerName ||
        'Unknown'

      const totalSales = salesData?.total ?? 0
      // totalPayments includes both bank and cash (all payment sources)
      const totalPayments = paymentList.reduce((sum, p) => sum + p.payment, 0)
      const totalCashPayments = cashPaymentList.reduce((sum, p) => sum + p.payment, 0)
      const startingDebt = debtEntry?.debt ?? 0
      const startingDebtDate = debtEntry?.date ?? null

      // currentDebt = startingDebt + totalSales - totalPayments (bank + cash)
      const currentDebt = startingDebt + totalSales - totalPayments

      analysis[customerId] = {
        customerId,
        customerName,
        totalSales,
        totalPayments,
        totalCashPayments,
        currentDebt,
        startingDebt,
        startingDebtDate,
        waybillCount: salesData?.count ?? 0,
        paymentCount: paymentList.length,
        waybills: [], // Individual waybill data not loaded (using pre-aggregated totals)
        payments: paymentList,
        cashPayments: cashPaymentList,
      }
    })

    return analysis
  }, [salesTotals, initialDebts, customerPaymentLists, payments])

  const includedTotals = React.useMemo(() => {
    let totalSales = 0
    let totalPayments = 0
    let totalCashPayments = 0
    let totalStartingDebts = 0

    Object.values(customerAnalysis).forEach(customer => {
      if (excludedCustomers.has(customer.customerId)) return
      totalSales += customer.totalSales
      totalPayments += customer.totalPayments
      totalCashPayments += customer.totalCashPayments
      totalStartingDebts += customer.startingDebt
    })

    // totalPayments already includes bank + cash, so this correctly subtracts all payments
    const totalOutstanding = totalStartingDebts + totalSales - totalPayments

    return {
      totalSales,
      totalPayments,
      totalCashPayments,
      totalOutstanding,
    }
  }, [customerAnalysis, excludedCustomers])

  // Filter and sort customers
  const sortedCustomers = React.useMemo(() => {
    let customers = Object.values(customerAnalysis)

    if (searchTerm) {
      customers = customers.filter(c =>
        c.customerId.includes(searchTerm) ||
        c.customerName.toLowerCase().includes(searchTerm.toLowerCase())
      )
    }

    customers.sort((a, b) => {
      const aVal = a[sortBy]
      const bVal = b[sortBy]

      if (typeof aVal === 'string' && typeof bVal === 'string') {
        return sortOrder === 'desc' ? bVal.localeCompare(aVal) : aVal.localeCompare(bVal)
      }

      const aNum = Number(aVal) || 0
      const bNum = Number(bVal) || 0
      return sortOrder === 'desc' ? bNum - aNum : aNum - bNum
    })

    return customers
  }, [customerAnalysis, searchTerm, sortBy, sortOrder])

  // Pagination
  const totalPages = Math.ceil(sortedCustomers.length / itemsPerPage)
  const paginatedCustomers = React.useMemo(() => {
    const startIndex = (currentPage - 1) * itemsPerPage
    return sortedCustomers.slice(startIndex, startIndex + itemsPerPage)
  }, [sortedCustomers, currentPage, itemsPerPage])

  React.useEffect(() => {
    setCurrentPage(1)
  }, [searchTerm, sortBy, sortOrder])

  const totalExpected = includedTotals.totalSales
  const totalReceived = includedTotals.totalPayments
  const totalCashReceived = includedTotals.totalCashPayments
  const totalOutstanding = includedTotals.totalOutstanding

  const isDataLoading = salesTotalsQuery.isLoading || paymentsQuery.isLoading || initialDebtsQuery.isLoading

  const uploadMutation = useMutation({
    mutationFn: ({ file, bank }: { file: File; bank: 'tbc' | 'bog' }) =>
      paymentsApi.uploadExcel(file, bank),
    onSuccess: (data: any) => {
      const addedCount = data.addedCount || data.newCount || 0
      const duplicateCount = data.duplicateCount || 0
      setUploadStatus(`✅ ${addedCount} ახალი გადახდა, ${duplicateCount} დუბლიკატი`)
      void queryClient.invalidateQueries({ queryKey: ['payments'] })
      if (fileInputRef.current) {
        fileInputRef.current.value = ''
      }
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
      if (manualFileInputRef.current) {
        manualFileInputRef.current.value = ''
      }
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

  const handleSort = (column: keyof CustomerAnalysis) => {
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
      if (newSet.has(customerId)) {
        newSet.delete(customerId)
      } else {
        newSet.add(customerId)
      }
      return newSet
    })
  }

  const handleManualPaymentAdd = async (customer: CustomerAnalysis) => {
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

      {/* RS.ge loading indicator (first load takes 1-3 minutes) */}
      {salesTotalsQuery.isLoading && (
        <div className="bg-blue-50 dark:bg-blue-900/20 border border-blue-200 rounded-lg p-3 text-sm text-blue-800 dark:text-blue-200">
          🔄 RS.ge-დან გაყიდვების მონაცემები იტვირთება... (შეიძლება 1-3 წუთი გაგრძელდეს)
        </div>
      )}

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
          გაყიდვები: {salesTotalsQuery.isLoading ? '…' : formatCurrency(totalExpected)} |
          გადახდები: {paymentsQuery.isLoading ? '…' : formatCurrency(totalReceived)}
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
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => handleManualPaymentAdd(customer)}
                        >
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
                          checked={!excludedCustomers.has(customer.customerId)}
                          onChange={() => handleToggleExcluded(customer.customerId)}
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
                            {/* Sales Summary Section */}
                            <div>
                              <h4 className="text-sm font-semibold mb-3">
                                {customer.customerName} - გაყიდვები ({customer.waybillCount} ზედნადები)
                              </h4>
                              <div className="p-3 bg-blue-50 dark:bg-blue-900/20 rounded border">
                                <div className="flex justify-between items-center text-sm">
                                  <span className="font-medium">სულ გაყიდვები:</span>
                                  <span className="font-bold">{formatCurrency(customer.totalSales)}</span>
                                </div>
                                <div className="flex justify-between items-center text-sm mt-1">
                                  <span className="font-medium">ზედნადების რაოდენობა:</span>
                                  <span>{customer.waybillCount}</span>
                                </div>
                                <div className="flex justify-between items-center text-sm mt-1">
                                  <span className="font-medium">საწყისი ვალი:</span>
                                  <span>{formatCurrency(customer.startingDebt)}</span>
                                </div>
                              </div>
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
            {salesTotalsQuery.isLoading ? <Skeleton className="h-6 w-24" /> : formatCurrency(totalExpected)}
          </div>
        </Card>
        <Card className="p-3 md:p-4">
          <div className="text-xs text-muted-foreground">ბანკი</div>
          <div className="mt-1 text-lg font-semibold md:text-xl">
            {paymentsQuery.isLoading ? <Skeleton className="h-6 w-24" /> : formatCurrency(totalReceived - totalCashReceived)}
          </div>
        </Card>
        <Card className="p-3 md:p-4">
          <div className="text-xs text-muted-foreground">ნაღდი</div>
          <div className="mt-1 text-lg font-semibold md:text-xl">
            {paymentsQuery.isLoading ? <Skeleton className="h-6 w-24" /> : formatCurrency(totalCashReceived)}
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

      {(salesTotalsQuery.isError || paymentsQuery.isError || initialDebtsQuery.isError) && (
        <Card className="border-destructive/30 bg-destructive/10 p-3 text-sm text-destructive md:p-4">
          <div className="font-medium">მონაცემების ჩატვირთვა ვერ მოხერხდა</div>
          <div className="mt-1 text-xs">
            {salesTotalsQuery.isError && <div>გაყიდვები (RS.ge): {getApiErrorMessage(salesTotalsQuery.error)}</div>}
            {paymentsQuery.isError && <div>გადახდები: {getApiErrorMessage(paymentsQuery.error)}</div>}
            {initialDebtsQuery.isError && <div>საწყისი ვალები: {getApiErrorMessage(initialDebtsQuery.error)}</div>}
          </div>
        </Card>
      )}
    </div>
  )
}
