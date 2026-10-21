import { describe, expect, it } from 'vitest'
import { formatMoney, formatOptionalMoney, normalizeDecimalString } from './formatMoney'

describe('normalizeDecimalString', () => {
  it('accepts decimal strings without float conversion', () => {
    expect(normalizeDecimalString('1234.50')).toBe('1234.5')
    expect(normalizeDecimalString('0.01')).toBe('0.01')
    expect(normalizeDecimalString('-99.99')).toBe('-99.99')
  })

  it('rejects invalid input', () => {
    expect(() => normalizeDecimalString('12.3.4')).toThrow()
    expect(() => normalizeDecimalString('abc')).toThrow()
  })
})

describe('formatMoney', () => {
  it('formats large amounts with grouping and currency', () => {
    const formatted = formatMoney('1000000', { currency: 'RWF', locale: 'en-RW' })
    expect(formatted).toContain('1,000,000')
    expect(formatted.includes('RWF') || formatted.includes('FRw') || formatted.includes('RF')).toBe(
      true,
    )
  })

  it('preserves two-decimal money strings without binary float drift', () => {
    const formatted = formatMoney('0.10', {
      currency: 'USD',
      locale: 'en-US',
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    })
    expect(formatted).toContain('0.10')
  })

  it('handles amounts that float would misrepresent if summed naively', () => {
    // 0.1 + 0.2 as float is 0.30000000000000004 — we format the string "0.3"
    expect(formatMoney('0.3', { currency: 'USD', locale: 'en-US', maximumFractionDigits: 2 })).toContain(
      '0.3',
    )
  })

  it('renders an em dash for null, undefined, or blank optional amounts', () => {
    expect(formatMoney(null)).toBe('—')
    expect(formatMoney(undefined)).toBe('—')
    expect(formatMoney('')).toBe('—')
    expect(formatMoney('   ')).toBe('—')
  })

  it('still formats zero as a real amount', () => {
    expect(formatMoney(0)).toMatch(/0/)
    expect(formatMoney('0')).toMatch(/0/)
  })
})

describe('formatOptionalMoney', () => {
  it('does not throw for null or invalid optional amounts', () => {
    expect(formatOptionalMoney(null)).toBe('—')
    expect(formatOptionalMoney(undefined)).toBe('—')
    expect(formatOptionalMoney('abc')).toBe('—')
    expect(formatOptionalMoney('12.3.4')).toBe('—')
  })

  it('formats valid money the same as formatMoney', () => {
    expect(formatOptionalMoney('2500')).toBe(formatMoney('2500'))
  })
})
