import type { FineStatus } from '@/shared/types/fine'

export type ChipColor =
  | 'default'
  | 'primary'
  | 'secondary'
  | 'error'
  | 'info'
  | 'success'
  | 'warning'

export function fineStatusColor(status: string): ChipColor {
  switch (status) {
    case 'UNPAID':
      return 'error'
    case 'PARTIALLY_PAID':
      return 'warning'
    case 'PAID':
      return 'success'
    case 'WAIVED':
      return 'default'
    case 'CANCELLED':
      return 'secondary'
    default:
      return 'default'
  }
}

export function finePaymentStatusColor(status: string): ChipColor {
  switch (status) {
    case 'PENDING':
      return 'info'
    case 'APPROVED':
      return 'success'
    case 'REJECTED':
      return 'error'
    default:
      return 'default'
  }
}

export function fineStatusLabelKey(status: string): string {
  return `fines.status.${status}`
}

export function canApproveFinePayment(status: string, isAdmin: boolean): boolean {
  return isAdmin && status === 'PENDING'
}

export function canRejectFinePayment(status: string, isAdmin: boolean): boolean {
  return isAdmin && status === 'PENDING'
}

export function canWaiveFine(status: string, isAdmin: boolean): boolean {
  return isAdmin && (status === 'UNPAID' || status === 'PARTIALLY_PAID')
}

export function canCancelFine(status: string, isAdmin: boolean): boolean {
  return isAdmin && (status === 'UNPAID' || status === 'PARTIALLY_PAID')
}

export function canSubmitFinePayment(status: string): boolean {
  return status === 'UNPAID' || status === 'PARTIALLY_PAID'
}

export function isTerminalFineStatus(status: string): boolean {
  return status === 'PAID' || status === 'WAIVED' || status === 'CANCELLED'
}

export function outstandingFineAmount(
  outstanding: string | number | null | undefined,
): number {
  return Number(outstanding) || 0
}

/** Type guard helper for role-gated fine payment review actions. */
export function filterFinePaymentActions(
  status: FineStatus | string,
  isAdmin: boolean,
): Array<'approve' | 'reject'> {
  const actions: Array<'approve' | 'reject'> = []
  if (canApproveFinePayment(status, isAdmin)) actions.push('approve')
  if (canRejectFinePayment(status, isAdmin)) actions.push('reject')
  return actions
}
