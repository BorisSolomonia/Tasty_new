import { format, parseISO, isValid } from 'date-fns'
import { ka } from 'date-fns/locale'

/**
 * Format amount in Georgian Lari
 */
export function formatCurrency(amount: number): string {
  return new Intl.NumberFormat('ka-GE', {
    style: 'currency',
    currency: 'GEL',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(amount)
}

/**
 * Format number with Georgian locale
 */
export function formatNumber(num: number, decimals = 2): string {
  return new Intl.NumberFormat('ka-GE', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  }).format(num)
}

/**
 * Parse Georgian/Excel number format to number
 * Handles: "1 234,56" or "1,234.56" or "1234.56"
 */
export function parseGeorgianNumber(str: string | number | null | undefined): number {
  if (str === null || str === undefined) return 0
  if (typeof str === 'number') return str

  // Remove spaces and non-breaking spaces
  let cleaned = str.replace(/[\s\u00A0\u202F]/g, '')

  // Handle Georgian format (comma as decimal separator)
  // Find the rightmost separator - that's the decimal
  const lastComma = cleaned.lastIndexOf(',')
  const lastDot = cleaned.lastIndexOf('.')

  if (lastComma > lastDot) {
    // Georgian format: 1234,56 or 1.234,56
    cleaned = cleaned.replace(/\./g, '').replace(',', '.')
  } else if (lastDot > lastComma) {
    // Standard format: 1234.56 or 1,234.56
    cleaned = cleaned.replace(/,/g, '')
  }

  const num = parseFloat(cleaned)
  return isNaN(num) ? 0 : num
}

/**
 * Format date for display
 */
export function formatDate(date: Date | string | null | undefined, pattern = 'dd.MM.yyyy'): string {
  if (!date) return ''

  const d = typeof date === 'string' ? parseISO(date) : date
  if (!isValid(d)) return ''

  return format(d, pattern, { locale: ka })
}

/**
 * Format date for API requests (ISO format)
 */
export function formatDateISO(date: Date | null | undefined): string {
  if (!date || !isValid(date)) return ''
  return format(date, 'yyyy-MM-dd')
}

/**
 * Parse Excel serial date number to Date
 * Excel uses 1900 date system with leap year bug (day 60 = Feb 29, 1900 which doesn't exist)
 */
export function parseExcelDate(serial: number | string): Date | null {
  if (typeof serial === 'string') {
    // Try parsing as ISO date first
    const parsed = parseISO(serial)
    if (isValid(parsed)) return parsed

    // Try parsing Georgian format (DD.MM.YYYY)
    const parts = serial.match(/(\d{2})\.(\d{2})\.(\d{4})/)
    if (parts) {
      return new Date(parseInt(parts[3]), parseInt(parts[2]) - 1, parseInt(parts[1]))
    }

    return null
  }

  // Excel serial number
  // Excel epoch is 1900-01-01 (day 1), but JavaScript epoch is 1970-01-01
  // Offset: 25569 days from Excel epoch to Unix epoch
  // Subtract 1 because Excel incorrectly treats 1900 as a leap year
  const EXCEL_EPOCH_OFFSET = 25568 // Days from 1900-01-01 to 1970-01-01 minus leap year bug
  const unixTimestamp = (serial - EXCEL_EPOCH_OFFSET) * 24 * 60 * 60 * 1000

  const date = new Date(unixTimestamp)
  return isValid(date) ? date : null
}

/**
 * Generate unique code for payment deduplication
 * Format: date|amountCents|customerId|balanceCents
 */
export function generateUniqueCode(
  date: string,
  amount: number,
  customerId: string,
  balance: number
): string {
  const amountCents = Math.round(amount * 100)
  const balanceCents = Math.round(balance * 100)
  return `${date}|${amountCents}|${customerId}|${balanceCents}`
}

/**
 * Calculate VAT from gross amount (Georgian standard 18%)
 */
export function calculateVAT(grossAmount: number, vatRate = 0.18): number {
  return (grossAmount / (1 + vatRate)) * vatRate
}

/**
 * Debounce function
 */
export function debounce<T extends (...args: unknown[]) => unknown>(
  fn: T,
  delay: number
): (...args: Parameters<T>) => void {
  let timeoutId: ReturnType<typeof setTimeout>
  return (...args: Parameters<T>) => {
    clearTimeout(timeoutId)
    timeoutId = setTimeout(() => fn(...args), delay)
  }
}

/**
 * Get payment cutoff date from environment
 */
export function getPaymentCutoffDate(): Date {
  const cutoff = import.meta.env.VITE_PAYMENT_CUTOFF_DATE || '2025-04-29'
  return parseISO(cutoff)
}

/**
 * Check if date is after payment cutoff (inclusive of day after)
 */
export function isAfterPaymentCutoff(date: Date | string): boolean {
  const cutoff = getPaymentCutoffDate()
  const d = typeof date === 'string' ? parseISO(date) : date
  return d > cutoff
}
