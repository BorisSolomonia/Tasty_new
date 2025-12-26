import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { useQuery } from '@tanstack/react-query'
import { addDays } from 'date-fns'
import { waybillsApi, paymentsApi } from '@/lib/api-client'
import { formatCurrency, formatDateISO, getPaymentCutoffDate } from '@/lib/utils'
import { isConfirmedWaybillStatus, sumPaymentAmount, sumWaybillAmount, sumWaybillVat } from '@/lib/erp-calculations'

export function DashboardPage() {
  const paymentWindowStart = formatDateISO(addDays(getPaymentCutoffDate(), 1))

  const waybillsQuery = useQuery({
    queryKey: ['waybills', 'afterCutoff'],
    queryFn: () => waybillsApi.getAll({ afterCutoffOnly: true, type: 'SALE' }),
    staleTime: 1000 * 60 * 30, // Cache for 30 minutes - waybills change infrequently
    gcTime: 1000 * 60 * 60, // Keep in cache for 1 hour
    retry: 1,
  })

  const paymentsQuery = useQuery({
    queryKey: ['payments', 'afterCutoff', paymentWindowStart],
    queryFn: () => paymentsApi.getAll(paymentWindowStart),
    staleTime: 1000 * 60 * 15, // Cache for 15 minutes - payments update more frequently
    gcTime: 1000 * 60 * 30, // Keep in cache for 30 minutes
    retry: 1,
  })

  const waybills = waybillsQuery.data ?? []
  const payments = paymentsQuery.data ?? []

  const afterCutoffWaybills = waybills.length
  const confirmedWaybills = waybills.filter((w) => isConfirmedWaybillStatus(w.status)).length

  const totalSales = sumWaybillAmount(waybills)
  const soldVat = sumWaybillVat(waybills, { positiveOnly: true })
  const totalReceived = sumPaymentAmount(payments, { authorizedOnly: true })

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-xl font-semibold tracking-tight md:text-2xl">Dashboard</h1>
        <p className="text-sm text-muted-foreground">
          Quick overview optimized for mobile, detailed on desktop.
        </p>
      </div>

      <div className="grid grid-cols-2 gap-3 md:grid-cols-4 md:gap-4">
        <Card className="p-3 md:p-4">
          <div className="text-xs text-muted-foreground">Waybills (after cutoff)</div>
          <div className="mt-1 text-lg font-semibold md:text-2xl">
            {waybillsQuery.isLoading ? <Skeleton className="h-7 w-16" /> : afterCutoffWaybills}
          </div>
          <div className="mt-1 text-xs text-muted-foreground">
            Confirmed: {waybillsQuery.isLoading ? '…' : confirmedWaybills}
          </div>
        </Card>
        <Card className="p-3 md:p-4">
          <div className="text-xs text-muted-foreground">Sold VAT</div>
          <div className="mt-1 text-lg font-semibold md:text-2xl">
            {waybillsQuery.isLoading ? <Skeleton className="h-7 w-28" /> : formatCurrency(soldVat)}
          </div>
        </Card>
        <Card className="p-3 md:p-4">
          <div className="text-xs text-muted-foreground">Sales total (after cutoff)</div>
          <div className="mt-1 text-lg font-semibold md:text-2xl">
            {waybillsQuery.isLoading ? <Skeleton className="h-7 w-28" /> : formatCurrency(totalSales)}
          </div>
        </Card>
        <Card className="p-3 md:p-4">
          <div className="text-xs text-muted-foreground">Payments total (after cutoff)</div>
          <div className="mt-1 text-lg font-semibold md:text-2xl">
            {paymentsQuery.isLoading ? <Skeleton className="h-7 w-28" /> : formatCurrency(totalReceived)}
          </div>
        </Card>
      </div>

      <Card className="p-3 md:p-4">
        <div className="text-sm font-medium">Stress Test</div>
        <p className="mt-1 text-sm text-muted-foreground">
          Resize the viewport: the sidebar appears on desktop, bottom navigation appears on mobile.
        </p>
        {(waybillsQuery.isError || paymentsQuery.isError) && (
          <div className="mt-3 rounded-md border border-destructive/30 bg-destructive/10 p-3 text-sm text-destructive">
            Failed to load live stats. Verify backend is running and `VITE_API_URL` points to the API Gateway.
          </div>
        )}
        <div className="mt-4 grid grid-cols-1 gap-3 md:grid-cols-3">
          {Array.from({ length: 9 }).map((_, idx) => (
            <Card key={idx} className="p-3">
              <div className="text-sm font-medium">Item #{idx + 1}</div>
              <div className="mt-1 text-xs text-muted-foreground">
                Tap targets and spacing remain comfortable on mobile.
              </div>
            </Card>
          ))}
        </div>
      </Card>
    </div>
  )
}
