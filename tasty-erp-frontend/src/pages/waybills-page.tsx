import * as React from 'react'
import { Link } from '@tanstack/react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { startOfMonth, addDays } from 'date-fns'
import { ApiError, waybillsApi, paymentsApi } from '@/lib/api-client'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { Button } from '@/components/ui/button'
import { formatCurrency, formatDate, formatDateISO, getPaymentCutoffDate } from '@/lib/utils'
import { sumPaymentAmount, sumWaybillAmount } from '@/lib/erp-calculations'

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

export function WaybillsPage() {
  const queryClient = useQueryClient()

  const [startDate, setStartDate] = React.useState<string>(formatDateISO(startOfMonth(new Date())))
  const [endDate, setEndDate] = React.useState<string>(formatDateISO(new Date()))
  const [syncMessage, setSyncMessage] = React.useState<string>('')

  // DEPRECATED: Waybill list queries disabled - they used deprecated Firebase endpoints
  // NEW ARCHITECTURE: Waybills are not stored in Firebase, only fetched on-demand from RS.ge
  // Use VAT summary endpoint instead for calculations

  const vatSummaryQuery = useQuery({
    queryKey: ['waybills', 'vat', startDate, endDate],
    queryFn: async () => {
      console.log('🔍 Fetching VAT summary:', { startDate, endDate })
      const result = await waybillsApi.getVatSummary({ startDate, endDate })
      console.log('✅ VAT summary received:', result)
      return result
    },
    staleTime: 1000 * 60 * 10, // Cache for 10 minutes - calculated data
    gcTime: 1000 * 60 * 30, // Keep in cache for 30 minutes
    retry: 1,
  })
  const vatSummary = vatSummaryQuery.data

  // Debug logging
  React.useEffect(() => {
    console.log('📊 VAT Summary State:', {
      isLoading: vatSummaryQuery.isLoading,
      isError: vatSummaryQuery.isError,
      error: vatSummaryQuery.error,
      data: vatSummary,
      netVat: vatSummary?.netVat,
    })
  }, [vatSummary, vatSummaryQuery.isLoading, vatSummaryQuery.isError, vatSummaryQuery.error])

  // Debt calculation: Total sales after cutoff - Total payments after cutoff
  const paymentWindowStart = formatDateISO(addDays(getPaymentCutoffDate(), 1))

  const afterCutoffSalesQuery = useQuery({
    queryKey: ['waybills', 'afterCutoff'],
    queryFn: () => waybillsApi.getAll({ afterCutoffOnly: true, type: 'SALE' }),
    staleTime: 1000 * 60 * 30, // Cache for 30 minutes - waybills change infrequently
    gcTime: 1000 * 60 * 60, // Keep in cache for 1 hour
    retry: 1,
  })

  const afterCutoffPaymentsQuery = useQuery({
    queryKey: ['payments', 'afterCutoff', paymentWindowStart],
    queryFn: () => paymentsApi.getAll(paymentWindowStart),
    staleTime: 1000 * 60 * 15, // Cache for 15 minutes - payments update more frequently
    gcTime: 1000 * 60 * 30, // Keep in cache for 30 minutes
    retry: 1,
  })

  const afterCutoffWaybills = afterCutoffSalesQuery.data ?? []
  const afterCutoffPayments = afterCutoffPaymentsQuery.data ?? []

  const totalSales = sumWaybillAmount(afterCutoffWaybills)
  const totalPayments = sumPaymentAmount(afterCutoffPayments, { authorizedOnly: true })
  const netDebt = totalSales - totalPayments

  const syncSalesMutation = useMutation({
    mutationFn: () => waybillsApi.fetch(startDate, endDate),
    onSuccess: (res) => {
      setSyncMessage(res.message ?? `Fetched ${res.totalCount} sales waybills`)
      void queryClient.invalidateQueries({ queryKey: ['waybills', 'sale'] })
      void queryClient.invalidateQueries({ queryKey: ['waybills', 'vat'] })
    },
    onError: (err) => setSyncMessage(getApiErrorMessage(err)),
  })

  const syncPurchasesMutation = useMutation({
    mutationFn: () => waybillsApi.fetchPurchases(startDate, endDate),
    onSuccess: (res) => {
      setSyncMessage(res.message ?? `Fetched ${res.totalCount} purchase waybills`)
      void queryClient.invalidateQueries({ queryKey: ['waybills', 'purchase'] })
      void queryClient.invalidateQueries({ queryKey: ['waybills', 'vat'] })
    },
    onError: (err) => setSyncMessage(getApiErrorMessage(err)),
  })

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-xl font-semibold tracking-tight md:text-2xl">Waybills</h1>
        <p className="text-sm text-muted-foreground">
          Sold vs purchased VAT using legacy RS.ge rules (backend-calculated).
        </p>
      </div>

      {/* Date inputs - Top priority for mobile */}
      <Card className="p-3 md:p-4">
        <div className="text-sm font-medium mb-3">VAT Period</div>
        <div className="grid grid-cols-2 gap-2 md:flex md:gap-3">
          <label className="grid gap-1 text-xs text-muted-foreground">
            Start
            <input
              className="h-10 rounded-md border border-input bg-background px-3 text-sm"
              type="date"
              value={startDate}
              onChange={(e) => setStartDate(e.target.value)}
            />
          </label>
          <label className="grid gap-1 text-xs text-muted-foreground">
            End
            <input
              className="h-10 rounded-md border border-input bg-background px-3 text-sm"
              type="date"
              value={endDate}
              onChange={(e) => setEndDate(e.target.value)}
            />
          </label>
        </div>
      </Card>

      {/* Net VAT - Large, prominent display for mobile */}
      <Card className="p-6 md:p-8 text-center">
        <div className="text-sm text-muted-foreground mb-2">Net VAT</div>
        {vatSummaryQuery.isLoading ? (
          <Skeleton className="h-16 w-48 mx-auto" />
        ) : (
          <div className="text-4xl md:text-5xl font-bold">
            <span className={(vatSummary?.netVat ?? 0) >= 0 ? 'text-green-600 dark:text-green-500' : 'text-red-600 dark:text-red-500'}>
              {formatCurrency(vatSummary?.netVat ?? 0)}
            </span>
          </div>
        )}
        <div className="mt-2 text-sm text-muted-foreground font-medium">
          {vatSummaryQuery.isLoading ? '…' : (vatSummary?.netVat ?? 0) >= 0 ? 'VAT to pay' : 'VAT refund'}
        </div>
      </Card>

      {/* Net Debt and Payments Button */}
      <Card className="p-4 md:p-5">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex-1">
            <div className="text-xs text-muted-foreground">Net Debt (Total Outstanding)</div>
            <div className="mt-1 text-2xl font-semibold md:text-3xl">
              {afterCutoffSalesQuery.isLoading || afterCutoffPaymentsQuery.isLoading ? (
                <Skeleton className="h-9 w-36" />
              ) : (
                <span className={netDebt >= 0 ? 'text-red-600 dark:text-red-500' : 'text-green-600 dark:text-green-500'}>
                  {formatCurrency(netDebt)}
                </span>
              )}
            </div>
            <div className="mt-1 text-xs text-muted-foreground">
              Sales after cutoff: {afterCutoffSalesQuery.isLoading ? '…' : formatCurrency(totalSales)} |
              Payments: {afterCutoffPaymentsQuery.isLoading ? '…' : formatCurrency(totalPayments)}
            </div>
          </div>
          <Link to="/payments">
            <Button size="lg" className="w-full sm:w-auto">
              View Payments
            </Button>
          </Link>
        </div>
      </Card>

      {/* Sync buttons */}
      <Card className="p-3 md:p-4">
        <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex gap-2">
            <Button
              onClick={() => syncSalesMutation.mutate()}
              disabled={syncSalesMutation.isPending || syncPurchasesMutation.isPending}
              className="flex-1 sm:flex-none"
            >
              {syncSalesMutation.isPending ? 'Syncing sales…' : 'Sync Sales'}
            </Button>
            <Button
              variant="outline"
              onClick={() => syncPurchasesMutation.mutate()}
              disabled={syncSalesMutation.isPending || syncPurchasesMutation.isPending}
              className="flex-1 sm:flex-none"
            >
              {syncPurchasesMutation.isPending ? 'Syncing purchases…' : 'Sync Purchases'}
            </Button>
          </div>
          {syncMessage && (
            <div className="text-xs text-muted-foreground">{syncMessage}</div>
          )}
        </div>
      </Card>

      {/* VAT breakdown - Sold and Purchased */}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 md:gap-4">
        <Card className="p-3 md:p-4">
          <div className="text-xs text-muted-foreground">Sold VAT</div>
          <div className="mt-1 text-2xl font-semibold md:text-3xl">
            {vatSummaryQuery.isLoading ? <Skeleton className="h-9 w-32" /> : formatCurrency(vatSummary?.soldVat ?? 0)}
          </div>
          <div className="mt-2 text-xs text-muted-foreground">
            Gross: {vatSummaryQuery.isLoading ? '…' : formatCurrency(vatSummary?.soldGross ?? 0)}
          </div>
        </Card>
        <Card className="p-3 md:p-4">
          <div className="text-xs text-muted-foreground">Purchased VAT</div>
          <div className="mt-1 text-2xl font-semibold md:text-3xl">
            {vatSummaryQuery.isLoading ? <Skeleton className="h-9 w-32" /> : formatCurrency(vatSummary?.purchasedVat ?? 0)}
          </div>
          <div className="mt-2 text-xs text-muted-foreground">
            Gross: {vatSummaryQuery.isLoading ? '…' : formatCurrency(vatSummary?.purchasedGross ?? 0)}
          </div>
        </Card>
      </div>

      <div className="grid grid-cols-2 gap-3 md:grid-cols-4 md:gap-4">
        <Card className="p-3 md:p-4">
          <div className="text-xs text-muted-foreground">Sales waybills</div>
          <div className="mt-1 text-lg font-semibold md:text-2xl">
            {vatSummaryQuery.isLoading ? <Skeleton className="h-7 w-16" /> : vatSummary?.soldWaybillCount ?? 0}
          </div>
          <div className="mt-1 text-xs text-muted-foreground">
            Amount &gt; 0: {vatSummaryQuery.isLoading ? '…' : vatSummary?.soldPositiveAmountCount ?? 0}
          </div>
        </Card>
        <Card className="p-3 md:p-4">
          <div className="text-xs text-muted-foreground">Sold gross (VAT base)</div>
          <div className="mt-1 text-lg font-semibold md:text-2xl">
            {vatSummaryQuery.isLoading ? <Skeleton className="h-7 w-28" /> : formatCurrency(vatSummary?.soldGross ?? 0)}
          </div>
        </Card>
        <Card className="p-3 md:p-4">
          <div className="text-xs text-muted-foreground">Purchased gross (VAT base)</div>
          <div className="mt-1 text-lg font-semibold md:text-2xl">
            {vatSummaryQuery.isLoading ? <Skeleton className="h-7 w-28" /> : formatCurrency(vatSummary?.purchasedGross ?? 0)}
          </div>
          <div className="mt-1 text-xs text-muted-foreground">
            Amount &gt; 0: {vatSummaryQuery.isLoading ? '…' : vatSummary?.purchasedPositiveAmountCount ?? 0}
          </div>
        </Card>
        <Card className="p-3 md:p-4">
          <div className="text-xs text-muted-foreground">Cutoff</div>
          <div className="mt-1 text-lg font-semibold md:text-2xl">
            {vatSummaryQuery.isLoading ? <Skeleton className="h-7 w-24" /> : formatDate(vatSummary?.cutoffDate ?? '')}
          </div>
          <div className="mt-1 text-xs text-muted-foreground">Used by debt logic (backend).</div>
        </Card>
      </div>

      {vatSummaryQuery.isError && (
        <Card className="border-destructive/30 bg-destructive/10 p-3 text-sm text-destructive md:p-4">
          <div className="font-medium">Failed to load VAT data</div>
          <div className="mt-1 text-xs text-destructive/90">
            Ensure backend is reachable via `VITE_API_URL`.
          </div>
          <div className="mt-2 text-xs">
            VAT summary: {getApiErrorMessage(vatSummaryQuery.error)}
          </div>
        </Card>
      )}

      <Card className="border-blue-200 bg-blue-50 p-3 text-sm md:p-4">
        <div className="font-medium text-blue-900">New Architecture: On-Demand Waybill Fetching</div>
        <div className="mt-2 text-xs text-blue-800">
          Waybills are no longer stored in Firebase to avoid quota limits. They are fetched fresh from RS.ge on-demand.
          The VAT summary above is calculated in real-time from RS.ge data.
        </div>
      </Card>
    </div>
  )
}
