/**
 * BOR-89 — the shared state every one of the eight variants reads from.
 *
 * The provider owns the period/product filters, fetches the canonical payload
 * exactly once, and hands the same objects to whichever variant is mounted.
 * A variant never fetches an aggregate of its own, which is what makes "the
 * eight views must not have different data scope" true by construction rather
 * than by review.
 */
import * as React from 'react'
import type { UseQueryResult } from '@tanstack/react-query'
import {
  useAuditCategories,
  useAuditFlows,
  useAuditSourceRows,
  useInvalidateAuditLayer,
} from '@/hooks/use-audit-flows'
import type {
  AuditCategory,
  AuditFlows,
  AuditSourceRow,
  AuditSourceRowPage,
} from '@/lib/audit-api'

/** Shared row feed cap. Rows beyond this are not silently hidden — see below. */
export const SOURCE_ROW_LIMIT = 1000

export const OPERATOR_STORAGE_KEY = 'tasty.audit.operator'
export const OPERATOR_HISTORY_KEY = 'tasty.audit.operators'

export const ALL_PRODUCTS = '__ALL__'

export interface AuditFilters {
  startDate: string
  endDate: string
  /** ALL_PRODUCTS, or a standardised product name. */
  product: string
}

export interface DrilldownRequest {
  key: string
  subject?: string
  /** Label shown while the drill-down loads. */
  label?: string
}

interface AuditContextValue {
  filters: AuditFilters
  setFilters: (next: Partial<AuditFilters>) => void

  /** The one canonical payload. */
  flowsQuery: UseQueryResult<AuditFlows, Error>
  flows: AuditFlows | undefined

  /** Shared immutable source rows for the whole period, unfiltered by status. */
  sourceRowsQuery: UseQueryResult<AuditSourceRowPage, Error>
  sourceRows: AuditSourceRow[]
  /**
   * The server's own account of what it left out. Reported, never inferred:
   * asking for 2023→today returns the cap of rows covering the most recent
   * weeks only, and any view counting from this feed must say so or an unread
   * month reads as a clean month.
   */
  sourceRowsPage: AuditSourceRowPage | undefined

  categoriesQuery: UseQueryResult<AuditCategory[], Error>
  categories: AuditCategory[]

  /** Self-declared operator name sent on every mutating call. */
  operator: string
  setOperator: (name: string) => void
  operatorHistory: string[]

  drilldown: DrilldownRequest | null
  openDrilldown: (request: DrilldownRequest) => void
  closeDrilldown: () => void

  refreshAll: () => void
}

const AuditContext = React.createContext<AuditContextValue | null>(null)

function readStoredOperator(): string {
  try {
    return window.localStorage.getItem(OPERATOR_STORAGE_KEY) ?? ''
  } catch {
    return ''
  }
}

function readOperatorHistory(): string[] {
  try {
    const raw = window.localStorage.getItem(OPERATOR_HISTORY_KEY)
    if (!raw) return []
    const parsed: unknown = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.filter((n): n is string => typeof n === 'string') : []
  } catch {
    return []
  }
}

export function todayIso(): string {
  return new Date().toISOString().slice(0, 10)
}

export const DEFAULT_START_DATE = '2023-01-01'

export function AuditProvider({ children }: { children: React.ReactNode }) {
  const [filters, setFiltersState] = React.useState<AuditFilters>(() => ({
    startDate: DEFAULT_START_DATE,
    endDate: todayIso(),
    product: ALL_PRODUCTS,
  }))
  const [operator, setOperatorState] = React.useState<string>(readStoredOperator)
  const [operatorHistory, setOperatorHistory] = React.useState<string[]>(readOperatorHistory)
  const [drilldown, setDrilldown] = React.useState<DrilldownRequest | null>(null)

  const product = filters.product === ALL_PRODUCTS ? undefined : filters.product

  const flowsQuery = useAuditFlows({
    startDate: filters.startDate,
    endDate: filters.endDate,
    product,
  })

  const sourceRowsQuery = useAuditSourceRows({
    startDate: filters.startDate,
    endDate: filters.endDate,
    limit: SOURCE_ROW_LIMIT,
  })

  const categoriesQuery = useAuditCategories()
  const invalidate = useInvalidateAuditLayer()

  const sourceRowsPage = sourceRowsQuery.data
  const sourceRows = React.useMemo(() => sourceRowsPage?.rows ?? [], [sourceRowsPage])

  const setFilters = React.useCallback((next: Partial<AuditFilters>) => {
    setFiltersState((current) => ({ ...current, ...next }))
  }, [])

  const setOperator = React.useCallback((name: string) => {
    const trimmed = name.trim()
    setOperatorState(trimmed)
    try {
      window.localStorage.setItem(OPERATOR_STORAGE_KEY, trimmed)
      if (trimmed) {
        setOperatorHistory((current) => {
          if (current.includes(trimmed)) return current
          const next = [trimmed, ...current].slice(0, 12)
          window.localStorage.setItem(OPERATOR_HISTORY_KEY, JSON.stringify(next))
          return next
        })
      }
    } catch {
      // A browser with storage disabled still gets a working session-local name.
    }
  }, [])

  const value = React.useMemo<AuditContextValue>(
    () => ({
      filters,
      setFilters,
      flowsQuery,
      flows: flowsQuery.data,
      sourceRowsQuery,
      sourceRows,
      sourceRowsPage,
      categoriesQuery,
      categories: categoriesQuery.data ?? [],
      operator,
      setOperator,
      operatorHistory,
      drilldown,
      openDrilldown: setDrilldown,
      closeDrilldown: () => setDrilldown(null),
      refreshAll: invalidate,
    }),
    [
      filters,
      setFilters,
      flowsQuery,
      sourceRowsQuery,
      sourceRows,
      sourceRowsPage,
      categoriesQuery,
      operator,
      setOperator,
      operatorHistory,
      drilldown,
      invalidate,
    ]
  )

  return <AuditContext.Provider value={value}>{children}</AuditContext.Provider>
}

export function useAudit(): AuditContextValue {
  const context = React.useContext(AuditContext)
  if (!context) throw new Error('useAudit must be used inside <AuditProvider>')
  return context
}

/** Convenience for views: the period, ready to pass to a drill-down fetch. */
export function useAuditPeriod() {
  const { filters } = useAudit()
  return { startDate: filters.startDate, endDate: filters.endDate }
}
