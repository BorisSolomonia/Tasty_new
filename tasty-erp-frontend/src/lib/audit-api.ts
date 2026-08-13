/**
 * BOR-89 "Audit Control" layer — typed client for `/api/audit-control/layer`.
 *
 * Every interface below mirrors the Java DTO of the same name in
 * `common/src/main/java/ge/tastyerp/common/dto/auditlayer/` field-for-field.
 *
 * Two conventions the UI depends on:
 *
 * 1. Every `BigDecimal` is typed `number | null`. The backend may legitimately
 *    have nothing to say about a figure, and in an audit an absent number and a
 *    zero are different claims. Rendering goes through `formatters` in
 *    `@/components/audit/format`, which prints an em dash for null/undefined —
 *    never a fabricated 0.
 * 2. A drill-down key is the dotted path of the aggregate it expands, e.g.
 *    `cash.unresolvedWithdrawalAmount` or `inventory.gapKg`. Alerts carry their
 *    own authoritative `drilldownKey`; metric cards derive theirs from the DTO
 *    field path they display.
 */
import { fetchWithAuth, jsonData } from '@/lib/api-client'

const BASE = '/audit-control/layer'

// ---------------------------------------------------------------------------
// Enums
// ---------------------------------------------------------------------------

export type AuditSourceType = 'BANK' | 'RS_GE' | 'CHECK' | 'MANUAL'

export type AuditMappingStatus =
  | 'UNMAPPED'
  | 'SUGGESTED'
  | 'AUTO_MAPPED'
  | 'PARTIALLY_MAPPED'
  | 'MANUALLY_MAPPED'
  | 'OVERRIDDEN'
  | 'VOIDED'

export const AUDIT_MAPPING_STATUSES: AuditMappingStatus[] = [
  'UNMAPPED',
  'SUGGESTED',
  'AUTO_MAPPED',
  'PARTIALLY_MAPPED',
  'MANUALLY_MAPPED',
  'OVERRIDDEN',
  'VOIDED',
]

/** Flow discriminator carried by alerts. */
export type AuditFlowName = 'INVENTORY' | 'CASH' | 'DOCUMENTATION'

export type AuditSeverity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW'

// ---------------------------------------------------------------------------
// AuditAlertDto
// ---------------------------------------------------------------------------

export interface AuditAlert {
  ruleId: string | null
  severity: string | null
  /** INVENTORY | CASH | DOCUMENTATION */
  flow: string | null
  title: string | null
  /** The formula in words, e.g. "settlement + debt > documented purchases". */
  formula: string | null
  /** Named inputs the formula was evaluated on. */
  inputs: Record<string, number> | null
  amount: number | null
  quantityKg: number | null
  affectedRowCount: number
  drilldownKey: string | null
  subjects: string[] | null
}

// ---------------------------------------------------------------------------
// AuditCategoryDto
// ---------------------------------------------------------------------------

export interface AuditCategory {
  code: string
  label: string | null
  builtIn: boolean
  supplierSettlement: boolean
  customerReceipt: boolean
  nonSupplierExpense: boolean
  cashReturn: boolean
  paperOnly: boolean
  unresolved: boolean
  description: string | null
}

// ---------------------------------------------------------------------------
// AuditChangeLogDto
// ---------------------------------------------------------------------------

export interface AuditChangeLog {
  id: string | null
  entityType: string | null
  entityId: string | null
  field: string | null
  oldValue: string | null
  newValue: string | null
  /** Self-declared operator name (decision D-4), not an authenticated user. */
  changedBy: string | null
  changedAt: string | null
  reason: string | null
}

// ---------------------------------------------------------------------------
// AuditMappingSplitDto / AuditMappingDto
// ---------------------------------------------------------------------------

export interface AuditMappingSplit {
  categoryCode: string | null
  counterpartyName: string | null
  counterpartyTin: string | null
  productName: string | null
  /** Always positive. */
  amount: number | null
  quantityKg: number | null
  note: string | null
}

export interface AuditMapping {
  id: string | null
  sourceType: AuditSourceType | null
  sourceRowId: string | null
  sourceAmount: number | null
  status: AuditMappingStatus | null
  splits: AuditMappingSplit[] | null
  /** Derived server-side: sourceAmount − Σ split amounts. */
  unresolvedAmount: number | null
  linkedSourceRows: string[] | null
  suggestionReason: string | null
  confidence: number | null
  note: string | null
  createdBy: string | null
  updatedBy: string | null
  createdAt: string | null
  updatedAt: string | null
}

// ---------------------------------------------------------------------------
// AuditSourceRowDto
// ---------------------------------------------------------------------------

export interface AuditSourceRow {
  sourceType: AuditSourceType | null
  sourceRowId: string | null
  date: string | null
  /** CREDIT / DEBIT for bank rows; PURCHASE / SALE for documents. */
  direction: string | null
  amount: number | null
  quantityKg: number | null
  productName: string | null
  counterpartyName: string | null
  counterpartyTin: string | null
  /** Raw description exactly as imported. Never normalised, never translated. */
  description: string | null
  additionalInformation: string | null
  reference: string | null
  status: AuditMappingStatus | null
  mapping: AuditMapping | null
  unresolvedAmount: number | null
  history: AuditChangeLog[] | null
}

// ---------------------------------------------------------------------------
// AuditSourceRowPageDto
// ---------------------------------------------------------------------------

/**
 * A capped page of source rows, plus the truth about what was left out.
 *
 * The bare list this replaced was dangerous: asking for 2023-01-01 → today
 * returned 1,000 rows covering only the most recent three weeks, and nothing in
 * the response said so. These fields are authoritative — the UI reports them
 * rather than inferring coverage from the rows it happens to hold.
 */
export interface AuditSourceRowPage {
  rows: AuditSourceRow[] | null
  /** How many rows matched the filter in the requested period. */
  totalCount: number
  /** How many are in `rows`. */
  returnedCount: number
  truncated: boolean
  /** Earliest date actually present in `rows`. Null when empty. */
  coverageFrom: string | null
  coverageTo: string | null
  /** The period that was asked for, so the UI can contrast it with coverage. */
  requestedFrom: string | null
  requestedTo: string | null
}

// ---------------------------------------------------------------------------
// AuditDrilldownDto
// ---------------------------------------------------------------------------

export interface AuditDrilldown {
  drilldownKey: string | null
  label: string | null
  definition: string | null
  /** Σ of the rows below — recomputed from rows, not copied from the headline. */
  total: number | null
  totalQuantityKg: number | null
  rowCount: number
  rows: AuditSourceRow[] | null
  truncated: boolean
}

// ---------------------------------------------------------------------------
// AuditFlowsDto — the single canonical payload all eight views render
// ---------------------------------------------------------------------------

export interface AuditProductRow {
  productName: string | null
  category: string | null
  purchaseKg: number | null
  saleKg: number | null
  writeOffKg: number | null
  /** Write-off rate actually applied, as a whole percentage. */
  writeOffPercent: number | null
  documentStockKg: number | null
  realStockKg: number | null
  gapKg: number | null
  /** True when real stock was explicitly confirmed rather than assumed 0. */
  realStockConfirmed: boolean
  relatedCashGap: number | null
  flaggedDocumentCount: number
}

export interface AuditInventoryFlow {
  products: AuditProductRow[] | null
  documentPurchaseKg: number | null
  documentSaleKg: number | null
  documentWriteOffKg: number | null
  documentStockKg: number | null
  realStockKg: number | null
  gapKg: number | null
  positiveGapProducts: number
  negativeGapProducts: number
}

export interface AuditCashFlow {
  // what the bank actually did
  bankInflow: number | null
  bankOutflow: number | null
  cashWithdrawals: number | null
  mappedWithdrawalAmount: number | null
  unresolvedWithdrawalAmount: number | null

  // where money went
  directBankSupplierPayments: number | null
  supplierAllocatedCashSettlements: number | null
  customerBankReceipts: number | null
  realCustomerCashReceipts: number | null
  otherIncome: number | null
  nonSupplierExpenses: number | null
  refundsAndReversals: number | null
  cashReturnedOrRedeposited: number | null

  // evidence, which is not money
  checksOnHand: number | null
  checksSupportedByRealMoney: number | null
  unsupportedChecks: number | null
  unclassifiedCheckCount: number

  // paper cash bridge
  onPaperSalesValue: number | null
  onPaperCustomerReceiptValue: number | null
  realMoneyReceivedAgainstPaperSales: number | null
  paperCashCreated: number | null
  onPaperSupplierPaymentValue: number | null
  realMoneySupportingSupplierPayments: number | null
  paperCashReduced: number | null
  netUnexplainedPaperCash: number | null

  // supplier purchase coverage control (§4B)
  realOutstandingSupplierDebt: number | null
  supplierSettlementAndDebt: number | null
  documentedSupplierPurchases: number | null
  excessOverDocumentedPurchases: number | null
  uncoveredPurchaseBalance: number | null
  coverageBreach: boolean
  coverageBreachSubjects: string[] | null

  // what nobody has classified yet
  unmappedInflowCount: number
  unmappedInflowAmount: number | null
  unmappedOutflowCount: number
  unmappedOutflowAmount: number | null

  bankRowCount: number
}

export interface AuditDocumentationFlow {
  documentPurchaseValue: number | null
  documentPurchaseKg: number | null
  documentSalesValue: number | null
  documentSalesKg: number | null
  writeOffKg: number | null

  paperOnlySalesValue: number | null
  paperOnlySalesCount: number
  paperOnlyCustomerPaymentValue: number | null
  paperOnlyCustomerPaymentCount: number
  unsupportedSupplierDocumentValue: number | null
  unsupportedSupplierDocumentCount: number

  unmappedDocumentRowCount: number
  unmappedDocumentValue: number | null

  flaggedDocumentCount: number
  flaggedDocumentValue: number | null

  documentRowCount: number
}

export interface AuditFlows {
  startDate: string | null
  endDate: string | null
  inventory: AuditInventoryFlow | null
  cash: AuditCashFlow | null
  documentation: AuditDocumentationFlow | null
  alerts: AuditAlert[] | null
  /**
   * Non-fatal notes about data availability — e.g. that no bank outflows have
   * been imported yet. Rendered at the top of every view so an empty number is
   * never mistaken for a clean number.
   */
  dataWarnings: string[] | null
}

// ---------------------------------------------------------------------------
// Manual inputs
// ---------------------------------------------------------------------------

export interface RealInventoryOverride {
  /** Firestore document id: `product|asOfDate`. Null when creating. */
  id: string | null
  productName: string | null
  asOfDate: string | null
  realKg: number | null
  note: string | null
  updatedBy: string | null
  updatedAt: string | null
}

export interface RealSupplierDebt {
  /** Supplier TIN when known, else the name. */
  id: string | null
  supplierName: string | null
  supplierTin: string | null
  outstandingAmount: number | null
  note: string | null
  updatedBy: string | null
  updatedAt: string | null
}

export interface CheckEvidence {
  id: string | null
  documentNumber: string | null
  counterpartyName: string | null
  counterpartyTin: string | null
  date: string | null
  amount: number | null
  /** Derived from linked mappings — never typed in directly. */
  supportedAmount: number | null
  unsupportedAmount: number | null
  explanation: string | null
  classified: boolean
  note: string | null
  updatedBy: string | null
  updatedAt: string | null
}

// ---------------------------------------------------------------------------
// Query parameter shapes
// ---------------------------------------------------------------------------

export interface AuditPeriodParams {
  startDate: string
  endDate: string
  /** Standardised product name, or undefined for all products. */
  product?: string
}

export interface AuditSourceRowParams {
  startDate: string
  endDate: string
  sourceType?: AuditSourceType
  status?: AuditMappingStatus
  search?: string
  limit?: number
}

export interface AuditDrilldownParams {
  key: string
  startDate: string
  endDate: string
  /** Narrows the expansion to one product or counterparty. */
  subject?: string
}

// ---------------------------------------------------------------------------
// Client
// ---------------------------------------------------------------------------

export const auditLayerApi = {
  /** The one canonical payload. All eight UX variants render this object. */
  getFlows: async (params: AuditPeriodParams) => {
    const response = await fetchWithAuth(`${BASE}/flows`, { params: { ...params } })
    return jsonData<AuditFlows>(response)
  },

  /** Returns a page, not a bare list: the cap and its coverage are part of the answer. */
  getSourceRows: async (params: AuditSourceRowParams) => {
    const response = await fetchWithAuth(`${BASE}/source-rows`, { params: { ...params } })
    return jsonData<AuditSourceRowPage>(response)
  },

  getDrilldown: async (params: AuditDrilldownParams) => {
    const response = await fetchWithAuth(`${BASE}/drilldown`, { params: { ...params } })
    return jsonData<AuditDrilldown>(response)
  },

  getCategories: async () => {
    const response = await fetchWithAuth(`${BASE}/categories`)
    return jsonData<AuditCategory[]>(response)
  },

  createCategory: async (category: AuditCategory, operator: string) => {
    const response = await fetchWithAuth(`${BASE}/categories`, {
      method: 'POST',
      params: { operator },
      body: JSON.stringify(category),
    })
    return jsonData<AuditCategory>(response)
  },

  deleteCategory: async (code: string, operator: string) => {
    await fetchWithAuth(`${BASE}/categories/${encodeURIComponent(code)}`, {
      method: 'DELETE',
      params: { operator },
    })
  },

  /** Creates or updates the mapping for one immutable source row. */
  saveMapping: async (mapping: AuditMapping, operator: string) => {
    const response = await fetchWithAuth(`${BASE}/mappings`, {
      method: 'PUT',
      params: { operator },
      body: JSON.stringify(mapping),
    })
    return jsonData<AuditMapping>(response)
  },

  /** Voids a mapping. History is retained — the change log is append-only. */
  deleteMapping: async (id: string, operator: string, reason: string) => {
    await fetchWithAuth(`${BASE}/mappings/${encodeURIComponent(id)}`, {
      method: 'DELETE',
      params: { operator, reason },
    })
  },

  getMappingHistory: async (sourceType: AuditSourceType, sourceRowId: string) => {
    const response = await fetchWithAuth(
      `${BASE}/mappings/${encodeURIComponent(sourceType)}/${encodeURIComponent(sourceRowId)}/history`
    )
    return jsonData<AuditChangeLog[]>(response)
  },

  getRealInventory: async (asOfDate: string) => {
    const response = await fetchWithAuth(`${BASE}/real-inventory`, { params: { asOfDate } })
    return jsonData<RealInventoryOverride[]>(response)
  },

  saveRealInventory: async (override: RealInventoryOverride, operator: string) => {
    const response = await fetchWithAuth(`${BASE}/real-inventory`, {
      method: 'PUT',
      params: { operator },
      body: JSON.stringify(override),
    })
    return jsonData<RealInventoryOverride>(response)
  },

  getSupplierDebt: async () => {
    const response = await fetchWithAuth(`${BASE}/supplier-debt`)
    return jsonData<RealSupplierDebt[]>(response)
  },

  saveSupplierDebt: async (debt: RealSupplierDebt, operator: string) => {
    const response = await fetchWithAuth(`${BASE}/supplier-debt`, {
      method: 'PUT',
      params: { operator },
      body: JSON.stringify(debt),
    })
    return jsonData<RealSupplierDebt>(response)
  },

  getCheckEvidence: async (params: { startDate: string; endDate: string }) => {
    const response = await fetchWithAuth(`${BASE}/check-evidence`, { params: { ...params } })
    return jsonData<CheckEvidence[]>(response)
  },

  saveCheckEvidence: async (evidence: CheckEvidence, operator: string) => {
    const response = await fetchWithAuth(`${BASE}/check-evidence`, {
      method: 'PUT',
      params: { operator },
      body: JSON.stringify(evidence),
    })
    return jsonData<CheckEvidence>(response)
  },

  deleteCheckEvidence: async (id: string, operator: string) => {
    await fetchWithAuth(`${BASE}/check-evidence/${encodeURIComponent(id)}`, {
      method: 'DELETE',
      params: { operator },
    })
  },

  getChangeLog: async (params: { entityId?: string; limit?: number }) => {
    const response = await fetchWithAuth(`${BASE}/change-log`, { params: { ...params } })
    return jsonData<AuditChangeLog[]>(response)
  },
}
