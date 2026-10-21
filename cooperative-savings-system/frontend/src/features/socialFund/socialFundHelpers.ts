import type { SocialContributionStatus, SocialDisbursementStatus } from '@/shared/types/socialFund'

export type ChipColor =
  | 'default'
  | 'primary'
  | 'secondary'
  | 'error'
  | 'info'
  | 'success'
  | 'warning'

export function socialStatusColor(status: string): ChipColor {
  switch (status) {
    case 'PENDING':
      return 'info'
    case 'APPROVED':
      return 'success'
    case 'REJECTED':
      return 'error'
    case 'CANCELLED':
      return 'secondary'
    default:
      return 'default'
  }
}

export function canApproveSocialContribution(status: string, isAdmin: boolean): boolean {
  return isAdmin && status === 'PENDING'
}

export function canRejectSocialContribution(status: string, isAdmin: boolean): boolean {
  return isAdmin && status === 'PENDING'
}

export function canApproveSocialDisbursement(status: string, isAdmin: boolean): boolean {
  return isAdmin && status === 'PENDING'
}

export function canRejectSocialDisbursement(status: string, isAdmin: boolean): boolean {
  return isAdmin && status === 'PENDING'
}

export function canCancelSocialDisbursement(status: string, isAdmin: boolean): boolean {
  return isAdmin && status === 'PENDING'
}

export function filterSocialContributionActions(
  status: SocialContributionStatus | string,
  isAdmin: boolean,
): Array<'approve' | 'reject'> {
  const actions: Array<'approve' | 'reject'> = []
  if (canApproveSocialContribution(status, isAdmin)) actions.push('approve')
  if (canRejectSocialContribution(status, isAdmin)) actions.push('reject')
  return actions
}

export function filterSocialDisbursementActions(
  status: SocialDisbursementStatus | string,
  isAdmin: boolean,
): Array<'approve' | 'reject' | 'cancel'> {
  const actions: Array<'approve' | 'reject' | 'cancel'> = []
  if (canApproveSocialDisbursement(status, isAdmin)) actions.push('approve')
  if (canRejectSocialDisbursement(status, isAdmin)) actions.push('reject')
  if (canCancelSocialDisbursement(status, isAdmin)) actions.push('cancel')
  return actions
}

export function pendingSocialApprovalsTotal(
  pendingContributions?: number | null,
  pendingDisbursements?: number | null,
): number {
  return (Number(pendingContributions) || 0) + (Number(pendingDisbursements) || 0)
}
