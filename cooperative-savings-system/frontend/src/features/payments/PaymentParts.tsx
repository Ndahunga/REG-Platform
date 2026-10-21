import { Alert, Box, Chip, Stack, Typography } from '@mui/material'
import { useTranslation } from 'react-i18next'
import { formatMoney } from '@/shared/utils/formatMoney'
import type { PaymentAllocation, PaymentPosting, PaymentStatus, PostingLine } from '@/shared/types/memberPayment'
import {
  apiErrorCode,
  apiErrorStatus,
  bpsToPercent,
  errorKeyFor,
  isIndeterminateFailure,
  isUnresolvedAttribution,
  paymentStatusColor,
} from './paymentHelpers'
import { getErrorMessage } from '@/shared/api/client'

export function PaymentStatusChip({ status }: { status: PaymentStatus }) {
  const { t } = useTranslation()
  return (
    <Chip
      size="small"
      color={paymentStatusColor(status)}
      label={t(`payments.status.${status}`)}
      data-testid="payment-status"
    />
  )
}

/**
 * What a status means for the payer's money. POSTED is the only status that says balances changed; a VERIFIED
 * payment is explicitly "funds confirmed, not yet posted", and nothing here implies a balance moved before posting.
 */
export function PaymentStatusNotice({ status }: { status: PaymentStatus }) {
  const { t } = useTranslation()
  const severity = status === 'POSTED' ? 'success' : status === 'REJECTED' ? 'error' : 'info'
  return (
    <Alert severity={severity} data-testid="payment-status-notice">
      {t(`payments.notice.${status}`)}
    </Alert>
  )
}

export function periodLabel(year?: number | null, month?: number | null): string {
  return year && month ? `${year}-${String(month).padStart(2, '0')}` : ''
}

export function shortId(id?: string | null): string {
  return id ? id.slice(0, 8) : ''
}

/** The allocation lines of a payment as the claim states them. */
export function AllocationLines({ allocations, currency }: { allocations: PaymentAllocation[]; currency?: string }) {
  const { t } = useTranslation()
  return (
    <Stack spacing={0.5} data-testid="payment-allocations">
      {allocations.map((a) => (
        <Stack key={a.id} direction="row" spacing={1} sx={{ justifyContent: 'space-between', gap: 2 }}>
          <Typography variant="body2">
            {a.type === 'CONTRIBUTION'
              ? t('payments.allocation.contributionLine', { period: periodLabel(a.periodYear, a.periodMonth) })
              : a.type === 'LOAN_REPAYMENT'
                ? t('payments.allocation.loanLine', { loan: shortId(a.loanId) })
                : t(`payments.allocation.type.${a.type}`)}
          </Typography>
          <Typography variant="body2" sx={{ fontWeight: 600 }}>
            {formatMoney(a.amount, currency ? { currency } : undefined)}
          </Typography>
        </Stack>
      ))}
    </Stack>
  )
}

/** The immutable posting result of each allocation (staff view). Shown only once the backend recorded it. */
export function PostingResults({ lines, currency }: { lines: PostingLine[]; currency?: string }) {
  const { t } = useTranslation()
  const money = (value: string | number | null | undefined) =>
    formatMoney(value ?? 0, currency ? { currency } : undefined)
  return (
    <Stack spacing={1} data-testid="posting-results">
      {lines.map((line) => (
        <Box key={line.allocationId} sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 1, p: 1 }}>
          <Typography variant="body2" sx={{ fontWeight: 600 }}>
            {line.type === 'CONTRIBUTION'
              ? t('payments.allocation.contributionLine', { period: periodLabel(line.periodYear, line.periodMonth) })
              : t('payments.allocation.loanLine', { loan: shortId(line.loanId) })}{' '}
            · {money(line.amount)}
          </Typography>
          {line.type === 'LOAN_REPAYMENT' ? (
            <Typography variant="caption" color="text.secondary" component="div">
              {t('payments.posting.loanBreakdown', {
                principal: money(line.principalAmount),
                interest: money(line.interestAmount),
                penalty: money(line.penaltyAmount),
              })}
            </Typography>
          ) : (
            <Typography variant="caption" color="text.secondary" component="div">
              {t(`payments.posting.attribution.${line.attribution}`)}
              {line.attribution === 'ATTRIBUTED' || line.attribution === 'ACCUMULATING'
                ? ` — ${t('payments.posting.split', {
                    rotating: money(line.rotatingAmount),
                    retained: money(line.retainedAmount),
                    percent: bpsToPercent(line.rotatingBps) || '0%',
                  })}`
                : ''}
            </Typography>
          )}
        </Box>
      ))}
    </Stack>
  )
}

/** A backend failure in words, using the backend's actual error code. Never invents a code or a workaround. */
export function ErrorNotice({ error, code }: { error?: unknown; code?: string | null }) {
  const { t } = useTranslation()
  const resolved = code ?? (error ? apiErrorCode(error) : null)
  const key = errorKeyFor(resolved)
  const status = error ? apiErrorStatus(error) : null
  if (isUnresolvedAttribution(resolved)) {
    return (
      <Alert severity="warning" data-testid="payment-error-unresolved-attribution">
        <Typography variant="body2" sx={{ fontWeight: 600 }}>
          {t('payments.errors.ROTATION_ATTRIBUTION_UNRESOLVED')}
        </Typography>
      </Alert>
    )
  }
  if (error && isIndeterminateFailure(error)) {
    return <Alert severity="warning">{t('payments.errors.indeterminate')}</Alert>
  }
  let text: string
  if (key) {
    text = t(key)
  } else if (status === 403) {
    text = error ? getErrorMessage(error, t('payments.errors.forbidden')) : t('payments.errors.forbidden')
  } else if (error) {
    text = getErrorMessage(error, t('payments.errors.generic'))
  } else {
    text = t('payments.errors.generic')
  }
  return (
    <Alert severity="error" data-testid="payment-error">
      {text}
      {resolved && !key && status !== 403 ? ` (${resolved})` : ''}
    </Alert>
  )
}

/** Posting metadata for staff: attempts and the last failure. */
export function PostingFailureNotice({ posting }: { posting: PaymentPosting }) {
  const { t } = useTranslation()
  if (posting.status !== 'VERIFIED' || posting.postingAttempts === 0) return null
  return (
    <Stack spacing={1} data-testid="posting-failure">
      <ErrorNotice code={posting.lastPostingFailureCode ?? 'POSTING_CONFLICT'} />
      <Typography variant="caption" color="text.secondary">
        {t('payments.posting.attempts', { count: posting.postingAttempts })}
      </Typography>
    </Stack>
  )
}
