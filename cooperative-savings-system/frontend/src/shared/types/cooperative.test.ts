import { describe, expect, it } from 'vitest'
import { isOnboardingIncomplete, mapCooperativeSummary, type CooperativeSummary } from './cooperative'

describe('mapCooperativeSummary', () => {
  it('normalizes id and default currency', () => {
    const raw = {
      id: 7,
      name: 'Ubumwe',
      status: 'ACTIVE',
      currency: '',
      logoUrl: null,
    } as unknown as CooperativeSummary

    expect(mapCooperativeSummary(raw)).toEqual({
      id: '7',
      name: 'Ubumwe',
      status: 'ACTIVE',
      currency: 'RWF',
      logoUrl: null,
      operatingModel: null,
    })
  })

  it('keeps a recorded operating model and maps a missing one to null (legacy scheme)', () => {
    const base = { id: 'a', name: 'A', status: 'ACTIVE', currency: 'RWF' } as unknown as CooperativeSummary
    expect(mapCooperativeSummary({ ...base, operatingModel: 'ROTATING' }).operatingModel).toBe('ROTATING')
    expect(mapCooperativeSummary(base).operatingModel).toBeNull()
  })
})

describe('isOnboardingIncomplete', () => {
  it('is true only for AWAITING_PRESIDENT', () => {
    expect(isOnboardingIncomplete({ onboardingState: 'AWAITING_PRESIDENT' })).toBe(true)
    expect(isOnboardingIncomplete({ onboardingState: 'COMPLETE' })).toBe(false)
    expect(isOnboardingIncomplete({ onboardingState: undefined })).toBe(false)
    expect(isOnboardingIncomplete(null)).toBe(false)
  })
})
