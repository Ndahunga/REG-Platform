import { describe, expect, it } from 'vitest'
import { mapContributionPeriodLine } from './contribution'

const base = {
  memberUserId: 'u-1',
  expectedAmount: '5000',
  paidAmount: '0',
  status: 'PENDING',
} as never

describe('mapContributionPeriodLine', () => {
  it('maps the backend memberName to fullName', () => {
    const line = mapContributionPeriodLine({ ...(base as object), memberName: 'Alice Uwase' } as never)
    expect(line.fullName).toBe('Alice Uwase')
    expect(line.username).toBe('')
  })

  it('keeps fullName when the caller already supplies it, and preserves username', () => {
    const line = mapContributionPeriodLine({
      ...(base as object),
      fullName: 'Carine Ingabire',
      memberName: 'Ignored',
      username: ' carine ',
    } as never)
    expect(line.fullName).toBe('Carine Ingabire')
    expect(line.username).toBe('carine')
  })

  it('yields empty strings, never undefined, when no name is present', () => {
    const line = mapContributionPeriodLine(base)
    expect(line.fullName).toBe('')
    expect(line.username).toBe('')
  })
})
