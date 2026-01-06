import * as React from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { addDays } from 'date-fns'
import { paymentsApi, waybillsApi, configApi, ApiError } from '@/lib/api-client'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { Button } from '@/components/ui/button'
import { formatCurrency, formatDateISO } from '@/lib/utils'
import { useCachedQuery } from '@/lib/use-cached-query'
import type { CustomerAnalysis, Waybill, Payment, InitialDebt, AggregationJob } from '@/types/domain'

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
  const itemsPerPage = 50 // Show 50 customers per page
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

  // NEW: Aggregation job tracking
  const [aggregationJobId, setAggregationJobId] = React.useState<string | null>(null)
  const [aggregationJob, setAggregationJob] = React.useState<AggregationJob | null>(null)
  const [isPolling, setIsPolling] = React.useState(false)

  const paymentWindowStart = formatDateISO(addDays(new Date(PAYMENT_CUTOFF_DATE), 1))

  // Fetch all data needed for customer analysis
  const waybillsQuery = useQuery({
    queryKey: ['waybills', 'afterCutoff'],
    queryFn: () => waybillsApi.getAll({ afterCutoffOnly: true, type: 'SALE' }),
    staleTime: 1000 * 60 * 30, // Cache for 30 minutes - waybills change infrequently
    gcTime: 1000 * 60 * 60, // Keep in cache for 1 hour
    retry: 1, // Only retry once on failure
  })

  const paymentsQuery = useQuery({
    queryKey: ['payments', 'afterCutoff', paymentWindowStart],
    queryFn: () => paymentsApi.getAll(paymentWindowStart),
    staleTime: 1000 * 60 * 15, // Cache for 15 minutes - payments update more frequently
    gcTime: 1000 * 60 * 30, // Keep in cache for 30 minutes
    retry: 1,
  })

  const initialDebtsQuery = useCachedQuery({
    queryKey: ['config', 'initialDebts'],
    queryFn: async () => {
      const result = await configApi.getInitialDebts()
      // Convert Firebase response to InitialDebt array
      if (Array.isArray(result)) return result as InitialDebt[]
      if (typeof result === 'object' && result !== null) {
        // Handle object format {customerId: {debt, name, date}}
        return Object.entries(result).map(([customerId, data]: [string, any]) => ({
          customerId,
          customerName: data.name || data.customerName || customerId,
          debt: Number(data.debt || data.amount || 0),
          date: data.date || PAYMENT_CUTOFF_DATE
        }))
      }
      return []
    },
    cacheKey: 'initial_debts',
    cacheTTL: 7 * 24 * 60 * 60 * 1000, // Cache for 7 days in localStorage
    staleTime: 1000 * 60 * 60 * 4, // Refetch after 4 hours if page is open
    gcTime: 1000 * 60 * 60 * 24, // Keep in memory for 24 hours
    retry: 1,
  })

  const customersQuery = useQuery({
    queryKey: ['config', 'customers'],
    queryFn: () => configApi.getCustomers(),
    staleTime: 1000 * 60 * 60 * 12, // Cache for 12 hours - customer list rarely changes
    gcTime: 1000 * 60 * 60 * 24, // Keep in cache for 24 hours
    retry: false, // Don't retry on quota errors
    enabled: false, // Disabled for now due to Firebase quota limits - will enable when quota resets
  })

  const waybills = (waybillsQuery.data || []) as Waybill[]
  const payments = (paymentsQuery.data || []) as Payment[]
  const initialDebts = (initialDebtsQuery.data || []) as InitialDebt[]
  const firebaseCustomers = (customersQuery.data || []) as Array<{ identification: string; customerName: string; contactInfo?: string }>

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

  // Calculate customer analysis - EXACT logic from legacy project
  const customerAnalysis = React.useMemo((): Record<string, CustomerAnalysis> => {
    const analysis: Record<string, CustomerAnalysis> = {}
    const customerSales = new Map<string, { totalSales: number; waybillCount: number; waybills: Waybill[]; buyerName: string }>()
    const customerPayments = new Map<string, { totalPayments: number; paymentCount: number; payments: any[] }>()
    // Resolve customer name from waybill - prioritize buyerName as it's the actual customer name
    const resolveWaybillCustomerName = (waybill?: Waybill) =>
      waybill?.buyerName || waybill?.customerName || waybill?.sellerName || ''

    // Process sales (waybills after cutoff)
    waybills.forEach(wb => {
      if (!wb.customerId) return
      const afterCutoff = wb.isAfterCutoff ?? (wb as { afterCutoff?: boolean }).afterCutoff
      if (!afterCutoff) return // Only after April 29, 2025

      if (!customerSales.has(wb.customerId)) {
        customerSales.set(wb.customerId, {
          totalSales: 0,
          waybillCount: 0,
          waybills: [],
          buyerName: ''
        })
      }

      const customer = customerSales.get(wb.customerId)!
      customer.totalSales += Number(wb.amount) || 0
      customer.waybillCount += 1
      customer.waybills.push(wb)
      // Save buyerName from waybill if not already set
      if (!customer.buyerName) {
        customer.buyerName = resolveWaybillCustomerName(wb)
      }
    })

    // Process payments (after cutoff date)
    payments.forEach(p => {
      if (!p.customerId) return

      const paymentDate = p.paymentDate || ''
      // Only include payments after April 29, 2025 (>= April 30)
      if (paymentDate < paymentWindowStart) return

      if (!customerPayments.has(p.customerId)) {
        customerPayments.set(p.customerId, {
          totalPayments: 0,
          paymentCount: 0,
          payments: []
        })
      }

      const customer = customerPayments.get(p.customerId)!
      const amount = Number(p.amount) || 0
      customer.totalPayments += amount
      customer.paymentCount += 1
      customer.payments.push({
        customerId: p.customerId,
        payment: amount,
        date: paymentDate,
        isAfterCutoff: true,
        source: p.source || 'unknown',
        uniqueCode: p.uniqueCode,
        paymentId: p.id,
        description: p.description,
        balance: Number(p.balance) || 0
      })
    })

    // Create initial debts map
    const startingDebtsMap = new Map<string, { amount: number; date: string; name: string }>()
    initialDebts.forEach(debt => {
      startingDebtsMap.set(debt.customerId, {
        amount: debt.debt,
        date: debt.date,
        name: debt.customerName
      })
    })

    // Combine all customer IDs
    const allIds = new Set([
      ...customerSales.keys(),
      ...customerPayments.keys(),
      ...Array.from(startingDebtsMap.keys())
    ])

    // Build final analysis for each customer
    allIds.forEach(customerId => {
      const sales = customerSales.get(customerId) || { totalSales: 0, waybillCount: 0, waybills: [], buyerName: '' }
      const pays = customerPayments.get(customerId) || { totalPayments: 0, paymentCount: 0, payments: [] }
      const sd = startingDebtsMap.get(customerId) || { amount: 0, date: null, name: '' }

      // Get customer name from multiple sources (priority: waybill buyerName > starting debt > firebase customers > ID)
      let customerName = customerId
      // First try the saved buyerName from waybills
      if (sales.buyerName) {
        customerName = sales.buyerName
      } else if (sd.name) {
        customerName = sd.name
      } else {
        // Try to find name in Firebase customers collection
        const fbCustomer = firebaseCustomers.find(c => c.identification === customerId)
        if (fbCustomer?.customerName) {
          customerName = fbCustomer.customerName
        }
      }

      // Calculate current debt: startingDebt + totalSales - totalPayments
      const currentDebt = sd.amount + sales.totalSales - pays.totalPayments

      // Separate cash payments
      const cashPayments = pays.payments.filter(p =>
        p.source === 'manual-cash' || p.source === 'cash'
      )
      const totalCashPayments = cashPayments.reduce((sum, p) => sum + p.payment, 0)

      analysis[customerId] = {
        customerId,
        customerName,
        totalSales: sales.totalSales,
        totalPayments: pays.totalPayments,
        totalCashPayments,
        currentDebt,
        startingDebt: sd.amount,
        startingDebtDate: sd.date,
        waybillCount: sales.waybillCount,
        paymentCount: pays.paymentCount,
        waybills: sales.waybills,
        payments: pays.payments,
        cashPayments
      }
    })

    return analysis
  }, [waybills, payments, initialDebts, firebaseCustomers, paymentWindowStart])

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

    // FIXED: Include starting debts in total calculation (same formula as individual customer debt)
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

    // Search filter
    if (searchTerm) {
      customers = customers.filter(c =>
        c.customerId.includes(searchTerm) ||
        c.customerName.toLowerCase().includes(searchTerm.toLowerCase())
      )
    }

    // Sort
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
    const endIndex = startIndex + itemsPerPage
    return sortedCustomers.slice(startIndex, endIndex)
  }, [sortedCustomers, currentPage, itemsPerPage])

  // Reset to page 1 when search term changes
  React.useEffect(() => {
    setCurrentPage(1)
  }, [searchTerm, sortBy, sortOrder])

  // Summary calculations
  const totalExpected = includedTotals.totalSales
  const totalReceived = includedTotals.totalPayments
  const totalCashReceived = includedTotals.totalCashPayments
  const totalOutstanding = includedTotals.totalOutstanding

  // Store total outstanding in query cache for waybills page to use
  React.useEffect(() => {
    queryClient.setQueryData(['totalOutstanding'], totalOutstanding)
  }, [totalOutstanding, queryClient])

  // NEW: Poll aggregation job status
  React.useEffect(() => {
    if (!aggregationJobId || !isPolling) return

    const pollInterval = setInterval(async () => {
      try {
        const job = await paymentsApi.getAggregationJobStatus(aggregationJobId)
        setAggregationJob(job)

        // Stop polling if job completed or failed
        if (job.status === 'COMPLETED' || job.status === 'FAILED') {
          setIsPolling(false)

          if (job.status === 'COMPLETED') {
            setUploadStatus(prev =>
              `${prev} ✅ აგრეგაცია დასრულდა: ${job.result?.updatedCount || 0} განახლებული მომხმარებელი`
            )
            // Refresh data after aggregation completes
            void queryClient.invalidateQueries({ queryKey: ['payments'] })
            void queryClient.invalidateQueries({ queryKey: ['waybills'] })
          } else if (job.status === 'FAILED') {
            setUploadStatus(prev =>
              `${prev} ⚠️ აგრეგაცია ვერ შესრულდა: ${job.errorMessage || 'უცნობი შეცდომა'}`
            )
          }
        }
      } catch (error) {
        console.error('Failed to poll aggregation job:', error)
        // Don't stop polling on network errors, retry
      }
    }, 2000) // Poll every 2 seconds

    return () => clearInterval(pollInterval)
  }, [aggregationJobId, isPolling, queryClient])

  const uploadMutation = useMutation({
    mutationFn: ({ file, bank }: { file: File; bank: 'tbc' | 'bog' }) =>
      paymentsApi.uploadExcel(file, bank),
    onSuccess: (data: any) => {
      const addedCount = data.addedCount || data.newCount || 0
      const duplicateCount = data.duplicateCount || 0

      setUploadStatus(`✅ ${addedCount} ახალი გადახდა, ${duplicateCount} დუბლიკატი. ⏳ აგრეგაცია მიმდინარეობს...`)
      void queryClient.invalidateQueries({ queryKey: ['payments'] })

      // Start polling for aggregation job if jobId is present
      if (data.aggregationJobId) {
        setAggregationJobId(data.aggregationJobId)
        setIsPolling(true)
        setAggregationJob({
          jobId: data.aggregationJobId,
          status: 'PENDING',
          source: 'excel_upload',
          createdAt: new Date().toISOString(),
          progressPercent: 0
        })
      }

      if (fileInputRef.current) {
        fileInputRef.current.value = ''
      }
    },
    onError: (err) => {
      setUploadStatus(`❌ შეცდომა: ${getApiErrorMessage(err)}`)
      setIsPolling(false)
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

  // Clear bank payments mutation
  const clearBankPaymentsMutation = useMutation({
    mutationFn: () => paymentsApi.deleteBankPayments(),
    onSuccess: (data) => {
      setUploadStatus(`✅ წაიშალა ${data.deleted} ბანკის გადახდა`)
      void queryClient.invalidateQueries({ queryKey: ['payments'] })
      void queryClient.invalidateQueries({ queryKey: ['waybills'] })

      // Start polling for aggregation if job was triggered
      if (data.aggregationJobId) {
        setAggregationJobId(data.aggregationJobId)
        setIsPolling(true)
        setAggregationJob({
          jobId: data.aggregationJobId,
          status: 'PENDING',
          source: 'bank_purge',
          createdAt: new Date().toISOString(),
          progressPercent: 0
        })
      }
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

      {/* Outstanding Debt - Mobile Priority */}
      <Card className="p-5 md:p-6 text-center border-2">
        <div className="text-sm text-muted-foreground mb-2">ჯამური ვალი</div>
        {waybillsQuery.isLoading || paymentsQuery.isLoading ? (
          <Skeleton className="h-14 w-44 mx-auto" />
        ) : (
          <div className="text-3xl md:text-4xl font-bold">
            <span className={totalOutstanding > 0 ? 'text-red-600 dark:text-red-500' : 'text-green-600 dark:text-green-500'}>
              {formatCurrency(totalOutstanding)}
            </span>
          </div>
        )}
        <div className="mt-2 text-sm text-muted-foreground">
          გაყიდვები: {waybillsQuery.isLoading ? '…' : formatCurrency(totalExpected)} |
          გადახდები: {paymentsQuery.isLoading ? '…' : formatCurrency(totalReceived)}
        </div>
      </Card>

      {/* Search Bar - Standalone at top */}
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
                <th className="px-4 py-3 text-left text-xs font-medium uppercase">
                  სახელი
                </th>
                <th
                  onClick={() => handleSort('currentDebt')}
                  className="px-4 py-3 text-left text-xs font-medium uppercase cursor-pointer hover:bg-accent"
                >
                  ვალი {sortBy === 'currentDebt' && (sortOrder === 'desc' ? '↓' : '↑')}
                </th>
                <th className="px-4 py-3 text-left text-xs font-medium uppercase">
                  ხელით
                </th>
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
                <th className="px-4 py-3 text-left text-xs font-medium uppercase">
                  ჩართვა
                </th>
                <th className="px-4 py-3 text-left text-xs font-medium uppercase">
                  დეტალები
                </th>
              </tr>
            </thead>
            <tbody className="divide-y">
              {paginatedCustomers.map((customer) => (
                <React.Fragment key={customer.customerId}>
                  <tr className="hover:bg-accent/50">
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
                          {/* Waybills Section */}
                          <div>
                            <h4 className="text-sm font-semibold mb-3">
                              {customer.customerName} - გაყიდვები/შესყიდვები ({customer.waybillCount})
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
                                    <span className="font-medium">რაოდენობა:</span>
                                    <span>{customer.waybillCount}</span>
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
              ))}
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
            {waybillsQuery.isLoading ? <Skeleton className="h-6 w-24" /> : formatCurrency(totalExpected)}
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

          {/* Aggregation Progress Indicator */}
          {aggregationJob && isPolling && (
            <div className="bg-blue-50 dark:bg-blue-900/20 border border-blue-200 rounded-lg p-4">
              <div className="flex items-center justify-between mb-2">
                <div className="font-medium text-blue-900 dark:text-blue-100">
                  🔄 აგრეგაცია მიმდინარეობს...
                </div>
                <div className="text-sm text-blue-700 dark:text-blue-300">
                  {aggregationJob.progressPercent || 0}%
                </div>
              </div>

              {/* Progress bar */}
              <div className="w-full bg-blue-200 dark:bg-blue-800 rounded-full h-2 mb-2">
                <div
                  className="bg-blue-600 h-2 rounded-full transition-all duration-300"
                  style={{ width: `${aggregationJob.progressPercent || 0}%` }}
                />
              </div>

              {/* Current step */}
              {aggregationJob.currentStep && (
                <div className="text-xs text-blue-600 dark:text-blue-400">
                  {aggregationJob.currentStep}
                </div>
              )}
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
            {waybillsQuery.isError && <div>ზედნადებები: {getApiErrorMessage(waybillsQuery.error)}</div>}
            {paymentsQuery.isError && <div>გადახდები: {getApiErrorMessage(paymentsQuery.error)}</div>}
          </div>
        </Card>
      )}
    </div>
  )
}
