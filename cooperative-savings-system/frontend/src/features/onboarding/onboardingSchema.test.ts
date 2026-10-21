import { describe, expect, it } from 'vitest'
import {
  ONBOARDING_STEP1_FIELDS,
  onboardingDefaults,
  onboardingSchema,
  toOnboardingPayload,
} from './onboardingSchema'

const valid: typeof onboardingDefaults = {
  ...onboardingDefaults,
  name: '  Public Scheme  ',
  contactEmail: 'Scheme@Example.COM',
  contactPhone: '+250 781 234 567',
  address: '  Kigali  ',
  financialYearStartMonth: 3,
  monthlyContributionAmount: '5000',
  contributionDueDay: 10,
  registrationDate: '2024-01-15',
  operatingModel: 'ACCUMULATING',
  firstName: 'Pat',
  lastName: 'President',
  username: 'pat.president',
  email: 'Pat@Example.COM',
  phone: '0781112233',
  password: 'SignupPass1!',
  confirmPassword: 'SignupPass1!',
}

describe('onboardingSchema operating model', () => {
  it('has no default model and asks for one in step 1', () => {
    expect(onboardingDefaults.operatingModel).toBe('')
    expect(ONBOARDING_STEP1_FIELDS).toContain('operatingModel')
  })

  it('rejects a missing or unsupported model and accepts each of the three', async () => {
    await expect(onboardingSchema.validate({ ...valid, operatingModel: '' })).rejects.toThrow(/operating model/i)
    await expect(
      onboardingSchema.validate({ ...valid, operatingModel: 'WEEKLY' as never }),
    ).rejects.toThrow(/operating model/i)
    for (const model of ['ACCUMULATING', 'ROTATING', 'HYBRID'] as const) {
      await expect(onboardingSchema.validate({ ...valid, operatingModel: model })).resolves.toBeDefined()
    }
  })

  it('sends the selected model in the cooperative group', () => {
    expect(toOnboardingPayload({ ...valid, operatingModel: 'HYBRID' }).cooperative.operatingModel).toBe('HYBRID')
  })
})

describe('onboardingSchema', () => {
  it('accepts a valid public onboarding form', async () => {
    await expect(onboardingSchema.validate(valid)).resolves.toMatchObject({
      name: 'Public Scheme',
      firstName: 'Pat',
    })
  })

  it('requires saving scheme name', async () => {
    await expect(onboardingSchema.validate({ ...valid, name: '' })).rejects.toThrow(/required/i)
  })

  it('does not ask for a registration number anywhere in the public signup form', async () => {
    expect(Object.keys(onboardingDefaults)).not.toContain('registrationNumber')
    expect(ONBOARDING_STEP1_FIELDS as string[]).not.toContain('registrationNumber')
    expect(Object.keys(onboardingSchema.fields)).not.toContain('registrationNumber')
    // a form without one is valid, so step 1 can proceed
    await expect(onboardingSchema.validate(valid)).resolves.toBeDefined()
  })

  it('rejects non-Rwandan cooperative phones', async () => {
    await expect(
      onboardingSchema.validate({ ...valid, contactPhone: '12345' }),
    ).rejects.toThrow(/Rwandan mobile/i)
  })

  it('requires creator first name', async () => {
    await expect(onboardingSchema.validate({ ...valid, firstName: '' })).rejects.toThrow(/required/i)
  })

  it('rejects a short password', async () => {
    await expect(
      onboardingSchema.validate({ ...valid, password: 'short', confirmPassword: 'short' }),
    ).rejects.toThrow(/8 characters/i)
  })

  it('requires matching passwords', async () => {
    await expect(
      onboardingSchema.validate({ ...valid, confirmPassword: 'OtherPass1!' }),
    ).rejects.toThrow(/match/i)
  })
})

describe('toOnboardingPayload', () => {
  it('builds cooperative and creator groups without role or subscription overrides', () => {
    const payload = toOnboardingPayload(valid)

    expect(payload).toEqual({
      cooperative: {
        name: 'Public Scheme',
        contactEmail: 'scheme@example.com',
        contactPhone: '0781234567',
        address: 'Kigali',
        currency: 'RWF',
        financialYearStartMonth: 3,
        monthlyContributionAmount: '5000',
        contributionDueDay: 10,
        registrationDate: '2024-01-15',
        operatingModel: 'ACCUMULATING',
      },
      creator: {
        firstName: 'Pat',
        lastName: 'President',
        username: 'pat.president',
        email: 'pat@example.com',
        phone: '0781112233',
        password: 'SignupPass1!',
      },
    })
    expect(payload).not.toHaveProperty('role')
    // nothing is invented for a registration number the user was never asked for
    expect(payload.cooperative).not.toHaveProperty('registrationNumber')
    expect(JSON.stringify(payload)).not.toMatch(/registrationNumber/i)
    expect(payload.cooperative).not.toHaveProperty('subscriptionInitialization')
    expect(payload.cooperative).not.toHaveProperty('status')
    expect(payload.creator).not.toHaveProperty('role')
    expect(payload.creator).not.toHaveProperty('confirmPassword')
  })

  it('omits blank optional creator phone', () => {
    const payload = toOnboardingPayload({ ...valid, phone: '   ' })
    expect(payload.creator.phone).toBeUndefined()
  })
})
