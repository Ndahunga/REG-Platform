import { describe, expect, it } from 'vitest'
import {
  cooperativeCreateSchema,
  cooperativeFormDefaults,
  cooperativeFormSchema,
  toCooperativePayload,
} from './cooperativeFormSchema'

describe('toCooperativePayload', () => {
  it('normalizes Rwanda fields and locks currency to RWF', () => {
    const payload = toCooperativePayload({
      ...cooperativeFormDefaults,
      name: '  Demo Coop  ',
      currency: 'USD',
      monthlyContributionAmount: '5000',
      financialYearStartMonth: 3,
      contributionDueDay: 10,
      description: '  ',
      registrationNumber: ' rca / 2024 / 0123 ',
      contactEmail: 'Admin@Example.COM',
      contactPhone: '+250 781 234 567',
      registrationDate: '2024-06-01',
      operatingModel: 'HYBRID',
    })

    expect(payload).toEqual({
      name: 'Demo Coop',
      description: undefined,
      registrationNumber: 'RCA/2024/0123',
      contactEmail: 'admin@example.com',
      contactPhone: '0781234567',
      address: undefined,
      currency: 'RWF',
      financialYearStartMonth: 3,
      monthlyContributionAmount: '5000',
      contributionDueDay: 10,
      registrationDate: '2024-06-01',
      operatingModel: 'HYBRID',
      subscriptionInitialization: 'START_TRIAL',
    })
  })

  it('never sends the operating model when editing', () => {
    const payload = toCooperativePayload({ ...cooperativeFormDefaults, operatingModel: 'ROTATING' }, 'edit')
    expect(payload).not.toHaveProperty('operatingModel')
  })

  it('maps an unchecked trial to NONE and omits president when assigning later', () => {
    const payload = toCooperativePayload({
      ...cooperativeFormDefaults,
      name: 'No Trial Coop',
      registrationNumber: 'RCA/2024/0123',
      contactEmail: 'info@terimbere.rw',
      contactPhone: '0781234567',
      registrationDate: '2024-01-15',
      startTrial: false,
      assignPresidentNow: false,
    })

    expect(payload.subscriptionInitialization).toBe('NONE')
    expect(payload.president).toBeUndefined()
  })

  it('includes existing-user president when assigning now', () => {
    const payload = toCooperativePayload({
      ...cooperativeFormDefaults,
      name: 'Existing Pres Coop',
      registrationNumber: 'RCA/2024/0123',
      contactEmail: 'info@terimbere.rw',
      contactPhone: '0781234567',
      registrationDate: '2024-01-15',
      assignPresidentNow: true,
      presidentMode: 'existing',
      presidentUserId: ' 11111111-1111-1111-1111-111111111111 ',
    })

    expect(payload.subscriptionInitialization).toBe('START_TRIAL')
    expect(payload.president).toEqual({ userId: '11111111-1111-1111-1111-111111111111' })
  })

  it('includes new-user president fields when assigning now', () => {
    const payload = toCooperativePayload({
      ...cooperativeFormDefaults,
      name: 'New Pres Coop',
      registrationNumber: 'RCA/2024/0123',
      contactEmail: 'info@terimbere.rw',
      contactPhone: '0781234567',
      registrationDate: '2024-01-15',
      assignPresidentNow: true,
      presidentMode: 'new',
      presidentUsername: 'pat.president',
      presidentEmail: 'Pat@Example.COM',
      presidentFirstName: 'Pat',
      presidentLastName: 'President',
      presidentPhone: '0781112233',
    })

    expect(payload.president).toEqual({
      username: 'pat.president',
      email: 'pat@example.com',
      firstName: 'Pat',
      lastName: 'President',
      phone: '0781112233',
      temporaryPassword: undefined,
    })
  })

  it('omits onboarding fields in edit mode', () => {
    const payload = toCooperativePayload(
      {
        ...cooperativeFormDefaults,
        name: 'Edit Coop',
        registrationNumber: 'RCA/2024/0123',
        contactEmail: 'info@terimbere.rw',
        contactPhone: '0781234567',
        registrationDate: '2024-01-15',
        startTrial: false,
        assignPresidentNow: true,
        presidentUserId: 'abc',
      },
      'edit',
    )

    expect(payload.subscriptionInitialization).toBeUndefined()
    expect(payload.president).toBeUndefined()
  })
})

describe('cooperativeFormSchema', () => {
  const valid = {
    ...cooperativeFormDefaults,
    name: 'Terimbere Savings',
    registrationNumber: 'RCA/2024/0123',
    contactEmail: 'info@terimbere.rw',
    contactPhone: '0781234567',
    registrationDate: '2024-01-15',
    monthlyContributionAmount: '10000',
    contributionDueDay: 5,
  }

  it('accepts a valid cooperative payload', async () => {
    await expect(cooperativeFormSchema.validate(valid)).resolves.toMatchObject({
      name: 'Terimbere Savings',
      currency: 'RWF',
    })
  })

  it('rejects non-Rwandan phone numbers', async () => {
    await expect(
      cooperativeFormSchema.validate({ ...valid, contactPhone: '12345' }),
    ).rejects.toThrow(/Rwandan mobile/i)
  })

  it('rejects invalid registration numbers', async () => {
    await expect(
      cooperativeFormSchema.validate({ ...valid, registrationNumber: 'ab' }),
    ).rejects.toThrow(/registration number/i)
  })

  it('requires registration date', async () => {
    await expect(
      cooperativeFormSchema.validate({ ...valid, registrationDate: '' }),
    ).rejects.toThrow(/required/i)
  })

  it('requires president fields when assigning now', async () => {
    await expect(
      cooperativeFormSchema.validate({
        ...valid,
        assignPresidentNow: true,
        presidentMode: 'new',
      }),
    ).rejects.toThrow(/required/i)
  })
})

describe('operating model in the create form', () => {
  const filled = {
    ...cooperativeFormDefaults,
    name: 'Demo',
    registrationNumber: 'RCA/2024/0123',
    contactEmail: 'admin@example.com',
    contactPhone: '0781234567',
    registrationDate: '2024-06-01',
  }

  it('has no default selection', () => {
    expect(cooperativeFormDefaults.operatingModel).toBe('')
  })

  it('requires a model when creating, with a clear message', async () => {
    await expect(cooperativeCreateSchema.validate(filled)).rejects.toThrow(/operating model/i)
    await expect(cooperativeCreateSchema.validate({ ...filled, operatingModel: 'ROTATING' })).resolves.toBeDefined()
  })

  it('does not require one when editing (a legacy scheme has none)', async () => {
    await expect(cooperativeFormSchema.validate(filled)).resolves.toBeDefined()
  })
})
