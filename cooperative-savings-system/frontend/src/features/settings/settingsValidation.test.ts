import { describe, expect, it } from 'vitest'
import { validateBaseSharePrice } from './settingsHelpers'

describe('validateBaseSharePrice', () => {
  it('accepts a blank value (clears the price) and amounts the backend accepts', () => {
    for (const ok of ['', '  ', '1', '1000', '0.01', '1234.5678', '999999999999999.9999']) {
      expect(validateBaseSharePrice(ok)).toBe(true)
    }
  })

  it('rejects zero, negatives and non-numeric text', () => {
    for (const bad of ['0', '0.0000', '-5', 'abc', 'NaN', 'Infinity', '1e3', '1,000']) {
      expect(validateBaseSharePrice(bad)).toBe('Base share price must be greater than zero, or left empty to clear')
    }
  })

  it('rejects more than 4 decimals and more than 15 whole digits (NUMERIC(19,4))', () => {
    expect(validateBaseSharePrice('0.00001')).toBe('Use at most 4 decimal places')
    expect(validateBaseSharePrice('1.23456')).toBe('Use at most 4 decimal places')
    expect(validateBaseSharePrice('1000000000000000')).toBe('Base share price is too large')
    expect(validateBaseSharePrice('1e20')).toBe('Base share price must be greater than zero, or left empty to clear')
  })
})
