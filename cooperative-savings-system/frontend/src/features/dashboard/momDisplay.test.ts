import { describe, expect, it } from 'vitest'
import { formatMomPercent, resolveMomDisplay } from './momDisplay'

describe('resolveMomDisplay', () => {
  it('formats positive and negative deltas', () => {
    expect(resolveMomDisplay(12.4, 'UP')).toEqual({ state: 'UP', percent: 12.4, arrow: 'up' })
    expect(resolveMomDisplay(-8.2, 'DOWN')).toEqual({ state: 'DOWN', percent: -8.2, arrow: 'down' })
  })

  it('handles no-baseline without NaN', () => {
    const mom = resolveMomDisplay(null, 'NO_BASELINE')
    expect(mom.percent).toBeNull()
    expect(mom.state).toBe('NO_BASELINE')
    expect(formatMomPercent(mom.percent)).toBe('')
  })

  it('never returns Infinity from garbage input', () => {
    const mom = resolveMomDisplay(Number.POSITIVE_INFINITY, 'UP')
    expect(mom.percent).toBeNull()
    expect(Number.isFinite(mom.percent as number)).toBe(false)
  })

  it('flat when zero', () => {
    expect(resolveMomDisplay(0, 'FLAT')).toEqual({ state: 'FLAT', percent: 0, arrow: 'flat' })
  })
})
