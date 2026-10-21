import { describe, expect, it } from 'vitest'
import { mapMember, memberDisplayName, type Member } from './member'

describe('memberDisplayName', () => {
  it('prefers fullName when present', () => {
    expect(
      memberDisplayName({
        firstName: 'A',
        lastName: 'B',
        fullName: 'Custom Name',
      }),
    ).toBe('Custom Name')
  })

  it('falls back to first + last', () => {
    expect(memberDisplayName({ firstName: 'Jane', lastName: 'Doe' })).toBe('Jane Doe')
  })
})

describe('mapMember', () => {
  it('normalizes userId and fills fullName', () => {
    const raw = {
      userId: 42,
      firstName: 'Jean',
      lastName: 'Uwimana',
      username: 'jean.u',
      email: 'jean@example.com',
      membershipStatus: 'ACTIVE',
      accountStatus: 'ACTIVE',
      roleInCooperative: 'MEMBER',
    } as unknown as Member

    const mapped = mapMember(raw)
    expect(mapped.userId).toBe('42')
    expect(mapped.fullName).toBe('Jean Uwimana')
  })

  it('unwraps nested member from the detail payload so edit forms get current fields', () => {
    const mapped = mapMember({
      member: {
        userId: 'abc-1',
        firstName: 'Marie',
        lastName: 'Uwase',
        username: 'marie.u',
        email: 'marie@example.com',
        membershipStatus: 'ACTIVE',
        accountStatus: 'ACTIVE',
        roleInCooperative: 'MEMBER',
      },
      contributions: [{ id: 1 }],
      loans: [],
    } as unknown as Member)

    expect(mapped.userId).toBe('abc-1')
    expect(mapped.firstName).toBe('Marie')
    expect(mapped.username).toBe('marie.u')
    expect(mapped.email).toBe('marie@example.com')
    expect(mapped.contributions).toEqual([{ id: 1 }])
  })
})
