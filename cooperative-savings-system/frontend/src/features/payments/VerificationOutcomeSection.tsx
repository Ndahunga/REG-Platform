import { Alert, Box, Button, Chip, Stack, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useAppSelector } from '@/app/store/hooks'
import { confirmCashReceived, fetchVerificationOverview, recheckPayment } from '@/shared/api/paymentVerification'
import { LoadingState } from '@/shared/components/LoadingState'
import type { MemberPayment } from '@/shared/types/memberPayment'
import type { VerificationCheck, VerificationOutcome } from '@/shared/types/paymentVerification'
import { ErrorNotice } from './PaymentParts'
import { paymentStatusColor } from './paymentHelpers'
import { ReasonConfirmDialog } from './ReasonConfirmDialog'

interface VerificationOutcomeSectionProps {
  cooperativeId: string
  payment: MemberPayment
}

export const outcomeColor = (outcome: VerificationOutcome) =>
  outcome === 'CONFIRMED'
    ? 'success'
    : outcome === 'CONFLICTED' || outcome === 'FAILED'
      ? 'error'
      : outcome === 'UNMATCHED'
        ? 'warning'
        : 'default'

/**
 * The evidence outcome of a payment, shown beside (never instead of) its lifecycle status. CONFIRMED means authoritative
 * evidence agrees (a cash confirmation by an officer, an imported statement line, or a signed provider confirmation);
 * VERIFIED and POSTED are decisions made by officers. A receipt image or OCR text can never produce CONFIRMED, and a
 * confirmation does not verify or post anything. Automatic provider confirmation is reported as unavailable unless the
 * deployment has a signed callback configured.
 */
export function VerificationOutcomeSection({ cooperativeId, payment }: VerificationOutcomeSectionProps) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const user = useAppSelector((s) => s.auth.user)
  const [cashDialog, setCashDialog] = useState(false)
  const key = ['member-payments', cooperativeId, 'verification', payment.id]
  const query = useQuery({ queryKey: key, queryFn: () => fetchVerificationOverview(cooperativeId, payment.id) })
  const refresh = () => queryClient.invalidateQueries({ queryKey: key })
  const recheck = useMutation({ mutationFn: () => recheckPayment(cooperativeId, payment.id), onSuccess: refresh })

  const overview = query.data
  const current = overview?.current ?? null
  const open = payment.status === 'SUBMITTED' || payment.status === 'UNDER_REVIEW'
  const isPayer = user?.id === payment.memberUserId || user?.id === payment.submittedBy
  const cashPending = payment.paymentMethod === 'CASH' && open && !(current?.outcome === 'CONFIRMED')

  const reasonText = (code: string) => t(`payments.outcome.reason.${code}`, { defaultValue: code })

  const line = (check: VerificationCheck, testId?: string) => (
    <Stack key={check.id} spacing={0.5} data-testid={testId}>
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 0.5 }}>
        <Chip size="small" color={outcomeColor(check.outcome)} label={t(`payments.outcome.value.${check.outcome}`)} />
        <Typography variant="body2">{t(`payments.verification.methods.${check.method}`)}</Typography>
        <Typography variant="caption" color="text.secondary">
          {t(`payments.outcome.source.${check.source}`)}
          {' · '}
          {check.authoritative ? t('payments.outcome.authoritative') : t('payments.outcome.notAuthoritative')}
        </Typography>
      </Stack>
      {check.reasons.length > 0 ? (
        <Stack direction="row" spacing={0.5} sx={{ flexWrap: 'wrap', rowGap: 0.5 }} data-testid="verification-reasons">
          {check.reasons.map((r) => (
            <Chip key={r} size="small" variant="outlined" color="warning" label={reasonText(r)} />
          ))}
        </Stack>
      ) : null}
      <Typography variant="caption" color="text.secondary">
        {new Date(check.createdAt).toLocaleString()}
      </Typography>
    </Stack>
  )

  return (
    <Box data-testid="verification-outcome">
      <Typography variant="subtitle2" gutterBottom>
        {t('payments.outcome.title')}
      </Typography>
      <Stack spacing={1}>
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 0.5 }}>
          <Typography variant="body2">{t('payments.outcome.paymentStatus')}</Typography>
          <Chip size="small" color={paymentStatusColor(payment.status)} label={t(`payments.status.${payment.status}`)} data-testid="verification-payment-status" />
          <Typography variant="body2">{t('payments.outcome.evidenceOutcome')}</Typography>
          {current ? (
            <Chip size="small" color={outcomeColor(current.outcome)} label={t(`payments.outcome.value.${current.outcome}`)} data-testid="verification-current-outcome" />
          ) : (
            <Chip size="small" label={t('payments.outcome.notChecked')} data-testid="verification-current-outcome" />
          )}
        </Stack>
        <Typography variant="caption" color="text.secondary">
          {t('payments.outcome.notAStatus')}
        </Typography>

        {query.isLoading ? <LoadingState variant="skeleton" rows={2} /> : null}
        {query.isError ? <ErrorNotice error={query.error} /> : null}
        {recheck.isError ? <ErrorNotice error={recheck.error} /> : null}

        {overview ? (
          <Alert
            severity={overview.automaticProviderConfirmation === 'UNAVAILABLE' ? 'info' : 'success'}
            data-testid="provider-availability"
          >
            {overview.automaticProviderConfirmation === 'UNAVAILABLE'
              ? t('payments.outcome.providerUnavailable')
              : t('payments.outcome.providerCallback')}
          </Alert>
        ) : null}
        {overview?.requiresTrustedConfirmation ? (
          <Alert severity="info" data-testid="verification-strict">
            {t('payments.outcome.strictRequired')}
          </Alert>
        ) : null}
        {current && (current.outcome === 'CONFLICTED' || current.outcome === 'FAILED') ? (
          <Alert severity="error" data-testid="verification-blocked">
            {t('payments.outcome.blocked')}
          </Alert>
        ) : null}

        {current ? line(current, 'verification-current') : null}

        <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', rowGap: 1 }}>
          {payment.paymentMethod !== 'CASH' && open ? (
            <Button variant="outlined" onClick={() => recheck.mutate()} disabled={recheck.isPending} sx={{ minHeight: 44 }} data-testid="verification-recheck">
              {recheck.isPending ? t('payments.outcome.rechecking') : t('payments.outcome.recheck')}
            </Button>
          ) : null}
          {payment.paymentMethod === 'CASH' && open ? (
            <Button variant="outlined" onClick={() => recheck.mutate()} disabled={recheck.isPending} sx={{ minHeight: 44 }}>
              {t('payments.outcome.recheck')}
            </Button>
          ) : null}
          {cashPending && !isPayer ? (
            <Button variant="contained" onClick={() => setCashDialog(true)} sx={{ minHeight: 44 }} data-testid="verification-confirm-cash">
              {t('payments.outcome.confirmCash')}
            </Button>
          ) : null}
        </Stack>
        {cashPending && isPayer ? (
          <Alert severity="info" data-testid="verification-cash-not-allowed">
            {t('payments.outcome.cashNeedsAnotherOfficer')}
          </Alert>
        ) : null}

        {overview && overview.history.length > 1 ? (
          <Box data-testid="verification-history">
            <Typography variant="caption" color="text.secondary">
              {t('payments.outcome.history')}
            </Typography>
            <Stack spacing={1} sx={{ mt: 0.5 }}>
              {overview.history.slice(0, -1).reverse().map((c) => line(c))}
            </Stack>
          </Box>
        ) : null}
      </Stack>

      {cashDialog ? (
        <ReasonConfirmDialog
          title={t('payments.outcome.cashTitle')}
          explain={t('payments.outcome.cashExplain')}
          confirmLabel={t('payments.outcome.cashConfirmLabel')}
          reasonLabel={t('payments.outcome.cashNote')}
          submitLabel={t('payments.outcome.confirmCash')}
          onSubmit={(note) => confirmCashReceived(cooperativeId, payment.id, note)}
          onClose={() => setCashDialog(false)}
          onDone={() => {
            setCashDialog(false)
            void refresh()
          }}
        />
      ) : null}
    </Box>
  )
}
