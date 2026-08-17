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
  /** The money left the bank as cash (BOR-92 v3): feeds the statement's withdrawals line. */
  cashWithdrawal?: boolean
  paperOnly: boolean
  unresolved: boolean
  description: string | null
}

// ---------------------------------------------------------------------------
// AuditSubgroupDto (BOR-92 level 2)
// ---------------------------------------------------------------------------

export interface AuditSubgroup {
  code: string
  label: string | null
  description: string | null
  builtIn: boolean
}

export const SUBGROUP_NONE = 'NONE'

// ---------------------------------------------------------------------------
// AuditStatementDto (BOR-92 v2: the statement at the top of /audit)
// ---------------------------------------------------------------------------

export type StatementRowKey =
  | 'purchases'
  | 'bankPaymentsToSuppliers'
  | 'cashOutflow'
  | 'sales'
  | 'bankInflow'
  | 'cashInflow'

export interface StatementSelection {
  suppliers: string[]
  customers: string[]
}

export interface StatementFigure {
  label: string
  amount: number | null
}

export interface StatementParty {
  /** TIN, or `name:<label>` for a counterparty the source gave no TIN for (never choosable). */
  tin: string
  name: string
  amount: number | null
  quantityKg: number | null
  /** Row-specific: unmapped GEL for cash outflow. */
  secondary: number | null
  /** Bank rows: money on this party's own rows / attributed to it from other rows. */
  directAmount: number | null
  directCount: number
  mappedAmount: number | null
  mappedCount: number
  /** Purchases: bank money mapped to this supplier, and purchases minus that. */
  bankPaid: number | null
  unpaidAfterBank: number | null
  rowCount: number
  chosen: boolean
  unreal: boolean
  identityBasis: string | null
}

export interface StatementProductGroup {
  category: string
  amount: number | null
  quantityKg: number | null
  chosenAmount: number | null
  chosenKg: number | null
  rowCount: number
  productCount: number
}

export interface StatementRow {
  key: StatementRowKey
  title: string
  definition: string
  chosenBy: 'SUPPLIERS' | 'CUSTOMERS'
  total: number | null
  totalKg: number | null
  chosen: number | null
  chosenKg: number | null
  secondary: number | null
  secondaryLabel: string | null
  extras: StatementFigure[] | null
  /** Real coverage of the data behind the figure — may end before the period does. */
  firstDate: string | null
  lastDate: string | null
  rowCount: number
  parties: StatementParty[]
  products: StatementProductGroup[] | null
}

export interface StatementSummary {
  purchases: number | null
  bankPaymentsToSuppliers: number | null
  possibleChecksNeeded: number | null
  withdrawals: number | null
  withdrawalsToSuppliers: number | null
  withdrawalsUnresolved: number | null
  /** Withdrawals in a group that is neither supplier settlement nor unresolved — cash gone elsewhere, documented as such. */
  withdrawalsUndocumented: number | null
  sales: number | null
  bankReceiptsFromCustomers: number | null
  receivables: number | null
  cashToReceiveFromCustomers: number | null
  cashToPaySuppliers: number | null
}

export interface StatementSupplierKg {
  tin: string | null
  name: string | null
  quantityKg: number | null
  lastPurchaseDate: string | null
}

export interface StatementLevel {
  category: string
  purchasedKg: number | null
  purchasedAmount: number | null
  writeOffPercent: number | null
  writeOffKg: number | null
  soldKg: number | null
  soldAmount: number | null
  netKg: number | null
  avgPurchasePricePerKg: number | null
  value: number | null
  stockBySupplier: StatementSupplierKg[]
}

export interface StatementInventoryRow {
  key: 'inventory'
  title: string
  definition: string
  totalKg: number | null
  totalValue: number | null
  unpricedCategories: string[]
  levels: StatementLevel[]
}

export interface AuditStatement {
  startDate: string
  endDate: string
  operator: string | null
  selection: StatementSelection
  purchases: StatementRow
  bankPaymentsToSuppliers: StatementRow
  cashOutflow: StatementRow
  inventory: StatementInventoryRow
  sales: StatementRow
  bankInflow: StatementRow
  cashInflow: StatementRow
  summary: StatementSummary | null
  notes: string[]
}

export interface StatementBulkMapRequest {
  startDate: string
  endDate: string
  sourceType: AuditSourceType
  sourceRowIds: string[]
  categoryCode: string
  subgroupCode?: string | null
  counterpartyTin?: string | null
  counterpartyName?: string | null
  note?: string | null
  replace: boolean
}

export interface StatementBulkMapResult {
  mapped: number
  skipped: number
  amount: number | null
}

export interface StatementTransaction {
  id: string
  kind: 'DOCUMENT_LINE' | 'BANK_ROW' | 'PAYMENT' | 'CASH_PAYMENT'
  date: string | null
  direction: string | null
  amount: number | null
  counterpartyTin: string | null
  counterpartyName: string | null
  productName: string | null
  category: string | null
  quantityKg: number | null
  unit: string | null
  waybillId: string | null
  description: string | null
  reference: string | null
  source: string | null
  sourceType: AuditSourceType | null
  sourceRowId: string | null
  mappingStatus: AuditMappingStatus | null
  mappingSummary: string | null
  unresolvedAmount: number | null
  mappedCounterparties: string[] | null
  withdrawal: boolean
  attribution: 'DIRECT' | 'MAPPED' | null
  sourceRow: AuditSourceRow | null
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
  /**
   * Level-2 document status (BOR-92): PURCHASE_ACT_NEEDED | CHECK_NEEDED |
   * CHECK_RECEIVED | UNMAPPED | a user-created code. Null = no status chosen.
   */
  subgroupCode?: string | null
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
  /**
   * The saved rule that created this mapping, when one did (BOR-91).
   *
   * Server-derived and optional: it is never sent back on save, and a build
   * whose backend predates rules simply omits it. Wherever a row is displayed,
   * a present value is shown, so a classification nobody typed says who did
   * type it.
   */
  appliedByRuleId?: string | null
}

// ---------------------------------------------------------------------------
// AuditMappingRuleDto / MappingRuleCriterion (BOR-91)
// ---------------------------------------------------------------------------

/**
 * What makes another transaction "similar" enough to inherit a mapping.
 *
 * Mirrors `MappingRuleCriterion.java`. Every criterion also matches on
 * direction — a payment to a counterparty and a receipt from the same
 * counterparty are never the same thing.
 */
export type MappingRuleCriterion =
  | 'COUNTERPARTY'
  | 'COUNTERPARTY_AND_DESCRIPTION'
  | 'DESCRIPTION'
  | 'TRANSACTION_TYPE'

/**
 * Fallback wording for a criterion, used only when the backend's own
 * `explanation` is absent. The backend's sentence is preferred because it is
 * built from the row in hand and therefore names the actual counterparty.
 */
export const CRITERION_LABEL: Record<MappingRuleCriterion, string> = {
  COUNTERPARTY: 'Every transaction with this counterparty, in this direction',
  COUNTERPARTY_AND_DESCRIPTION:
    'This counterparty and this exact payment description',
  DESCRIPTION: 'Any transaction with this exact description, whoever it is with',
  TRANSACTION_TYPE: 'Every transaction the bank gave this transaction type, in this direction',
}

/** A reusable classification the user chose to apply beyond one transaction. */
export interface AuditMappingRule {
  id: string | null
  criterion: MappingRuleCriterion | null
  /** CREDIT or DEBIT. A rule never crosses direction. */
  direction: string | null
  counterpartyTin: string | null
  counterpartyName: string | null
  description: string | null
  transactionType: string | null

  // what the rule asserts
  categoryCode: string | null
  mappedCounterpartyName: string | null
  mappedCounterpartyTin: string | null
  note: string | null

  /** False once revoked; kept so history still resolves. */
  active: boolean
  appliedCount: number
  appliedAmount: number | null

  createdBy: string | null
  createdAt: string | null
  updatedBy: string | null
  updatedAt: string | null
}

/** What a rule would do, shown before the user commits. */
export interface AuditMappingRulePreview {
  criterion: MappingRuleCriterion | null
  /** Plain-language statement of the match, e.g. "every payment to X". */
  explanation: string | null
  /** How many rows match, including the one in hand. */
  matchCount: number
  matchAmount: number | null
  /** How many of those already carry a mapping a person made. */
  alreadyMappedByPersonCount: number
  sample: AuditSourceRow[] | null
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
  /** The bank's own classification of the row, e.g. "გადარიცხვა თანხის გატანა". */
  transactionType: string | null
  /** Tax code the backend resolved for the counterparty, when it could. */
  resolvedCounterpartyTin: string | null
  counterpartyIdentitySource: string | null
  /** Why the identity above is claimed — the backend's own words. */
  counterpartyIdentityBasis: string | null
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

  // ---- BOR-92: level-2 subgroups and the statement ----

  getSubgroups: async () => {
    const response = await fetchWithAuth(`${BASE}/subgroups`)
    return jsonData<AuditSubgroup[]>(response)
  },

  createSubgroup: async (subgroup: AuditSubgroup, operator: string) => {
    const response = await fetchWithAuth(`${BASE}/subgroups`, {
      method: 'POST',
      params: { operator },
      body: JSON.stringify(subgroup),
    })
    return jsonData<AuditSubgroup>(response)
  },

  deleteSubgroup: async (code: string, operator: string) => {
    await fetchWithAuth(`${BASE}/subgroups/${encodeURIComponent(code)}`, {
      method: 'DELETE',
      params: { operator },
    })
  },

  getStatement: async (params: { startDate: string; endDate: string; operator?: string }) => {
    const response = await fetchWithAuth(`${BASE}/statement`, { params: { ...params } })
    return jsonData<AuditStatement>(response)
  },

  getStatementTransactions: async (params: {
    row: StatementRowKey
    startDate: string
    endDate: string
    tin?: string
    category?: string
    attribution?: 'DIRECT' | 'MAPPED'
    withdrawalsOnly?: boolean
    limit?: number
  }) => {
    const response = await fetchWithAuth(`${BASE}/statement/transactions`, { params: { ...params } })
    return jsonData<StatementTransaction[]>(response)
  },

  /**
   * Map an explicit set of bank rows in one write (BOR-92 v4). Not a rule:
   * only the listed ids change. Default fills each row's unmapped remainder;
   * `replace` drops the row's splits and covers the whole amount.
   */
  bulkMapStatement: async (request: StatementBulkMapRequest, operator: string) => {
    const response = await fetchWithAuth(`${BASE}/statement/bulk-map`, {
      method: 'POST',
      params: { operator },
      body: JSON.stringify(request),
    })
    return jsonData<StatementBulkMapResult>(response)
  },

  saveStatementSelection: async (selection: StatementSelection, operator: string) => {
    const response = await fetchWithAuth(`${BASE}/statement/selection`, {
      method: 'PUT',
      params: { operator },
      body: JSON.stringify(selection),
    })
    return jsonData<StatementSelection>(response)
  },

  /** Sets a product's group in the shared store /audit-control and /product-categories use. Applies everywhere, not to one row. */
  setProductCategory: async (productName: string, category: string, operator: string) => {
    await fetchWithAuth(`${BASE}/product-categories`, {
      method: 'PUT',
      params: { productName, category, operator },
    })
  },

  getProductCategoryCodes: async () => {
    const response = await fetchWithAuth(`${BASE}/product-categories`)
    return jsonData<string[]>(response)
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

  // -------------------------------------------------------------------------
  // Mapping rules (BOR-91). A rule is created only by explicit choice: the
  // scope step offers the criteria that actually match the row in hand, each
  // with a live count of what it would catch, and the user picks one.
  // -------------------------------------------------------------------------

  getMappingRules: async () => {
    const response = await fetchWithAuth(`${BASE}/mappings/rules`)
    return jsonData<AuditMappingRule[]>(response)
  },

  /** One entry per criterion that applies to this row. Never auto-selected. */
  previewMappingRules: async (params: {
    sourceRowId: string
    startDate: string
    endDate: string
  }) => {
    const response = await fetchWithAuth(`${BASE}/mappings/rules/preview`, {
      params: { ...params },
    })
    return jsonData<AuditMappingRulePreview[]>(response)
  },

  /**
   * Saves the rule and applies it across the given period in one call — the
   * period is required, because "apply this classification to similar rows"
   * has no meaning without saying over what span. Editing an existing rule
   * re-applies it the same way.
   */
  saveMappingRule: async (
    rule: AuditMappingRule,
    operator: string,
    period: { startDate: string; endDate: string }
  ) => {
    const response = await fetchWithAuth(`${BASE}/mappings/rules`, {
      method: 'PUT',
      params: { operator, startDate: period.startDate, endDate: period.endDate },
      body: JSON.stringify(rule),
    })
    return jsonData<AuditMappingRule>(response)
  },

  /** Revoking un-maps every mapping the rule created. */
  revokeMappingRule: async (id: string, operator: string, reason: string) => {
    await fetchWithAuth(`${BASE}/mappings/rules/${encodeURIComponent(id)}`, {
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
