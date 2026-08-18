import { describe, expect, it } from 'vitest'
import { canonicalId, formatCurrency, formatNumber, parseGeorgianNumber } from './utils'

describe('canonicalId (BOR-82 finding FE-21: one implementation, not three)', () => {
  it('treats a leading-zero TIN and its stripped form as the same customer', () => {
    expect(canonicalId('01008057492')).toBe('1008057492')
    expect(canonicalId('1008057492')).toBe('1008057492')
    expect(canonicalId('01008057492')).toBe(canonicalId('1008057492'))
  })

  it('handles null, empty and non-numeric ids without throwing', () => {
    expect(canonicalId(null)).toBe('')
    expect(canonicalId(undefined)).toBe('')
    expect(canonicalId('')).toBe('')
    expect(canonicalId('  abc ')).toBe('abc')
    expect(canonicalId('abc')).not.toBe(canonicalId('xyz'))
  })

  it('keeps a lone zero', () => {
    expect(canonicalId('000')).toBe('0')
  })
})

describe('number formatting (BOR-82 finding FE-5: formatters built once)', () => {
  it('formats GEL and plain numbers with the Georgian locale', () => {
    // Locale data may group with a space, NBSP, narrow NBSP, dot or comma.
    const grouped = (rest: string) => new RegExp('1[\\s.,\u00a0\u202f]?234' + rest)
    expect(formatCurrency(1234.5)).toMatch(grouped('[.,]50'))
    expect(formatNumber(1234.5, 1)).toMatch(grouped('[.,]5$'))
    expect(formatNumber(2, 0)).toBe('2')
  })

  it('is stable across repeated calls (cached formatter, same output)', () => {
    const first = formatNumber(99.999, 2)
    for (let i = 0; i < 1000; i++) expect(formatNumber(99.999, 2)).toBe(first)
  })
})

describe('parseGeorgianNumber', () => {
  it('reads both separator conventions', () => {
    expect(parseGeorgianNumber('1.234,56')).toBe(1234.56)
    expect(parseGeorgianNumber('1,234.56')).toBe(1234.56)
    expect(parseGeorgianNumber('1 234,56')).toBe(1234.56)
    expect(parseGeorgianNumber(null)).toBe(0)
  })
})

describe('isSaneAuditDate (audit filters)', () => {
  it('refuses the half-typed years a native date input emits and anything before the floor', async () => {
    const { isSaneAuditDate } = await import('@/components/audit/audit-context')
    for (const v of ['0002-01-01', '0020-01-01', '0202-01-01', '1999-12-31', '', '2023-1-1']) {
      expect(isSaneAuditDate(v)).toBe(false)
    }
    expect(isSaneAuditDate('2023-01-01')).toBe(true)
    expect(isSaneAuditDate(new Date().toISOString().slice(0, 10))).toBe(true)
    expect(isSaneAuditDate('2999-01-01')).toBe(false)
  })
})
