export type MembershipStatus = 'ACTIVE' | 'INACTIVE' | 'SUSPENDED' | 'PENDING'
export type AccountStatus = 'ACTIVE' | 'INACTIVE' | 'LOCKED' | 'PENDING' | 'SUSPENDED'
export type RoleInCooperative =
  | 'MEMBER'
  | 'PRESIDENT'
  | 'VICE_PRESIDENT'
  | 'SECRETARY'
  | 'ACCOUNTANT'
  | 'LOAN_OFFICER'
  | 'COOPERATIVE_ADMIN'

export const ROLES_IN_COOPERATIVE: RoleInCooperative[] = [
  'MEMBER',
  'PRESIDENT',
  'VICE_PRESIDENT',
  'SECRETARY',
  'ACCOUNTANT',
  'LOAN_OFFICER',
]

export function normalizeRoleInCooperative(role: string | null | undefined): RoleInCooperative {
  if (role === 'COOPERATIVE_ADMIN') return 'PRESIDENT'
  if (
    role === 'PRESIDENT' ||
    role === 'VICE_PRESIDENT' ||
    role === 'SECRETARY' ||
    role === 'ACCOUNTANT' ||
    role === 'LOAN_OFFICER'
  ) {
    return role
  }
  return 'MEMBER'
}

export const MEMBERSHIP_STATUSES: MembershipStatus[] = [
  'ACTIVE',
  'INACTIVE',
  'SUSPENDED',
  'PENDING',
]

export const ACCOUNT_STATUSES: AccountStatus[] = [
  'ACTIVE',
  'INACTIVE',
  'LOCKED',
  'PENDING',
  'SUSPENDED',
]

export interface Member {
  userId: string
  membershipId?: string
  firstName: string
  lastName: string
  fullName?: string
  username: string
  email: string
  phone?: string | null
  nationalId?: string | null
  address?: string | null
  membershipDate?: string | null
  membershipStatus: MembershipStatus
  accountStatus: AccountStatus
  roleInCooperative: RoleInCooperative | string
  shareCount?: number
  profileImageUrl?: string | null
  /** Returned only once on create when the server generated a password. */
  temporaryPassword?: string | null
  contributions?: unknown[]
  loans?: unknown[]
  fines?: unknown[]
  /** Phase 7 social fund history (contributions and/or disbursements). */
  social?: unknown[]
  socialFundHistory?: unknown[]
  socialContributions?: unknown[]
  payouts?: unknown[]
}

export interface MemberCreateRequest {
  firstName: string
  lastName: string
  username: string
  email: string
  phone?: string
  nationalId?: string
  address?: string
  membershipDate?: string
  temporaryPassword?: string
  roleInCooperative: RoleInCooperative
  shareCount?: number
}

export interface MemberUpdateRequest {
  firstName: string
  lastName: string
  username?: string
  email: string
  phone?: string
  nationalId?: string
  address?: string
  membershipDate?: string
  roleInCooperative?: RoleInCooperative
  shareCount?: number
}

export interface MemberStatusUpdateRequest {
  accountStatus?: AccountStatus
  membershipStatus?: MembershipStatus
}

/** Backend-calculated member financial summary. */
export interface MemberFinancialSummary {
  cooperativeId?: string
  memberUserId?: string
  memberName?: string
  membershipStatus?: string
  membershipDate?: string | null
  currency?: string
  regularContributions?: string | number
  specialContributions?: string | number
  actualContributions?: string | number
  expectedContributions?: string | number
  outstandingContributions?: string | number
  loansReceived?: string | number
  outstandingLoanPrincipal?: string | number
  outstandingLoanInterest?: string | number
  totalLoanRepayments?: string | number
  totalFines?: string | number
  unpaidFines?: string | number
  approvedFinePayments?: string | number
  socialContributions?: string | number
  contributionPercentage?: string | number | null
  recentPayoutTotal?: string | number
  sharesHeld?: number | null
  currentShareValue?: string | number | null
  totalShareValue?: string | number | null
}

export function memberDisplayName(member: Pick<Member, 'firstName' | 'lastName' | 'fullName'>): string {
  if (member.fullName?.trim()) return member.fullName.trim()
  return `${member.firstName ?? ''} ${member.lastName ?? ''}`.trim()
}

export function mapMember(raw: Member): Member {
  const wrapper = raw as Member & { member?: Member }
  const nested = wrapper.member
  // GET /members/{id} returns { member, contributions, loans, ... }; flatten so edit forms prefill.
  const base = nested ? { ...wrapper, ...nested } : wrapper
  const userId = String(base.userId ?? '')
  return {
    ...base,
    userId,
    fullName: memberDisplayName(base),
  }
}

/** What may be done to remove a member, decided by the backend from the member's actual records. */
export interface MemberRemovalEligibility {
  memberUserId: string
  membershipStatus: string
  /** True only for a member with no history at all: then, and only then, they may be permanently deleted. */
  canDelete: boolean
  deletionBlockedReason?: string | null
  historyBlockers: { source: string; count: number }[]
  canDeactivate: boolean
  deactivationBlockedReason?: string | null
  canReactivate: boolean
  impact: {
    outstandingLoanPrincipal: string | number
    openLoans: number
    unpaidContributions: string | number
    heldCredit: string | number
    upcomingRecipientPeriods: number
    openPayouts: number
  }
}
