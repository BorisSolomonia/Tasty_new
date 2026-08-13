/**
 * Rendering rules for BOR-89.
 *
 * The module's honesty rule is enforced here, in one place: a figure the
 * backend did not send is an em dash, never a zero, never a guess. Callers pass
 * whatever the payload gave them — null, undefined or a number — and get a
 * string that tells the truth about which of those it was.
 */
import { formatCurrency, formatNumber } from '@/lib/utils'

export const EM_DASH = '—'

export function hasValue(value: number | null | undefined): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

/** Georgian Lari. Em dash when the payload has no figure. */
export function fmtGel(value: number | null | undefined): string {
  return hasValue(value) ? formatCurrency(value) : EM_DASH
}

/** Signed Lari — keeps the direction of a gap visible. */
export function fmtGelSigned(value: number | null | undefined): string {
  if (!hasValue(value)) return EM_DASH
  return value > 0 ? `+${formatCurrency(value)}` : formatCurrency(value)
}

export function fmtKg(value: number | null | undefined, decimals = 2): string {
  return hasValue(value) ? `${formatNumber(value, decimals)} kg` : EM_DASH
}

export function fmtKgSigned(value: number | null | undefined, decimals = 2): string {
  if (!hasValue(value)) return EM_DASH
  const body = `${formatNumber(value, decimals)} kg`
  return value > 0 ? `+${body}` : body
}

export function fmtCount(value: number | null | undefined): string {
  return hasValue(value) ? formatNumber(value, 0) : EM_DASH
}

export function fmtPercent(value: number | null | undefined, decimals = 1): string {
  return hasValue(value) ? `${formatNumber(value, decimals)}%` : EM_DASH
}

/**
 * A named input of an alert formula. The unit is the rule's business, not ours,
 * so the value is printed as a plain number rather than dressed as money or kg.
 */
export function formatInput(value: number | null | undefined): string {
  return hasValue(value) ? formatNumber(value, 2) : EM_DASH
}

/** Raw source text: shown verbatim, Georgian included, never translated. */
export function fmtText(value: string | null | undefined): string {
  const trimmed = (value ?? '').trim()
  return trimmed.length > 0 ? trimmed : EM_DASH
}

export function fmtDate(value: string | null | undefined): string {
  const trimmed = (value ?? '').trim()
  if (!trimmed) return EM_DASH
  // Source dates arrive as yyyy-MM-dd and timestamps as yyyy-MM-ddTHH:mm:ss.
  return trimmed.replace('T', ' ')
}

/** Tone tokens the audit views use for value colouring. */
export type Tone = 'neutral' | 'good' | 'warn' | 'bad' | 'info'

export const toneClass: Record<Tone, string> = {
  neutral: 'text-foreground',
  good: 'text-success',
  warn: 'text-warning',
  bad: 'text-destructive',
  info: 'text-primary',
}

/**
 * Tone for a figure that should be zero — a gap, an unresolved remainder, an
 * unsupported balance. Absent stays neutral: unknown is not the same as clean.
 */
export function gapTone(value: number | null | undefined, warnBelow = 0): Tone {
  if (!hasValue(value)) return 'neutral'
  const magnitude = Math.abs(value)
  if (magnitude < 0.005) return 'good'
  return magnitude <= warnBelow ? 'warn' : 'bad'
}

export const SEVERITY_ORDER: Record<string, number> = {
  CRITICAL: 0,
  HIGH: 1,
  MEDIUM: 2,
  LOW: 3,
}

export function severityRank(severity: string | null | undefined): number {
  return SEVERITY_ORDER[(severity ?? '').toUpperCase()] ?? 99
}

export function severityTone(severity: string | null | undefined): Tone {
  switch ((severity ?? '').toUpperCase()) {
    case 'CRITICAL':
    case 'HIGH':
      return 'bad'
    case 'MEDIUM':
      return 'warn'
    case 'LOW':
      return 'info'
    default:
      return 'neutral'
  }
}

/** Weight used by the Risk Heatmap. Kept here so the formula has one home. */
export const SEVERITY_WEIGHT: Record<string, number> = {
  CRITICAL: 4,
  HIGH: 3,
  MEDIUM: 2,
  LOW: 1,
}

export function severityWeight(severity: string | null | undefined): number {
  return SEVERITY_WEIGHT[(severity ?? '').toUpperCase()] ?? 0
}

export const FLOW_LABEL: Record<string, string> = {
  INVENTORY: 'Inventory',
  CASH: 'Cash',
  DOCUMENTATION: 'Documentation',
}

export function flowLabel(flow: string | null | undefined): string {
  const key = (flow ?? '').toUpperCase()
  return FLOW_LABEL[key] ?? fmtText(flow)
}
