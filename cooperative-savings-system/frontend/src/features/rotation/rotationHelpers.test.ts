import { describe, expect, it } from 'vitest'
import en from '@/i18n/locales/en.json'
import rw from '@/i18n/locales/rw.json'
import {
  bpsToPercent,
  cycleMonths,
  firstSelectableMonth,
  monthKey,
  parseMonthInput,
  percentToBps,
  resolveRotatingBps,
} from './rotationHelpers'

describe('basis points and percentages', () => {
  it('formats basis points as a plain percentage', () => {
    expect(bpsToPercent(7000)).toBe('70')
    expect(bpsToPercent(3050)).toBe('30.5')
    expect(bpsToPercent(3333)).toBe('33.33')
    expect(bpsToPercent(10000)).toBe('100')
  })

  it('parses a typed percentage into whole basis points', () => {
    expect(percentToBps('70')).toBe(7000)
    expect(percentToBps(' 33,33 ')).toBe(3333)
    expect(percentToBps('0.01')).toBe(1)
    expect(percentToBps('100')).toBe(10000)
  })

  it('rejects text, too many decimals and out-of-range values', () => {
    expect(percentToBps('')).toBeNull()
    expect(percentToBps('abc')).toBeNull()
    expect(percentToBps('12.345')).toBeNull()
    expect(percentToBps('-5')).toBeNull()
    expect(percentToBps('100.5')).toBeNull()
    expect(percentToBps('1000')).toBeNull()
  })
})

describe('resolveRotatingBps', () => {
  it('is always 100% rotating for a Rotating scheme, whatever was typed', () => {
    expect(resolveRotatingBps('ROTATING', '')).toEqual({ bps: 10000 })
    expect(resolveRotatingBps('ROTATING', '40')).toEqual({ bps: 10000 })
  })

  it('needs a valid split with both portions positive for a Hybrid scheme', () => {
    expect(resolveRotatingBps('HYBRID', '')).toEqual({ problem: 'required' })
    expect(resolveRotatingBps('HYBRID', 'x')).toEqual({ problem: 'invalid' })
    expect(resolveRotatingBps('HYBRID', '0')).toEqual({ problem: 'hybridBounds' })
    expect(resolveRotatingBps('HYBRID', '100')).toEqual({ problem: 'hybridBounds' })
    expect(resolveRotatingBps('HYBRID', '70')).toEqual({ bps: 7000 })
    expect(resolveRotatingBps('HYBRID', '99.99')).toEqual({ bps: 9999 })
    expect(resolveRotatingBps('HYBRID', '0.01')).toEqual({ bps: 1 })
  })
})

describe('months', () => {
  it('parses and formats month values', () => {
    expect(parseMonthInput('2026-03')).toEqual({ year: 2026, month: 3 })
    expect(parseMonthInput('2026-13')).toBeNull()
    expect(parseMonthInput('')).toBeNull()
    expect(monthKey(2026, 3)).toBe('2026-03')
  })

  it('offers the first month that has not begun', () => {
    expect(firstSelectableMonth(new Date(2026, 0, 15))).toBe('2026-02')
    expect(firstSelectableMonth(new Date(2026, 11, 31))).toBe('2027-01')
  })

  it('lays a cycle out one month per recipient across a year end', () => {
    expect(cycleMonths('2026-11', 4)).toEqual(['2026-11', '2026-12', '2027-01', '2027-02'])
    expect(cycleMonths('bad', 3)).toEqual([])
    expect(cycleMonths('2026-05', 0)).toEqual([])
  })
})

describe('rotation translations', () => {
  function keys(value: unknown, prefix = ''): string[] {
    if (value && typeof value === 'object') {
      return Object.entries(value as Record<string, unknown>).flatMap(([k, v]) =>
        keys(v, prefix ? `${prefix}.${k}` : k),
      )
    }
    return [prefix]
  }

  it('has every rotation key in English and Kinyarwanda, none empty', () => {
    const enKeys = keys(en.rotation).sort()
    const rwKeys = keys(rw.rotation).sort()
    expect(rwKeys).toEqual(enKeys)
    expect(keys(en.operatingModel).sort()).toEqual(keys(rw.operatingModel).sort())
    expect(en.nav.rotation).toBeTruthy()
    expect(rw.nav.rotation).toBeTruthy()
    expect(en.pages.rotation.title).toBeTruthy()
    expect(rw.pages.rotation.title).toBeTruthy()
    const empty = [...keys(en.rotation), ...keys(rw.rotation)].filter((k) => {
      const value = k.split('.').reduce<unknown>((acc, part) => (acc as Record<string, unknown>)?.[part], en.rotation)
      return value === ''
    })
    expect(empty).toEqual([])
  })
})
