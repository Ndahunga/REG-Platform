import { describe, expect, it } from 'vitest'
import { ROUTES, SUPPORT_SECTIONS } from '@/shared/constants/routes'
import { FINANCE_ACCESS_ROLES, LOAN_OPS_ROLES } from '@/shared/types/auth'
import { adminModuleNavItems, canAccessNavItem, memberNavItems } from './navItems'

describe('payments navigation', () => {
  it('places Payments in the Finance group for members and for finance and loan officers', () => {
    const memberItem = memberNavItems.find((item) => item.path === ROUTES.payments)
    expect(memberItem?.sidebarGroup).toBe('finance')
    expect(memberItem?.labelKey).toBe('nav.payments')
    expect(memberItem?.roles).toBeUndefined()

    const officerItem = adminModuleNavItems.find((item) => item.path === ROUTES.payments)
    expect(officerItem?.sidebarGroup).toBe('finance')
    for (const role of [...FINANCE_ACCESS_ROLES, ...LOAN_OPS_ROLES]) {
      expect(canAccessNavItem(officerItem!, [role])).toBe(true)
    }
    expect(canAccessNavItem(officerItem!, ['MEMBER'])).toBe(false)
  })

  it('adds the payments section to the Super Admin support workspace and keeps one route', () => {
    expect(SUPPORT_SECTIONS).toContain('payments')
    expect(ROUTES.payments).toBe('/payments')
  })
})
