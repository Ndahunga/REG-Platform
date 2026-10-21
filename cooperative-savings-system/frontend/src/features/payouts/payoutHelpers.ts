import type { PayoutRunStatus } from '@/shared/types/payout'

export type ChipColor =
  | 'default'
  | 'primary'
  | 'secondary'
  | 'error'
  | 'info'
  | 'success'
  | 'warning'

export type PayoutAction = 'confirm' | 'markPaid' | 'cancel'

export function payoutStatusColor(status: string): ChipColor {
  switch (status) {
    case 'DRAFT':
      return 'default'
    case 'PREVIEWED':
    case 'PENDING':
      return 'info'
    case 'CONFIRMED':
      return 'warning'
    case 'PAID':
      return 'success'
    case 'CANCELLED':
      return 'secondary'
    default:
      return 'default'
  }
}

export function payoutStatusLabelKey(status: string): string {
  return `payouts.status.${status}`
}

export function canConfirmPayout(status: string, isAdmin: boolean): boolean {
  return isAdmin && status === 'PREVIEWED'
}

export function canMarkPaidPayout(status: string, isAdmin: boolean): boolean {
  return isAdmin && status === 'CONFIRMED'
}

export function canCancelPayout(status: string, isAdmin: boolean): boolean {
  return isAdmin && (status === 'DRAFT' || status === 'PREVIEWED')
}

export function isTerminalPayoutStatus(status: string): boolean {
  return status === 'PAID' || status === 'CANCELLED'
}

export function filterPayoutActions(
  status: PayoutRunStatus | string,
  isAdmin: boolean,
): PayoutAction[] {
  const actions: PayoutAction[] = []
  if (canConfirmPayout(status, isAdmin)) actions.push('confirm')
  if (canMarkPaidPayout(status, isAdmin)) actions.push('markPaid')
  if (canCancelPayout(status, isAdmin)) actions.push('cancel')
  return actions
}

export function formatPayoutPercentage(value: string | number | null | undefined): string {
  if (value == null || value === '') return '—'
  const n = Number(value)
  if (!Number.isFinite(n)) return '—'
  return `${n.toFixed(4).replace(/\.?0+$/, '')}%`
}

export function sumPayoutAmounts(
  lines: Array<{ payoutAmount?: string | number | null }> | null | undefined,
): number {
  if (!lines?.length) return 0
  return lines.reduce((sum, line) => sum + (Number(line.payoutAmount) || 0), 0)
}
