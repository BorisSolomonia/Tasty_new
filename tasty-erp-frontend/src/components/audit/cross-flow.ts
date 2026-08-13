/**
 * Cross-flow derivations shared by the Flow Map, Exception Investigator and
 * Risk Heatmap.
 *
 * All three need to answer "what else does this touch?" and all three must
 * answer it the same way. The link between flows is the alert `subjects` list —
 * a product or counterparty named by the rule. Nothing here invents a
 * relationship the backend did not state.
 */
import type { AuditAlert, AuditSourceRow } from '@/lib/audit-api'
import { severityWeight } from './format'

export type FlowKey = 'INVENTORY' | 'CASH' | 'DOCUMENTATION'

export const FLOW_KEYS: FlowKey[] = ['INVENTORY', 'CASH', 'DOCUMENTATION']

export function flowOf(alert: AuditAlert): FlowKey | null {
  const upper = (alert.flow ?? '').toUpperCase()
  return (FLOW_KEYS as string[]).includes(upper) ? (upper as FlowKey) : null
}

export function alertsByFlow(alerts: AuditAlert[] | null | undefined): Record<FlowKey, AuditAlert[]> {
  const grouped: Record<FlowKey, AuditAlert[]> = { INVENTORY: [], CASH: [], DOCUMENTATION: [] }
  for (const alert of alerts ?? []) {
    const flow = flowOf(alert)
    if (flow) grouped[flow].push(alert)
  }
  return grouped
}

export function subjectsOf(alerts: AuditAlert[]): string[] {
  const set = new Set<string>()
  for (const alert of alerts) {
    for (const subject of alert.subjects ?? []) {
      if (subject && subject.trim()) set.add(subject.trim())
    }
  }
  return [...set]
}

/** Alerts naming at least one of `subjects`. */
export function alertsTouching(alerts: AuditAlert[], subjects: string[]): AuditAlert[] {
  if (subjects.length === 0) return []
  const wanted = new Set(subjects)
  return alerts.filter((alert) => (alert.subjects ?? []).some((subject) => wanted.has(subject)))
}

export interface CrossFlowCluster {
  subject: string
  alerts: AuditAlert[]
  byFlow: Record<FlowKey, AuditAlert[]>
  /** Number of distinct flows the subject appears in — 2 or 3 is the interesting case. */
  flowCount: number
  weight: number
}

/** Subjects that at least one rule named, ranked by severity weight. */
export function crossFlowClusters(alerts: AuditAlert[] | null | undefined): CrossFlowCluster[] {
  const bySubject = new Map<string, AuditAlert[]>()
  for (const alert of alerts ?? []) {
    for (const subject of alert.subjects ?? []) {
      const key = subject?.trim()
      if (!key) continue
      const bucket = bySubject.get(key)
      if (bucket) bucket.push(alert)
      else bySubject.set(key, [alert])
    }
  }

  const clusters: CrossFlowCluster[] = []
  for (const [subject, subjectAlerts] of bySubject) {
    const byFlow = alertsByFlow(subjectAlerts)
    const flowCount = FLOW_KEYS.filter((flow) => byFlow[flow].length > 0).length
    const weight = subjectAlerts.reduce((sum, alert) => sum + severityWeight(alert.severity), 0)
    clusters.push({ subject, alerts: subjectAlerts, byFlow, flowCount, weight })
  }

  return clusters.sort((a, b) => b.flowCount - a.flowCount || b.weight - a.weight)
}

/**
 * Severity weight per flow: CRITICAL 4, HIGH 3, MEDIUM 2, LOW 1.
 *
 * Deliberately not called a "risk score out of 100". The backend does not send
 * one, and normalising counts against an invented ceiling would be a fabricated
 * number wearing a percentage sign.
 */
export function flowWeights(alerts: AuditAlert[] | null | undefined) {
  const grouped = alertsByFlow(alerts)
  return FLOW_KEYS.map((flow) => {
    const flowAlerts = grouped[flow]
    return {
      flow,
      alerts: flowAlerts,
      count: flowAlerts.length,
      weight: flowAlerts.reduce((sum, alert) => sum + severityWeight(alert.severity), 0),
      critical: flowAlerts.filter((a) => (a.severity ?? '').toUpperCase() === 'CRITICAL').length,
    }
  })
}

export const UNRESOLVED_STATUSES = new Set(['UNMAPPED', 'PARTIALLY_MAPPED', 'SUGGESTED'])

export function isUnresolved(row: AuditSourceRow): boolean {
  return UNRESOLVED_STATUSES.has(row.status ?? 'UNMAPPED')
}

/** Month buckets (yyyy-MM) over the rows the shared feed returned. */
export interface MonthBucket {
  month: string
  total: number
  unresolved: number
  unresolvedAmount: number
}

export function monthBuckets(rows: AuditSourceRow[]): MonthBucket[] {
  const map = new Map<string, MonthBucket>()
  for (const row of rows) {
    const month = (row.date ?? '').slice(0, 7)
    if (month.length !== 7) continue
    let bucket = map.get(month)
    if (!bucket) {
      bucket = { month, total: 0, unresolved: 0, unresolvedAmount: 0 }
      map.set(month, bucket)
    }
    bucket.total += 1
    if (isUnresolved(row)) {
      bucket.unresolved += 1
      bucket.unresolvedAmount += Math.abs(row.unresolvedAmount ?? row.amount ?? 0)
    }
  }
  return [...map.values()].sort((a, b) => a.month.localeCompare(b.month))
}
