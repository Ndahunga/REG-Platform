import { describe, expect, it } from 'vitest'
import { canShowQuickAction, QUICK_ACTIONS } from '@/shared/components/QuickActionsMenu'
import {
  PERMISSION_MEMBERSHIP_MANAGE,
  ROLE_ACCOUNTANT,
  ROLE_LOAN_OFFICER,
  ROLE_MEMBER,
  ROLE_PRESIDENT,
  ROLE_SECRETARY,
  ROLE_SUPER_ADMIN,
} from '@/shared/types/auth'

const addMember = QUICK_ACTIONS.find((a) => a.id === 'add-member')!
const memberView = QUICK_ACTIONS.find((a) => a.id === 'member-view')!

describe('canShowQuickAction', () => {
  it('hides add member and member view from accountant and loan officer', () => {
    const accountant = { roles: [ROLE_ACCOUNTANT], permissions: [] }
    const loanOfficer = { roles: [ROLE_LOAN_OFFICER], permissions: [] }
    expect(canShowQuickAction(addMember, accountant)).toBe(false)
    expect(canShowQuickAction(memberView, accountant)).toBe(false)
    expect(canShowQuickAction(addMember, loanOfficer)).toBe(false)
    expect(canShowQuickAction(memberView, loanOfficer)).toBe(false)
  })

  it('does not include change password in the actions menu', () => {
    expect(QUICK_ACTIONS.some((a) => a.id === 'change-password')).toBe(false)
  })

  it('hides member actions from ordinary members', () => {
    const member = { roles: [ROLE_MEMBER], permissions: [] }
    expect(canShowQuickAction(addMember, member)).toBe(false)
    expect(canShowQuickAction(memberView, member)).toBe(false)
  })

  it('shows add and view for secretary, president, and super admin', () => {
    const secretary = {
      roles: [ROLE_SECRETARY],
      permissions: [PERMISSION_MEMBERSHIP_MANAGE],
    }
    const president = {
      roles: [ROLE_PRESIDENT],
      permissions: [PERMISSION_MEMBERSHIP_MANAGE],
    }
    const superAdmin = { roles: [ROLE_SUPER_ADMIN], permissions: [] }
    expect(canShowQuickAction(addMember, secretary)).toBe(true)
    expect(canShowQuickAction(memberView, secretary)).toBe(true)
    expect(canShowQuickAction(addMember, president)).toBe(true)
    expect(canShowQuickAction(memberView, president)).toBe(true)
    expect(canShowQuickAction(addMember, superAdmin)).toBe(true)
    expect(canShowQuickAction(memberView, superAdmin)).toBe(true)
  })
})
