import { describe, expect, it } from 'vitest'
import {
  isValidCooperativeEmail,
  isValidRegistrationDate,
  isValidRegistrationNumber,
  isValidRwandanPhone,
  normalizeRegistrationNumber,
  normalizeRwandanPhone,
} from './rwandaCooperative'

describe('rwandaCooperative', () => {
  it('normalizes and validates Rwandan phones', () => {
    expect(normalizeRwandanPhone('+250781234567')).toBe('0781234567')
    expect(isValidRwandanPhone('0781234567')).toBe(true)
    expect(isValidRwandanPhone('123')).toBe(false)
  })

  it('validates emails and registration numbers', () => {
    expect(isValidCooperativeEmail('info@terimbere.rw')).toBe(true)
    expect(isValidCooperativeEmail('bad')).toBe(false)
    expect(normalizeRegistrationNumber(' rca / 2024 / 1 ')).toBe('RCA/2024/1')
    expect(isValidRegistrationNumber('RCA/2024/0123')).toBe(true)
    expect(isValidRegistrationNumber('!!')).toBe(false)
  })

  it('rejects future registration dates', () => {
    expect(isValidRegistrationDate('2020-01-01', '2026-08-25')).toBe(true)
    expect(isValidRegistrationDate('2026-08-26', '2026-08-25')).toBe(false)
    expect(isValidRegistrationDate('1949-12-31', '2026-08-25')).toBe(false)
  })
})
