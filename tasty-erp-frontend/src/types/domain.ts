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

export type CustomerSalesTotals = {
  customerId: string
  customerName: string
  totalSales: number
  saleCount: number
  lastSaleDate: string | null
}

// ==================== Audit Control (BOR-74) ====================

export type DailyLedgerRow = {
  date: string
  startingInventoryKg: number
  purchasedKg: number
  soldKg: number
  writeOffKg: number
  endingInventoryKg: number
  writeOffPercent: number
  overage: boolean
}

export type InventoryLedger = {
  parentCategory: string
  childProducts: string[]
  openingStockKg: number
  totalPurchasedKg: number
  totalSoldKg: number
  totalWriteOffKg: number
  endingInventoryKg: number
  overageDays: number
  dailyRows: DailyLedgerRow[]
}

export type RealTotals = {
  realTotalSales: number
  realTotalPurchases: number
  excludedSales: number
  excludedPurchases: number
  realEntityCount: number
  excludedEntityCount: number
}

export type ReconciliationRow = {
  customerId: string
  customerName: string
  realEntity: boolean
  totalSales: number
  totalPayments: number
  currentDebt: number
  realDebt: number
  exceptionDebt: number
  manuallyMarkedPaid: boolean
}

export type TargetedExpense = {
  targetId: string
  totalExpense: number
  matchCount: number
  matches: Array<{
    paymentId?: string
    source?: string
    amount: number
    date?: string
    description?: string
    matchedOnDescription: boolean
  }>
}

export type AuditException = {
  id?: string
  type: string
  description?: string
  referenceId?: string
  customerId?: string
  amount?: number
  date?: string
  status?: string
  manual?: boolean
  createdAt?: string
  createdBy?: string
}

export type AuditDashboard = {
  startDate: string
  endDate: string
  productFilter?: string | null
  realTotals: RealTotals
  inventoryLedgers: InventoryLedger[]
  reconciliation: ReconciliationRow[]
  realDebtTotal: number
  exceptionDebtTotal: number
  targetedExpense: TargetedExpense
  exceptions: AuditException[]
}

export type CustomerDebt = {
  customerId: string
  customerName: string
  startingDebt: number
  totalSales: number
  totalPayments: number
  totalCashPayments: number
  currentDebt: number
  waybillCount: number
  paymentCount: number
  excluded: boolean
}

export type DebtOverview = {
  customers: CustomerDebt[]
  totalSales: number
  totalPayments: number
  totalCashPayments: number
  totalOutstanding: number
}

export type ProductCategoryCode = 'BEEF' | 'PORK' | 'FAT' | 'OTHER'

export type ProductCategory = {
  name: string
  category: ProductCategoryCode
}

export type ProductCatalogRow = {
  name: string
  category: ProductCategoryCode
  overridden: boolean
}

export type ProductCatalog = {
  purchased: ProductCatalogRow[]
  sold: ProductCatalogRow[]
}

