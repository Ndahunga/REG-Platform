import { describe, expect, it } from 'vitest'
import type { CooperativeSettings } from '@/shared/types/cooperativeSettings'
import {
  cooperativeSettingsDefaults,
  toSettingsFormValues,
  toSettingsPayload,
  validateBaseSharePrice,
} from './settingsHelpers'

describe('settingsHelpers', () => {
  it('maps API settings into form values', () => {
    const settings: CooperativeSettings = {
      id: '1',
      cooperativeId: 'c1',
      timezone: 'UTC',
      locale: 'rw',
      notifyContributions: false,
      notifyLoans: true,
      notifyFines: false,
      notifyPayouts: true,
      baseSharePrice: '100000.0000',
    }
    expect(toSettingsFormValues(settings)).toEqual({
      timezone: 'UTC',
      locale: 'rw',
      notifyContributions: false,
      notifyLoans: true,
      notifyFines: false,
      notifyPayouts: true,
      baseSharePrice: '100000.0000',
    })
  })

  it('builds update payload with trimmed defaults', () => {
    expect(
      toSettingsPayload({
        ...cooperativeSettingsDefaults,
        timezone: '  Africa/Kigali  ',
        locale: '  en  ',
        notifyContributions: false,
      }),
    ).toEqual({
      timezone: 'Africa/Kigali',
      locale: 'en',
      notifyContributions: false,
      notifyLoans: true,
      notifyFines: true,
      notifyPayouts: true,
      baseSharePrice: null,
      clearBaseSharePrice: true,
    })
  })

  it('includes a configured base share price in the update payload', () => {
    expect(
      toSettingsPayload({
        ...cooperativeSettingsDefaults,
        baseSharePrice: ' 100000 ',
      }),
    ).toMatchObject({
      baseSharePrice: 100000,
      clearBaseSharePrice: false,
    })
  })

  it('rejects zero and negative base share prices in the form', () => {
    expect(validateBaseSharePrice('')).toBe(true)
    expect(validateBaseSharePrice('100000')).toBe(true)
    expect(validateBaseSharePrice('0')).not.toBe(true)
    expect(validateBaseSharePrice('-1')).not.toBe(true)
  })
})
