import type { LoanStatus } from '@/shared/types/loan'

export type ChipColor =
  | 'default'
  | 'primary'
  | 'secondary'
  | 'error'
  | 'info'
  | 'success'
  | 'warning'

export function loanStatusColor(status: string): ChipColor {
  switch (status) {
    case 'PENDING':
      return 'info'
    case 'AWAITING_SECOND_APPROVAL':
      return 'warning'
    case 'APPROVED':
      return 'primary'
    case 'ACTIVE':
      return 'success'
    case 'OVERDUE':
      return 'warning'
    case 'REJECTED':
      return 'error'
    case 'CLOSED':
      return 'secondary'
    case 'WRITTEN_OFF':
      return 'default'
    default:
      return 'default'
  }
}

export function loanStatusLabelKey(status: string): string {
  return `loans.status.${status}`
}

export function canShowApprove(status: string, canAct: boolean): boolean {
  return canAct && (status === 'PENDING' || status === 'AWAITING_SECOND_APPROVAL')
}

export function canShowReject(status: string, canAct: boolean): boolean {
  return canAct && (status === 'PENDING' || status === 'AWAITING_SECOND_APPROVAL')
}

export function canShowDisburse(status: string, isAdmin: boolean): boolean {
  return isAdmin && status === 'APPROVED'
}

export function canShowWriteOff(status: string, isAdmin: boolean): boolean {
  return isAdmin && (status === 'ACTIVE' || status === 'OVERDUE')
}

export function canShowRepayment(status: string, isAdmin: boolean): boolean {
  return isAdmin && (status === 'ACTIVE' || status === 'OVERDUE')
}

export function isTerminalLoanStatus(status: string): boolean {
  return (
    status === 'REJECTED' ||
    status === 'CLOSED' ||
    status === 'WRITTEN_OFF'
  )
}

export function outstandingTotal(
  principal: string | number | null | undefined,
  interest: string | number | null | undefined,
): number {
  return (Number(principal) || 0) + (Number(interest) || 0)
}

/** Type guard helper for role-gated UI lists. */
export function filterLoanActions(
  status: LoanStatus | string,
  isAdmin: boolean,
): Array<'approve' | 'reject' | 'disburse' | 'repay' | 'writeOff'> {
  const actions: Array<'approve' | 'reject' | 'disburse' | 'repay' | 'writeOff'> = []
  if (canShowApprove(status, isAdmin)) actions.push('approve')
  if (canShowReject(status, isAdmin)) actions.push('reject')
  if (canShowDisburse(status, isAdmin)) actions.push('disburse')
  if (canShowRepayment(status, isAdmin)) actions.push('repay')
  if (canShowWriteOff(status, isAdmin)) actions.push('writeOff')
  return actions
}
