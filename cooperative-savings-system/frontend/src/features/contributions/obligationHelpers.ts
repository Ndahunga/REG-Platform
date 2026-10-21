import type { ContributionObligationFields } from '@/shared/types/contribution'

/** True only when the server reports an overdue amount above zero; legacy schemes without a due day are never overdue. */
export function hasOverdue(fields: ContributionObligationFields): boolean {
  return Boolean(fields.overdue) && Number(fields.overdueAmount ?? 0) > 0
}

/** The expected rotating/retained split is shown only for snapshotted obligations of a Rotating or Hybrid scheme. */
export function hasExpectedSplit(fields: ContributionObligationFields): boolean {
  const model = fields.operatingModel
  return (
    (model === 'ROTATING' || model === 'HYBRID') &&
    fields.expectedRotatingAmount != null &&
    fields.expectedRetainedAmount != null
  )
}

export function bpsLabel(bps: number | null | undefined): string {
  if (bps == null) return ''
  const percent = bps / 100
  return `${Number.isInteger(percent) ? percent : percent.toFixed(2)}%`
}

/** A due day must be a whole number from 1 to 31 (months without that day use their last day). */
export function parseDueDay(value: string): number | null {
  if (!/^\d{1,2}$/.test(value.trim())) return null
  const day = Number(value)
  return day >= 1 && day <= 31 ? day : null
}
