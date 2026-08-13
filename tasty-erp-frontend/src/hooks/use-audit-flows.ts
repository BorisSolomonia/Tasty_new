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
 * Every query key starts with `audit-layer`, so one `invalidateQueries` after a
 * mutation refreshes aggregates, rows and manual inputs together — a mapping
 * saved in the Workbench moves the numbers in the Control Tower.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  auditLayerApi,
  type AuditCategory,
  type AuditMapping,
  type AuditPeriodParams,
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

/** Invalidates the whole audit layer — aggregates, rows and manual inputs. */
export function useInvalidateAuditLayer() {
  const queryClient = useQueryClient()
  return () => void queryClient.invalidateQueries({ queryKey: [AUDIT_LAYER_KEY] })
}

// ---------------------------------------------------------------------------
// Mutations. `operator` is the self-declared name from OperatorPicker and is
// sent on every mutating call — the API has no authentication, so this is a
// recorded claim, not an identity.
// ---------------------------------------------------------------------------

export function useSaveMapping(operator: string) {
  const invalidate = useInvalidateAuditLayer()
  return useMutation({
    mutationFn: (mapping: AuditMapping) => auditLayerApi.saveMapping(mapping, operator),
    onSuccess: invalidate,
  })
}

export function useVoidMapping(operator: string) {
  const invalidate = useInvalidateAuditLayer()
  return useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) =>
      auditLayerApi.deleteMapping(id, operator, reason),
    onSuccess: invalidate,
  })
}

export function useSaveRealInventory(operator: string) {
  const invalidate = useInvalidateAuditLayer()
  return useMutation({
    mutationFn: (override: RealInventoryOverride) =>
      auditLayerApi.saveRealInventory(override, operator),
    onSuccess: invalidate,
  })
}

export function useSaveSupplierDebt(operator: string) {
  const invalidate = useInvalidateAuditLayer()
  return useMutation({
    mutationFn: (debt: RealSupplierDebt) => auditLayerApi.saveSupplierDebt(debt, operator),
    onSuccess: invalidate,
  })
}

export function useSaveCheckEvidence(operator: string) {
  const invalidate = useInvalidateAuditLayer()
  return useMutation({
    mutationFn: (evidence: CheckEvidence) => auditLayerApi.saveCheckEvidence(evidence, operator),
    onSuccess: invalidate,
  })
}

export function useCreateCategory(operator: string) {
  const invalidate = useInvalidateAuditLayer()
  return useMutation({
    mutationFn: (category: AuditCategory) => auditLayerApi.createCategory(category, operator),
    onSuccess: invalidate,
  })
}
