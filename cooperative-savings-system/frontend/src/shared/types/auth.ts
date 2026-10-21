export type AuthStatus = 'anonymous' | 'authenticated' | 'loading'

export interface AuthUser {
  id: string
  username: string
  email: string
  firstName: string
  lastName: string
  fullName: string
  roles: string[]
  permissions: string[]
  cooperativeIds: string[]
}

export interface LoginRequest {
  username: string
  password: string
}

export interface SignupRequest {
  username: string
  email: string
  password: string
  firstName: string
  lastName: string
  phone?: string
}

export interface LoginResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
  user: AuthUser
}

export interface ChangePasswordRequest {
  currentPassword: string
  newPassword: string
}

export interface UpdateProfileRequest {
  username: string
  email: string
  firstName: string
  lastName: string
}

export interface PasswordResetRequest {
  usernameOrEmail: string
}

export interface PasswordResetConfirmRequest {
  token: string
  newPassword: string
}

export const ROLE_SUPER_ADMIN = 'SUPER_ADMIN'
export const ROLE_COOPERATIVE_ADMIN = 'COOPERATIVE_ADMIN'
export const ROLE_PRESIDENT = 'PRESIDENT'
export const ROLE_VICE_PRESIDENT = 'VICE_PRESIDENT'
export const ROLE_SECRETARY = 'SECRETARY'
export const ROLE_ACCOUNTANT = 'ACCOUNTANT'
export const ROLE_LOAN_OFFICER = 'LOAN_OFFICER'
export const ROLE_MEMBER = 'MEMBER'

export const PERMISSION_LOAN_APPROVE = 'LOAN_APPROVE'
export const PERMISSION_FUND_AUTHORIZE = 'FUND_AUTHORIZE'
export const PERMISSION_LOAN_WRITE = 'LOAN_WRITE'
export const PERMISSION_CONTRIBUTION_WRITE = 'CONTRIBUTION_WRITE'
export const PERMISSION_SOCIAL_WRITE = 'SOCIAL_WRITE'
export const PERMISSION_MEMBERSHIP_MANAGE = 'MEMBERSHIP_MANAGE'
export const PERMISSION_PAYOUT_WRITE = 'PAYOUT_WRITE'
export const PERMISSION_FINE_WRITE = 'FINE_WRITE'
export const PERMISSION_AUDIT_READ = 'AUDIT_READ'
export const PERMISSION_SETTINGS_MANAGE = 'SETTINGS_MANAGE'
export const PERMISSION_LEDGER_READ = 'LEDGER_READ'
export const PERMISSION_INVESTMENT_WRITE = 'INVESTMENT_WRITE'
export const PERMISSION_INCOME_EXPENSE_WRITE = 'INCOME_EXPENSE_WRITE'

export const LEADERSHIP_ROLES = [
  ROLE_PRESIDENT,
  ROLE_VICE_PRESIDENT,
  ROLE_COOPERATIVE_ADMIN,
  ROLE_SUPER_ADMIN,
] as const

export const OFFICER_ROLES = [
  ROLE_PRESIDENT,
  ROLE_VICE_PRESIDENT,
  ROLE_SECRETARY,
  ROLE_ACCOUNTANT,
  ROLE_LOAN_OFFICER,
  ROLE_COOPERATIVE_ADMIN,
] as const

export const STAFF_ROLES = [...OFFICER_ROLES, ROLE_SUPER_ADMIN]

export const SECRETARY_ACCESS_ROLES = [
  ROLE_PRESIDENT,
  ROLE_VICE_PRESIDENT,
  ROLE_SECRETARY,
  ROLE_COOPERATIVE_ADMIN,
  ROLE_SUPER_ADMIN,
]

export const FINANCE_ACCESS_ROLES = [
  ROLE_PRESIDENT,
  ROLE_VICE_PRESIDENT,
  ROLE_ACCOUNTANT,
  ROLE_COOPERATIVE_ADMIN,
  ROLE_SUPER_ADMIN,
]

/** Officers who may see member-identifying contribution/fine leaderboards. */
export const MEMBER_INSIGHTS_FINANCE_ROLES = FINANCE_ACCESS_ROLES

/** Officers who may see overdue-loan follow-up (includes loan officer). */
export const MEMBER_INSIGHTS_LOAN_ROLES = [
  ROLE_PRESIDENT,
  ROLE_VICE_PRESIDENT,
  ROLE_ACCOUNTANT,
  ROLE_LOAN_OFFICER,
  ROLE_COOPERATIVE_ADMIN,
  ROLE_SUPER_ADMIN,
] as const

/** Who may initiate/manage OuWealth subscription payments for a Saving Scheme. */
export const BILLING_MANAGER_ROLES = FINANCE_ACCESS_ROLES

export const LOAN_COMMITTEE_ROLES = [
  ROLE_PRESIDENT,
  ROLE_VICE_PRESIDENT,
  ROLE_LOAN_OFFICER,
  ROLE_COOPERATIVE_ADMIN,
  ROLE_SUPER_ADMIN,
]

export const LOAN_OPS_ROLES = [
  ROLE_PRESIDENT,
  ROLE_VICE_PRESIDENT,
  ROLE_LOAN_OFFICER,
  ROLE_ACCOUNTANT,
  ROLE_COOPERATIVE_ADMIN,
  ROLE_SUPER_ADMIN,
]

export type AppRole =
  | typeof ROLE_SUPER_ADMIN
  | typeof ROLE_PRESIDENT
  | typeof ROLE_VICE_PRESIDENT
  | typeof ROLE_SECRETARY
  | typeof ROLE_ACCOUNTANT
  | typeof ROLE_LOAN_OFFICER
  | typeof ROLE_COOPERATIVE_ADMIN
  | typeof ROLE_MEMBER

export function hasAnyRole(roles: string[] | undefined, allowed: readonly string[]): boolean {
  if (!roles?.length) return false
  return allowed.some((role) => roles.includes(role))
}

export function canViewMemberFinanceInsights(roles: string[] | undefined): boolean {
  return hasAnyRole(roles, MEMBER_INSIGHTS_FINANCE_ROLES)
}

export function canViewMemberLoanInsights(roles: string[] | undefined): boolean {
  return hasAnyRole(roles, MEMBER_INSIGHTS_LOAN_ROLES)
}

export function canViewAnyMemberInsights(roles: string[] | undefined): boolean {
  return canViewMemberFinanceInsights(roles) || canViewMemberLoanInsights(roles)
}

export function isOfficerRole(roles: string[] | undefined): boolean {
  return hasAnyRole(roles, OFFICER_ROLES)
}

export function isLeadershipRole(roles: string[] | undefined): boolean {
  return hasAnyRole(roles, LEADERSHIP_ROLES)
}

export function hasPermission(
  user: { roles?: string[]; permissions?: string[] } | null | undefined,
  permission: string,
): boolean {
  if (!user) return false
  if (user.roles?.includes(ROLE_SUPER_ADMIN)) return true
  return user.permissions?.includes(permission) ?? false
}

/** Highest-priority role for badges and home screens. */
export function primaryRole(roles: string[]): AppRole {
  if (roles.includes(ROLE_SUPER_ADMIN)) return ROLE_SUPER_ADMIN
  if (roles.includes(ROLE_PRESIDENT) || roles.includes(ROLE_COOPERATIVE_ADMIN)) {
    return ROLE_PRESIDENT
  }
  if (roles.includes(ROLE_VICE_PRESIDENT)) return ROLE_VICE_PRESIDENT
  if (roles.includes(ROLE_SECRETARY)) return ROLE_SECRETARY
  if (roles.includes(ROLE_ACCOUNTANT)) return ROLE_ACCOUNTANT
  if (roles.includes(ROLE_LOAN_OFFICER)) return ROLE_LOAN_OFFICER
  return ROLE_MEMBER
}
