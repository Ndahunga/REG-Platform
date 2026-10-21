import { describe, expect, it } from 'vitest'
import { ROLES_IN_COOPERATIVE, normalizeRoleInCooperative } from './member'
import { dutyRoleCode } from '@/shared/components/RoleDutiesNote'

describe('ROLES_IN_COOPERATIVE', () => {
  it('lists Ikimina offices for the add-member form', () => {
    expect(ROLES_IN_COOPERATIVE).toEqual([
      'MEMBER',
      'PRESIDENT',
      'VICE_PRESIDENT',
      'SECRETARY',
      'ACCOUNTANT',
      'LOAN_OFFICER',
    ])
  })
})

describe('normalizeRoleInCooperative', () => {
  it('maps the legacy cooperative admin title to President', () => {
    expect(normalizeRoleInCooperative('COOPERATIVE_ADMIN')).toBe('PRESIDENT')
  })
})

describe('dutyRoleCode', () => {
  it('prefers the highest office from JWT roles', () => {
    expect(dutyRoleCode(undefined, ['MEMBER', 'ACCOUNTANT'])).toBe('ACCOUNTANT')
  })
})
