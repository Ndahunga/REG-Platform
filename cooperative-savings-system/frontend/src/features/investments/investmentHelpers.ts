import type { InvestmentStatus } from '@/shared/types/investment'

export type ChipColor =
  | 'default'
  | 'primary'
  | 'secondary'
  | 'error'
  | 'info'
  | 'success'
  | 'warning'

export type InvestmentAction = 'activate' | 'cancel' | 'recordReturn' | 'recordLoss'

export function investmentStatusColor(status: string): ChipColor {
  switch (status) {
    case 'PLANNED':
      return 'info'
    case 'ACTIVE':
      return 'success'
    case 'PARTIALLY_RETURNED':
      return 'warning'
    case 'COMPLETED':
      return 'primary'
    case 'CANCELLED':
      return 'secondary'
    case 'LOSS_RECORDED':
      return 'error'
    default:
      return 'default'
  }
}

export function investmentStatusLabelKey(status: string): string {
  return `investments.status.${status}`
}

export function canActivateInvestment(status: string, isAdmin: boolean): boolean {
  return isAdmin && status === 'PLANNED'
}

export function canCancelInvestment(status: string, isAdmin: boolean): boolean {
  return isAdmin && (status === 'PLANNED' || status === 'ACTIVE')
}

export function canRecordInvestmentReturn(status: string, isAdmin: boolean): boolean {
  return isAdmin && (status === 'ACTIVE' || status === 'PARTIALLY_RETURNED')
}

export function canRecordInvestmentLoss(status: string, isAdmin: boolean): boolean {
  return isAdmin && (status === 'ACTIVE' || status === 'PARTIALLY_RETURNED')
}

export function isTerminalInvestmentStatus(status: string): boolean {
  return (
    status === 'COMPLETED' || status === 'CANCELLED' || status === 'LOSS_RECORDED'
  )
}

export function remainingInvestmentCapital(
  remaining: string | number | null | undefined,
): number {
  return Number(remaining) || 0
}

/** Role-gated investment action visibility by status. */
export function filterInvestmentActions(
  status: InvestmentStatus | string,
  isAdmin: boolean,
): InvestmentAction[] {
  const actions: InvestmentAction[] = []
  if (canActivateInvestment(status, isAdmin)) actions.push('activate')
  if (canCancelInvestment(status, isAdmin)) actions.push('cancel')
  if (canRecordInvestmentReturn(status, isAdmin)) actions.push('recordReturn')
  if (canRecordInvestmentLoss(status, isAdmin)) actions.push('recordLoss')
  return actions
}
