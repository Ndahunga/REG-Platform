import { describe, expect, it } from 'vitest'
import {
  checkMoney,
  exceedsLength,
  isFutureIsoDate,
  isMoneyValid,
  isValidIsoDate,
} from './formValidation'

describe('checkMoney', () => {
  it.each([
    ['100', null],
    ['0.01', null],
    ['1234567890123.4567', null],
    ['999999999999999', null],
    [' 50 ', null],
  ])('accepts %s', (value, expected) => {
    expect(checkMoney(value)).toBe(expected)
  })

  it.each([
    ['', 'required'],
    ['   ', 'required'],
    [null, 'required'],
    ['abc', 'invalid'],
    ['NaN', 'invalid'],
    ['Infinity', 'invalid'],
    ['1e3', 'invalid'],
    ['1,000', 'invalid'],
    ['1.', 'invalid'],
    ['.5', 'invalid'],
    ['-5', 'negative'],
    ['-abc', 'invalid'],
    ['0', 'notPositive'],
    ['0.0000', 'notPositive'],
    ['1.23456', 'precision'],
    ['1000000000000000', 'tooLarge'],
  ])('rejects %s as %s', (value, expected) => {
    expect(checkMoney(value as string | null)).toBe(expected)
  })

  it('allows zero and blank only when asked', () => {
    expect(checkMoney('0', { allowZero: true })).toBeNull()
    expect(checkMoney('', { allowEmpty: true })).toBeNull()
    expect(isMoneyValid('', { allowEmpty: true })).toBe(true)
    expect(isMoneyValid('0')).toBe(false)
  })
})

describe('dates and lengths', () => {
  it('validates real calendar dates only', () => {
    expect(isValidIsoDate('2026-02-28')).toBe(true)
    expect(isValidIsoDate('2026-02-31')).toBe(false)
    expect(isValidIsoDate('2026-2-3')).toBe(false)
    expect(isValidIsoDate('')).toBe(false)
    expect(isValidIsoDate('not a date')).toBe(false)
  })

  it('detects future dates against a supplied today', () => {
    expect(isFutureIsoDate('2026-05-03', '2026-05-02')).toBe(true)
    expect(isFutureIsoDate('2026-05-02', '2026-05-02')).toBe(false)
    expect(isFutureIsoDate('2026-05-01', '2026-05-02')).toBe(false)
  })

  it('measures trimmed length', () => {
    expect(exceedsLength('a'.repeat(128), 128)).toBe(false)
    expect(exceedsLength('a'.repeat(129), 128)).toBe(true)
    expect(exceedsLength('  ' + 'a'.repeat(128) + '  ', 128)).toBe(false)
    expect(exceedsLength(null, 5)).toBe(false)
  })
})
