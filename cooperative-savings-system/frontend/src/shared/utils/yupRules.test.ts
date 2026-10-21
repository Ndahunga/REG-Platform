import * as yup from 'yup'
import { describe, expect, it } from 'vitest'
import { todayInKigaliIso } from './rwandaCooperative'
import { dateField, integerField, moneyField } from './yupRules'

const messageFor = async (schema: yup.Schema, value: unknown) => {
  try {
    await schema.validate(value)
    return null
  } catch (error) {
    return (error as yup.ValidationError).message
  }
}

const shiftDays = (iso: string, days: number) => {
  const d = new Date(`${iso}T00:00:00Z`)
  d.setUTCDate(d.getUTCDate() + days)
  return d.toISOString().slice(0, 10)
}

describe('moneyField', () => {
  const required = moneyField({ label: 'Amount' })

  it.each([
    ['', 'Amount is required'],
    ['abc', 'Enter a valid amount'],
    ['NaN', 'Enter a valid amount'],
    ['Infinity', 'Enter a valid amount'],
    ['1e3', 'Enter a valid amount'],
    ['-5', 'Amount cannot be negative'],
    ['0', 'Amount must be greater than 0'],
    ['0.001', 'Amount must be at least 0.01'],
    ['0.0050', 'Amount must be at least 0.01'],
    ['1.23456', 'Use at most 4 decimal places'],
    ['1000000000000000', 'Amount is too large'],
  ])('rejects "%s"', async (value, message) => {
    expect(await messageFor(required, value)).toBe(message)
  })

  it('accepts amounts the backend accepts (at least 0.01, up to 4 decimals)', async () => {
    for (const ok of ['0.01', '1', '5000.5', '12345.6789', '  250  ']) {
      expect(await messageFor(required, ok)).toBeNull()
    }
  })

  it('supports optional and zero-allowed amounts', async () => {
    expect(await messageFor(moneyField({ allowEmpty: true }), '')).toBeNull()
    expect(await messageFor(moneyField({ allowEmpty: true, allowZero: true }), '0')).toBeNull()
    expect(await messageFor(moneyField({ allowZero: true }), '')).toBe('Amount is required')
    expect(await messageFor(moneyField({ allowEmpty: true }), '0')).toBe('Amount must be greater than 0')
  })
})

describe('dateField', () => {
  const today = todayInKigaliIso()

  it('requires a real date that is not in the future', async () => {
    const schema = dateField({ label: 'Payment date' })
    expect(await messageFor(schema, '')).toBe('Payment date is required')
    expect(await messageFor(schema, '2026-02-31')).toBe('Enter a valid payment date')
    expect(await messageFor(schema, shiftDays(today, 2))).toBe('Payment date cannot be in the future')
    expect(await messageFor(schema, today)).toBeNull()
    expect(await messageFor(schema, '2020-01-15')).toBeNull()
  })

  it('can be optional and can allow future dates', async () => {
    expect(await messageFor(dateField({ required: false }), '')).toBeNull()
    expect(await messageFor(dateField({ noFuture: false }), shiftDays(today, 30))).toBeNull()
  })
})

describe('integerField', () => {
  const term = integerField({ min: 1, max: 600, message: 'bad term' })

  it('accepts blank (optional) and whole numbers in range', async () => {
    for (const ok of ['', '1', '600', ' 12 ']) expect(await messageFor(term, ok)).toBeNull()
  })

  it('rejects out-of-range, fractional and non-numeric values', async () => {
    for (const bad of ['0', '601', '1.5', '-3', 'abc', '1e2']) {
      expect(await messageFor(term, bad)).toBe('bad term')
    }
  })

  it('can be required', async () => {
    expect(await messageFor(integerField({ min: 0, max: 5, required: true, message: 'x' }), '')).toBe('x')
  })
})
