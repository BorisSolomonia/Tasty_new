/**
 * BOR-89 — React Query access to the audit layer.
 *
 * The ticket's hard rule is that the eight UX variants "must not have different
 * data scope". That is enforced structurally here rather than by convention:
 *
 *  - `useAuditFlows` fetches the ONE canonical `AuditFlowsDto`. The page calls
 *    it once and hands the result to whichever variant is on screen through
 *    `AuditContext`. No variant fetches its own aggregates.
 *  - `useAuditSourceRows` is the shared row feed for the variants that work at
 *    row level (Cash-First, Documentation Ledger, Workbench, Risk Heatmap). It
 *    is keyed on the same period as the flows payload, so a row visible in one
 *    variant is visible in all of them.
 *
 * Every query key starts with `audit-layer`, so `refreshAll` can refresh
 * aggregates, rows and manual inputs together. Mutations, however, invalidate
 * only the scopes they can actually change (BOR-82 finding FE-2): a blanket
 * `['audit-layer']` invalidation after every save refetched 5–8 query families —
 * including the 10–20 s flows scan — for a mapping edit that could not have
 * touched categories, rules or supplier debt. Clearing a 20-row queue cost
 * ~100 refetches and ~20 full-period scans.
 */
import { useCallback } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  auditLayerApi,
  type AuditCategory,
  type AuditSubgroup,
  type StatementRowKey,
  type StatementSelection,
  type AuditMapping,
  type AuditMappingRule,
  type AuditPeriodParams,
  type AuditSourceRowPage,
  type AuditSourceRowParams,
  type AuditSourceType,
  type CheckEvidence,
  type RealInventoryOverride,
  type RealSupplierDebt,
} from '@/lib/audit-api'

export const AUDIT_LAYER_KEY = 'audit-layer'

const STALE = 1000 * 60 * 2

/** The single canonical three-flow payload. */
export function useAuditFlows(params: AuditPeriodParams, enabled = true) {
  return useQuery({
    queryKey: [AUDIT_LAYER_KEY, 'flows', params.startDate, params.endDate, params.product ?? ''],
    queryFn: () => auditLayerApi.getFlows(params),
    staleTime: STALE,
    // No retry: over a wide range this is a long scan, and a silent second
    // attempt would double the wait. A failure surfaces at once and the
    // operator decides whether to re-run it.
    retry: false,
    enabled,
  })
}

/** Shared immutable-source-row feed. Returns a page carrying its own cap. */
export function useAuditSourceRows(params: AuditSourceRowParams, enabled = true) {
  return useQuery({
    queryKey: [
      AUDIT_LAYER_KEY,
      'source-rows',
      params.startDate,
      params.endDate,
      params.sourceType ?? '',
      params.status ?? '',
      params.search ?? '',
      params.limit ?? 0,
    ],
    queryFn: () => auditLayerApi.getSourceRows(params),
    staleTime: STALE,
    retry: false,
    enabled,
  })
}

/** BOR-92 v2: the statement. Keyed by period and operator (whose saved selection defines "chosen"). */
export function useAuditStatement(params: { startDate: string; endDate: string; operator?: string }, enabled = true) {
  return useQuery({
    queryKey: [AUDIT_LAYER_KEY, 'statement', params.startDate, params.endDate, params.operator ?? ''],
    queryFn: () => auditLayerApi.getStatement(params),
    staleTime: STALE,
    retry: false,
    enabled,
  })
}

export function useStatementTransactions(
  params: { row: StatementRowKey; startDate: string; endDate: string; tin?: string; category?: string },
  enabled = true
) {
  return useQuery({
    queryKey: [
      AUDIT_LAYER_KEY,
      'statement-transactions',
      params.row,
      params.startDate,
      params.endDate,
      params.tin ?? '',
      params.category ?? '',
    ],
    queryFn: () => auditLayerApi.getStatementTransactions(params),
    staleTime: STALE,
    retry: false,
    enabled,
  })
}

/** Saving the selection moves every "chosen" figure; nothing else changes. */
export function useSaveStatementSelection(operator: string) {
  const invalidate = useInvalidateAuditScopes()
  return useMutation({
    mutationFn: (selection: StatementSelection) => auditLayerApi.saveStatementSelection(selection, operator),
    onSuccess: () => invalidate(['statement']),
  })
}

/**
 * A product's group is a shared rule (one category per product name, both
 * pages). It moves the statement, the flows and the drill-downs.
 */
export function useSetProductCategory(operator: string) {
  const invalidate = useInvalidateAuditScopes()
  return useMutation({
    mutationFn: (input: { productName: string; category: string }) =>
      auditLayerApi.setProductCategory(input.productName, input.category, operator),
    onSuccess: () => invalidate(['statement', 'flows', 'source-rows', 'drilldown']),
  })
}

export function useProductCategoryCodes() {
  return useQuery({
    queryKey: [AUDIT_LAYER_KEY, 'product-category-codes'],
    queryFn: () => auditLayerApi.getProductCategoryCodes(),
    staleTime: 1000 * 60 * 60,
    retry: 1,
  })
}

/** BOR-92: level-2 subgroups (built-in + custom). */
export function useAuditSubgroups() {
  return useQuery({
    queryKey: [AUDIT_LAYER_KEY, 'subgroups'],
    queryFn: () => auditLayerApi.getSubgroups(),
    staleTime: 1000 * 60 * 10,
    retry: 1,
  })
}

export function useCreateSubgroup(operator: string) {
  const invalidate = useInvalidateAuditScopes()
  return useMutation({
    mutationFn: (subgroup: AuditSubgroup) => auditLayerApi.createSubgroup(subgroup, operator),
    onSuccess: () => invalidate(['subgroups', 'statement']),
  })
}

export function useDeleteSubgroup(operator: string) {
  const invalidate = useInvalidateAuditScopes()
  return useMutation({
    mutationFn: (code: string) => auditLayerApi.deleteSubgroup(code, operator),
    onSuccess: () => invalidate(['subgroups', 'statement']),
  })
}

export function useAuditCategories() {
  return useQuery({
    queryKey: [AUDIT_LAYER_KEY, 'categories'],
    queryFn: () => auditLayerApi.getCategories(),
    staleTime: 1000 * 60 * 10,
    retry: 1,
  })
}

export function useAuditDrilldown(
  params: { key: string; startDate: string; endDate: string; subject?: string },
  enabled: boolean
) {
  return useQuery({
    queryKey: [
      AUDIT_LAYER_KEY,
      'drilldown',
      params.key,
      params.startDate,
      params.endDate,
      params.subject ?? '',
    ],
    queryFn: () => auditLayerApi.getDrilldown(params),
    staleTime: STALE,
    // Same reasoning as the flows query: never pay a long scan twice silently.
    retry: false,
    enabled,
  })
}

// ---------------------------------------------------------------------------
// Mapping rules (BOR-91)
// ---------------------------------------------------------------------------

/**
 * Every saved rule, active or revoked.
 *
 * `retry: false` on purpose. This endpoint is the newest part of the layer, and
 * a build whose backend predates it answers 404 — retrying would only delay the
 * same answer. Callers render the failure as "the rules endpoint did not
 * answer", never as "there are no rules".
 */
export function useMappingRules(enabled = true) {
  return useQuery({
    queryKey: [AUDIT_LAYER_KEY, 'mapping-rules'],
    queryFn: () => auditLayerApi.getMappingRules(),
    staleTime: STALE,
    retry: false,
    enabled,
  })
}

/**
 * What each applicable criterion would catch for one source row.
 *
 * Fetched only while the scope step is open — this is a scan over the period,
 * and nothing should pay for it before the user has asked to widen a mapping.
 */
export function useMappingRulePreview(
  params: { sourceRowId: string; startDate: string; endDate: string },
  enabled: boolean
) {
  return useQuery({
    queryKey: [
      AUDIT_LAYER_KEY,
      'mapping-rule-preview',
      params.sourceRowId,
      params.startDate,
      params.endDate,
    ],
    queryFn: () => auditLayerApi.previewMappingRules(params),
    staleTime: STALE,
    retry: false,
    enabled: enabled && Boolean(params.sourceRowId),
  })
}

/** Saving a rule also applies it, so the period it applies over is required. */
export function useSaveMappingRule(
  operator: string,
  period: { startDate: string; endDate: string }
) {
  const invalidate = useInvalidateAuditScopes()
  return useMutation({
    mutationFn: (rule: AuditMappingRule) =>
      auditLayerApi.saveMappingRule(rule, operator, period),
    // A rule maps rows, so everything a mapping moves, plus the rule list.
    onSuccess: () => invalidate(['mapping-rules', ...MAPPING_SCOPES]),
  })
}

export function useRevokeMappingRule(operator: string) {
  const invalidate = useInvalidateAuditScopes()
  return useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) =>
      auditLayerApi.revokeMappingRule(id, operator, reason),
    onSuccess: () => invalidate(['mapping-rules', ...MAPPING_SCOPES]),
  })
}

export function useMappingHistory(
  sourceType: AuditSourceType | null | undefined,
  sourceRowId: string | null | undefined
) {
  return useQuery({
    queryKey: [AUDIT_LAYER_KEY, 'mapping-history', sourceType ?? '', sourceRowId ?? ''],
    queryFn: () => auditLayerApi.getMappingHistory(sourceType as AuditSourceType, sourceRowId as string),
    staleTime: STALE,
    retry: 1,
    enabled: Boolean(sourceType && sourceRowId),
  })
}

export function useRealInventory(asOfDate: string, enabled = true) {
  return useQuery({
    queryKey: [AUDIT_LAYER_KEY, 'real-inventory', asOfDate],
    queryFn: () => auditLayerApi.getRealInventory(asOfDate),
    staleTime: STALE,
    retry: 1,
    enabled,
  })
}

export function useSupplierDebt(enabled = true) {
  return useQuery({
    queryKey: [AUDIT_LAYER_KEY, 'supplier-debt'],
    queryFn: () => auditLayerApi.getSupplierDebt(),
    staleTime: STALE,
    retry: 1,
    enabled,
  })
}

export function useCheckEvidence(params: { startDate: string; endDate: string }, enabled = true) {
  return useQuery({
    queryKey: [AUDIT_LAYER_KEY, 'check-evidence', params.startDate, params.endDate],
    queryFn: () => auditLayerApi.getCheckEvidence(params),
    staleTime: STALE,
    retry: 1,
    enabled,
  })
}

/**
 * The query families under `audit-layer`. Each mutation names the ones it can
 * change; nothing else is refetched.
 */
export type AuditScope =
  | 'flows'
  | 'source-rows'
  | 'categories'
  | 'subgroups'
  | 'statement'
  | 'drilldown'
  | 'mapping-rules'
  | 'mapping-rule-preview'
  | 'mapping-history'
  | 'real-inventory'
  | 'supplier-debt'
  | 'check-evidence'

/** What a change to one row's mapping can move: aggregates, the feed, open drill-downs, that row's history. */
export const MAPPING_SCOPES: readonly AuditScope[] = [
  'flows',
  'source-rows',
  'drilldown',
  'mapping-history',
  'mapping-rule-preview',
  'statement',
]

/**
 * Invalidates the whole audit layer — aggregates, rows and manual inputs.
 *
 * Returned callback is referentially stable (`useCallback`): it is placed in the
 * `AuditContext` value and listed in that value's `useMemo` deps, so a fresh
 * closure per render made the memo never hit and re-rendered every consumer on
 * every keystroke (BOR-82 finding FE-1).
 */
export function useInvalidateAuditLayer() {
  const queryClient = useQueryClient()
  return useCallback(
    () => void queryClient.invalidateQueries({ queryKey: [AUDIT_LAYER_KEY] }),
    [queryClient]
  )
}

/** Invalidates only the named scopes. Stable across renders. */
export function useInvalidateAuditScopes() {
  const queryClient = useQueryClient()
  return useCallback(
    (scopes: readonly AuditScope[]) =>
      Promise.all(
        scopes.map((scope) => queryClient.invalidateQueries({ queryKey: [AUDIT_LAYER_KEY, scope] }))
      ).then(() => undefined),
    [queryClient]
  )
}

// ---------------------------------------------------------------------------
// Mutations. `operator` is the self-declared name from OperatorPicker and is
// sent on every mutating call — the API has no authentication, so this is a
// recorded claim, not an identity.
// ---------------------------------------------------------------------------

export function useSaveMapping(operator: string) {
  const invalidate = useInvalidateAuditScopes()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (mapping: AuditMapping) => auditLayerApi.saveMapping(mapping, operator),
    // Stop any source-rows refetch that is still in flight from a PREVIOUS save.
    // Without this, that older response landed after the patch below and
    // overwrote it wholesale, flipping the row the operator just classified
    // back to UNMAPPED (BOR-82 finding FE-8) — indistinguishable from a failed
    // save. Cancelling here means the only refetch that can land is the one
    // this mutation schedules after it has patched.
    onMutate: async () => {
      await queryClient.cancelQueries({ queryKey: [AUDIT_LAYER_KEY, 'source-rows'] })
    },
    onSuccess: (saved) => {
      // The refetch this triggers can take ten to twenty seconds on a wide date
      // range, and until it lands the row still reads UNMAPPED — which looks
      // exactly like the save having failed. Patch the row in place first so the
      // result is visible immediately; the refetch then confirms it.
      queryClient.setQueriesData<AuditSourceRowPage>(
        { queryKey: [AUDIT_LAYER_KEY, 'source-rows'] },
        (page) => {
          if (!page?.rows) return page
          return {
            ...page,
            rows: page.rows.map((row) =>
              row.sourceType === saved.sourceType && row.sourceRowId === saved.sourceRowId
                ? {
                    ...row,
                    mapping: saved,
                    status: saved.status ?? row.status,
                    unresolvedAmount: saved.unresolvedAmount ?? row.unresolvedAmount,
                  }
                : row
            ),
          }
        }
      )
      void invalidate(MAPPING_SCOPES)
    },
  })
}

export function useVoidMapping(operator: string) {
  const invalidate = useInvalidateAuditScopes()
  return useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) =>
      auditLayerApi.deleteMapping(id, operator, reason),
    onSuccess: () => invalidate(MAPPING_SCOPES),
  })
}

export function useSaveRealInventory(operator: string) {
  const invalidate = useInvalidateAuditScopes()
  return useMutation({
    mutationFn: (override: RealInventoryOverride) =>
      auditLayerApi.saveRealInventory(override, operator),
    onSuccess: () => invalidate(['real-inventory', 'flows', 'statement']),
  })
}

export function useSaveSupplierDebt(operator: string) {
  const invalidate = useInvalidateAuditScopes()
  return useMutation({
    mutationFn: (debt: RealSupplierDebt) => auditLayerApi.saveSupplierDebt(debt, operator),
    onSuccess: () => invalidate(['supplier-debt', 'flows']),
  })
}

export function useSaveCheckEvidence(operator: string) {
  const invalidate = useInvalidateAuditScopes()
  return useMutation({
    mutationFn: (evidence: CheckEvidence) => auditLayerApi.saveCheckEvidence(evidence, operator),
    onSuccess: () => invalidate(['check-evidence', 'flows', 'drilldown', 'statement']),
  })
}

export function useCreateCategory(operator: string) {
  const invalidate = useInvalidateAuditScopes()
  return useMutation({
    mutationFn: (category: AuditCategory) => auditLayerApi.createCategory(category, operator),
    // A brand-new category has no mappings yet, so no aggregate can move.
    onSuccess: () => invalidate(['categories']),
  })
}
