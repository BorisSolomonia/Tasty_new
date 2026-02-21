export type Waybill = {
  id?: string
  waybillId?: string
  type?: 'SALE' | 'PURCHASE'
  customerId?: string
  customerName?: string
  buyerTin?: string
  buyerName?: string
  date?: string
  amount?: number | string | null
  status?: number | string | null
  isAfterCutoff?: boolean
  sellerTin?: string | null
  sellerName?: string | null
}

export type WaybillVatSummary = {
  startDate: string
  endDate: string
  cutoffDate?: string
  soldWaybillCount: number
  purchasedWaybillCount: number
  soldPositiveAmountCount: number
  purchasedPositiveAmountCount: number
  soldGross: number
  purchasedGross: number
  soldVat: number
  purchasedVat: number
  netVat: number
}

export type Payment = {
  id?: string
  uniqueCode?: string | null
  customerId?: string
  customerName?: string | null
  paymentDate?: string
  amount?: number | string | null
  balance?: number | string | null
  description?: string | null
  source?: string | null
  isAfterCutoff?: boolean
  uploadedAt?: string | null
  excelRowIndex?: number | null
}

export type PaymentStatus = {
  customerId: string
  lastPaymentDate: string
  daysSinceLastPayment: number
  statusColor: 'none' | 'yellow' | 'red'
}

export type InitialDebt = {
  customerId: string
  customerName: string
  debt: number
  date: string
}

export type CustomerAnalysis = {
  customerId: string
  customerName: string
  totalSales: number
  totalPayments: number
  totalCashPayments: number
  currentDebt: number
  startingDebt: number
  startingDebtDate: string | null
  waybillCount: number
  paymentCount: number
  waybills: Waybill[]
  payments: Array<{
    customerId: string
    payment: number
    date: string
    isAfterCutoff: boolean
    source: string
    uniqueCode?: string
    paymentId?: string
    description?: string
    balance?: number
  }>
  cashPayments: Array<{
    customerId: string
    payment: number
    date: string
    isAfterCutoff: boolean
    source: string
    paymentId?: string
  }>
}

export type ProductSales = {
  customerId: string
  customerName: string
  beefKg: number
  porkKg: number
  totalKg: number
  beefProductsFound?: string[]
  porkProductsFound?: string[]
}

export type CustomerEntry = {
  id: string
  name: string
}

export type AggregationJob = {
  jobId: string
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  source: string
  currentStep?: string
  progressPercent?: number
  createdAt: string
  startedAt?: string
  completedAt?: string
  result?: {
    totalCustomers: number
    newCount: number
    updatedCount: number
    unchangedCount: number
    durationMs: number
  }
  errorMessage?: string
  errorDetails?: string
}
