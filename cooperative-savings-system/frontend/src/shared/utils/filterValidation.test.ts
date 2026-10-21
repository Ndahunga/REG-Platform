import { describe, expect, it } from 'vitest'
import dayjs from 'dayjs'
import {
  isIsoDate,
  todayIsoDate,
  validateOptionalDateRange,
  validateOptionalYearMonth,
} from './filterValidation'

const today = dayjs('2026-08-26')

describe('isIsoDate', () => {
  it('accepts calendar dates only', () => {
    expect(isIsoDate('2026-08-26')).toBe(true)
    expect(isIsoDate('2026-13-01')).toBe(false)
    expect(isIsoDate('26-08-2026')).toBe(false)
    expect(isIsoDate('')).toBe(false)
  })
})

describe('todayIsoDate', () => {
  it('formats YYYY-MM-DD', () => {
    expect(todayIsoDate(today)).toBe('2026-08-26')
  })
})

describe('validateOptionalDateRange', () => {
  it('allows empty or same-day ranges', () => {
    expect(validateOptionalDateRange('', '', today)).toBeNull()
    expect(validateOptionalDateRange('2026-08-26', '2026-08-26', today)).toBeNull()
    expect(validateOptionalDateRange('2026-01-01', '', today)).toBeNull()
  })

  it('rejects inverted and future dates', () => {
    expect(validateOptionalDateRange('2026-08-10', '2026-08-01', today)).toBe('fromAfterTo')
    expect(validateOptionalDateRange('2026-08-27', '', today)).toBe('futureFrom')
    expect(validateOptionalDateRange('', '2026-09-01', today)).toBe('futureTo')
  })
})

describe('validateOptionalYearMonth', () => {
  it('allows year-only or month-only', () => {
    expect(validateOptionalYearMonth('', '', today)).toBeNull()
    expect(validateOptionalYearMonth('2026', '', today)).toBeNull()
    expect(validateOptionalYearMonth('', '8', today)).toBeNull()
    expect(validateOptionalYearMonth('2026', '8', today)).toBeNull()
  })

  it('rejects future periods and invalid values', () => {
    expect(validateOptionalYearMonth('2026', '9', today)).toBe('futureYearMonth')
    expect(validateOptionalYearMonth('2027', '', today)).toBe('futureYearMonth')
    expect(validateOptionalYearMonth('1999', '', today)).toBe('invalidYear')
    expect(validateOptionalYearMonth('', '13', today)).toBe('invalidMonth')
  })
})
