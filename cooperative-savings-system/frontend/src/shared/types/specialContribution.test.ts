import { describe, expect, it } from 'vitest'
import { mapSpecialContribution } from './specialContribution'

const base = { id: 1, campaignId: 2, memberUserId: 'u-1', amount: '1000', status: 'PENDING' } as never

describe('mapSpecialContribution', () => {
  it('maps the backend memberName to fullName so the table shows a name, not a UUID', () => {
    const row = mapSpecialContribution({ ...(base as object), memberName: 'Alice Uwase' } as never)
    expect(row.fullName).toBe('Alice Uwase')
    expect(row.memberUserId).toBe('u-1')
  })

  it('keeps fullName when supplied and leaves it undefined when there is no name at all', () => {
    expect(mapSpecialContribution({ ...(base as object), fullName: 'Carine', memberName: 'x' } as never).fullName).toBe('Carine')
    expect(mapSpecialContribution(base).fullName).toBeUndefined()
  })
})
