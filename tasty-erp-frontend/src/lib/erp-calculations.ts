import type { Payment, Waybill } from '@/types/domain'
import { parseGeorgianNumber } from '@/lib/utils'

export const VAT_RATE = 0.18

export function toNumber(value: unknown): number {
  if (value == null) return 0
  if (typeof value === 'number') return Number.isFinite(value) ? value : 0
  if (typeof value === 'string') {
    return parseGeorgianNumber(value)
  }
  return 0
}

export function vatFromGross(grossAmount: number, vatRate = VAT_RATE): number {
  if (!Number.isFinite(grossAmount) || grossAmount === 0) return 0
  return (grossAmount / (1 + vatRate)) * vatRate
}

export function isConfirmedWaybillStatus(status: unknown): boolean {
  if (status == null) return false
  if (typeof status === 'number') return status === 1
  if (typeof status === 'string') {
    const s = status.trim().toUpperCase()
    return s === '1' || s === 'CONFIRMED'
  }
  return false
}

export function sumWaybillAmount(
  waybills: Waybill[],
  opts?: { afterCutoffOnly?: boolean; confirmedOnly?: boolean; positiveOnly?: boolean }
): number {
  const afterCutoffOnly = opts?.afterCutoffOnly ?? false
  const confirmedOnly = opts?.confirmedOnly ?? false
  const positiveOnly = opts?.positiveOnly ?? false
  return waybills
    .filter((w) => (afterCutoffOnly ? Boolean(w.isAfterCutoff) : true))
    .filter((w) => (confirmedOnly ? isConfirmedWaybillStatus(w.status) : true))
    .reduce((sum, w) => {
      const amount = toNumber(w.amount)
      if (positiveOnly && amount <= 0) return sum
      return sum + amount
    }, 0)
}

export function sumWaybillVat(
  waybills: Waybill[],
  opts?: {
    afterCutoffOnly?: boolean
    confirmedOnly?: boolean
    positiveOnly?: boolean
    vatRate?: number
  }
): number {
  const vatRate = opts?.vatRate ?? VAT_RATE
  const gross = sumWaybillAmount(waybills, {
    afterCutoffOnly: opts?.afterCutoffOnly,
    confirmedOnly: opts?.confirmedOnly,
    positiveOnly: opts?.positiveOnly,
  })
  return vatFromGross(gross, vatRate)
}

const AUTHORIZED_BANK_SOURCES = new Set(['tbc', 'bog', 'excel', 'bank-api'])

export function isAuthorizedPaymentSource(payment: Payment): boolean {
  const source = payment.source?.toLowerCase().trim() ?? ''
  if (AUTHORIZED_BANK_SOURCES.has(source)) return true
  if (source === 'manual-cash') return true
  const desc = payment.description?.toLowerCase() ?? ''
  return desc.includes('bank payment')
}

export function isManualCashPayment(payment: Payment): boolean {
  return (payment.source?.toLowerCase().trim() ?? '') === 'manual-cash'
}

export function sumPaymentAmount(
  payments: Payment[],
  opts?: { afterCutoffOnly?: boolean; authorizedOnly?: boolean }
): number {
  const afterCutoffOnly = opts?.afterCutoffOnly ?? false
  const authorizedOnly = opts?.authorizedOnly ?? true

  return payments
    .filter((p) => (afterCutoffOnly ? Boolean(p.isAfterCutoff) : true))
    .filter((p) => (authorizedOnly ? isAuthorizedPaymentSource(p) : true))
    .reduce((sum, p) => sum + toNumber(p.amount), 0)
}

export function calculateCurrentDebt(params: {
  startingDebt: number
  totalSales: number
  totalPayments: number
}): number {
  return params.startingDebt + params.totalSales - params.totalPayments
}
