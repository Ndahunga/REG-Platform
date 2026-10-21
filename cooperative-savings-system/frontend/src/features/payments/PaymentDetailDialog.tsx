import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  Stack,
  Typography,
} from '@mui/material'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useAppSelector } from '@/app/store/hooks'
import {
  fetchPayment,
  fetchPaymentEvents,
  fetchPaymentPosting,
  startPaymentReview,
} from '@/shared/api/memberPayments'
import { getErrorMessage } from '@/shared/api/client'
import { AuthenticatedFileLink } from '@/shared/components/AuthenticatedFileLink'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { formatMoney } from '@/shared/utils/formatMoney'
import {
  AllocationLines,
  ErrorNotice,
  PaymentStatusChip,
  PaymentStatusNotice,
  PostingFailureNotice,
  PostingResults,
} from './PaymentParts'
import { PostPaymentDialog, RejectPaymentDialog, VerifyPaymentDialog, WithdrawPaymentDialog } from './PaymentDialogs'
import { PaymentReversalSection } from './PaymentReversalSection'
import { ReceiptExtractionSection } from './ReceiptExtractionSection'
import { VerificationOutcomeSection } from './VerificationOutcomeSection'
import { dutyBlockers, holdsAuthorities, type DutyBlocker } from './paymentHelpers'

interface PaymentDetailDialogProps {
  cooperativeId: string
  paymentId: string
  /** Officer view: posting status and the review, verification and posting actions the user may perform. */
  staff: boolean
  onClose: () => void
}

type ActiveDialog = 'verify' | 'evidence' | 'reject' | 'post' | 'retry' | 'withdraw' | null

const formatDateTime = (value?: string | null) => (value ? new Date(value).toLocaleString() : '')

/**
 * One payment: its claim, allocations, history and (for officers) posting state. Officers only see the actions they
 * may perform: the write authority of every allocation type, and not their own payment (payer or submitter), and for
 * posting also not the verifier or evidence recorder. The backend enforces all of it again.
 */
export function PaymentDetailDialog({ cooperativeId, paymentId, staff, onClose }: PaymentDetailDialogProps) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const queryClient = useQueryClient()
  const user = useAppSelector((s) => s.auth.user)
  const [dialog, setDialog] = useState<ActiveDialog>(null)
  const [reviewError, setReviewError] = useState<unknown>(null)
  const [reviewing, setReviewing] = useState(false)

  const paymentQuery = useQuery({
    queryKey: ['member-payments', cooperativeId, 'detail', paymentId],
    queryFn: () => fetchPayment(cooperativeId, paymentId),
  })
  const eventsQuery = useQuery({
    queryKey: ['member-payments', cooperativeId, 'events', paymentId],
    queryFn: () => fetchPaymentEvents(cooperativeId, paymentId),
  })
  const postingQuery = useQuery({
    queryKey: ['member-payments', cooperativeId, 'posting', paymentId],
    queryFn: () => fetchPaymentPosting(cooperativeId, paymentId),
    enabled: staff,
  })

  const payment = paymentQuery.data
  const posting = postingQuery.data

  const refresh = async () => {
    await queryClient.invalidateQueries({ queryKey: ['member-payments', cooperativeId] })
  }

  const afterAction = async (messageKey: string) => {
    setDialog(null)
    await refresh()
    enqueueSnackbar(t(messageKey), { variant: 'success' })
  }

  const startReview = async () => {
    setReviewing(true)
    setReviewError(null)
    try {
      await startPaymentReview(cooperativeId, paymentId)
      await refresh()
    } catch (error) {
      setReviewError(error)
      await refresh()
    } finally {
      setReviewing(false)
    }
  }

  const blockerText = (blockers: DutyBlocker[]) =>
    blockers.map((b) => t(`payments.duty.${b}`)).join(' ')

  const renderActions = () => {
    if (!staff || !payment) return null
    const types = payment.allocations.map((a) => a.type)
    if (!holdsAuthorities(user, types)) {
      return <Alert severity="info">{t('payments.officer.noAuthority')}</Alert>
    }
    const evidenceRecorded = posting?.verificationEvidenceRecorded ?? false
    const verifyBlockers = dutyBlockers('verify', user?.id, payment)
    const postBlockers = dutyBlockers('post', user?.id, payment, posting)
    const actions: React.ReactNode[] = []
    const notes: string[] = []
    let verifiedNote = false

    if (payment.status === 'SUBMITTED') {
      if (verifyBlockers.length === 0) {
        actions.push(
          <Button key="review" variant="outlined" onClick={() => void startReview()} disabled={reviewing} sx={{ minHeight: 44 }}>
            {t('payments.officer.startReview')}
          </Button>,
        )
      } else {
        notes.push(blockerText(verifyBlockers))
      }
    }
    if (payment.status === 'UNDER_REVIEW') {
      if (verifyBlockers.length === 0) {
        actions.push(
          <Button key="verify" variant="contained" onClick={() => setDialog('verify')} sx={{ minHeight: 44 }}>
            {t('payments.officer.verify')}
          </Button>,
        )
      } else {
        notes.push(blockerText(verifyBlockers))
      }
    }
    if (payment.status === 'VERIFIED' && posting) {
      if (!evidenceRecorded) {
        if (verifyBlockers.length === 0) {
          actions.push(
            <Button key="evidence" variant="contained" onClick={() => setDialog('evidence')} sx={{ minHeight: 44 }}>
              {t('payments.officer.completeEvidence')}
            </Button>,
          )
        } else {
          notes.push(blockerText(verifyBlockers))
        }
      } else if (postBlockers.length > 0) {
        notes.push(blockerText(postBlockers))
      } else if (posting.retryable) {
        actions.push(
          <Button key="retry" variant="contained" color="warning" onClick={() => setDialog('retry')} sx={{ minHeight: 44 }}>
            {t('payments.officer.retryPosting')}
          </Button>,
        )
      } else {
        actions.push(
          <Button key="post" variant="contained" onClick={() => setDialog('post')} sx={{ minHeight: 44 }}>
            {t('payments.officer.post')}
          </Button>,
        )
      }
    }
    if (payment.status === 'VERIFIED') {
      // Funds are confirmed: the payment cannot be rejected. It stays visible here until it can be posted.
      verifiedNote = true
    }
    if ((payment.status === 'SUBMITTED' || payment.status === 'UNDER_REVIEW') && verifyBlockers.length === 0) {
      actions.push(
        <Button key="reject" color="error" variant="outlined" onClick={() => setDialog('reject')} sx={{ minHeight: 44 }}>
          {t('payments.officer.reject')}
        </Button>,
      )
    }
    return (
      <Stack spacing={1} data-testid="payment-actions">
        {verifiedNote ? (
          <Alert severity="info" data-testid="payment-verified-note">
            {t('payments.officer.verifiedNotRejectable')}
          </Alert>
        ) : null}
        {notes.map((note) => (
          <Alert key={note} severity="info" data-testid="payment-duty-note">
            {note}
          </Alert>
        ))}
        {reviewError ? <ErrorNotice error={reviewError} /> : null}
        {actions.length > 0 ? (
          <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
            {actions}
          </Stack>
        ) : null}
      </Stack>
    )
  }

  return (
    <>
      <Dialog open onClose={onClose} fullWidth maxWidth="md" aria-labelledby="payment-detail-title">
        <DialogTitle id="payment-detail-title">{t('payments.detail.title')}</DialogTitle>
        <DialogContent>
          {paymentQuery.isLoading ? <LoadingState variant="skeleton" rows={4} /> : null}
          {paymentQuery.isError ? (
            <ErrorState
              message={getErrorMessage(paymentQuery.error, t('errors.generic'))}
              onRetry={() => void paymentQuery.refetch()}
            />
          ) : null}
          {payment ? (
            <Stack spacing={2} sx={{ pt: 1 }} data-testid="payment-detail">
              <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                <Typography variant="h6">{formatMoney(payment.amount, { currency: payment.currency })}</Typography>
                <PaymentStatusChip status={payment.status} />
              </Stack>
              <PaymentStatusNotice status={payment.status} />
              {payment.rejectionReason ? (
                <Alert severity="error">{t('payments.detail.rejectionReason', { reason: payment.rejectionReason })}</Alert>
              ) : null}

              <Box>
                <Typography variant="body2">
                  {t('payments.detail.method')}: {t(`payments.method.${payment.paymentMethod}`)}
                </Typography>
                {payment.transactionReference ? (
                  <Typography variant="body2">
                    {t('payments.detail.reference')}: {payment.transactionReference}
                  </Typography>
                ) : null}
                <Typography variant="body2">
                  {t('payments.detail.paymentDate')}: {payment.paymentDate}
                </Typography>
                <Typography variant="body2">
                  {t('payments.detail.submittedAt')}: {formatDateTime(payment.submittedAt)}
                </Typography>
                {payment.verifiedAt ? (
                  <Typography variant="body2" data-testid="payment-verified-at">
                    {t('payments.detail.verifiedAt')}: {formatDateTime(payment.verifiedAt)}
                  </Typography>
                ) : null}
                {payment.postedAt ? (
                  <Typography variant="body2" data-testid="payment-posted-at">
                    {t('payments.detail.postedAt')}: {formatDateTime(payment.postedAt)}
                  </Typography>
                ) : null}
                {payment.notes ? (
                  <Typography variant="body2">
                    {t('payments.detail.notes')}: {payment.notes}
                  </Typography>
                ) : null}
              </Box>

              <Box>
                <Typography variant="subtitle2" gutterBottom>
                  {t('payments.detail.receipt')}
                </Typography>
                {payment.evidenceFileKey ? (
                  <Stack spacing={0.5}>
                    <AuthenticatedFileLink storageKey={payment.evidenceFileKey}>
                      {t('payments.evidence.view')}
                    </AuthenticatedFileLink>
                    <Typography variant="caption" color="text.secondary">
                      {t('payments.evidence.uploadedNotProof')}
                    </Typography>
                  </Stack>
                ) : (
                  <Typography variant="body2" color="text.secondary">
                    {t('payments.evidence.none')}
                  </Typography>
                )}
              </Box>

              <Box>
                <Typography variant="subtitle2" gutterBottom>
                  {t('payments.allocation.title')}
                </Typography>
                <AllocationLines allocations={payment.allocations} currency={payment.currency} />
              </Box>

              {staff ? (
                <Box>
                  <Typography variant="subtitle2" gutterBottom>
                    {t('payments.officer.postingState')}
                  </Typography>
                  {postingQuery.isLoading ? <LoadingState variant="skeleton" rows={1} /> : null}
                  {postingQuery.isError ? <ErrorNotice error={postingQuery.error} /> : null}
                  {posting ? (
                    <Stack spacing={1}>
                      <Typography variant="body2" data-testid="payment-evidence-state">
                        {posting.verificationEvidenceRecorded && posting.verificationMethod
                          ? t('payments.officer.evidenceRecorded', {
                              method: t(`payments.verification.methods.${posting.verificationMethod}`),
                            })
                          : payment.status === 'VERIFIED' || payment.status === 'POSTED'
                            ? t('payments.officer.evidenceMissing')
                            : t('payments.officer.notVerifiedYet')}
                      </Typography>
                      <PostingFailureNotice posting={posting} />
                      {posting.postings.length > 0 ? (
                        <PostingResults lines={posting.postings} currency={payment.currency} />
                      ) : null}
                    </Stack>
                  ) : null}
                </Box>
              ) : null}

              <Divider />
              {staff ? (
                <>
                  <VerificationOutcomeSection cooperativeId={cooperativeId} payment={payment} />
                  <ReceiptExtractionSection cooperativeId={cooperativeId} payment={payment} />
                  <Divider />
                </>
              ) : null}
              {renderActions()}
              {payment.status === 'SUBMITTED' && user?.id === payment.memberUserId ? (
                <Box>
                  <Button color="error" variant="outlined" onClick={() => setDialog('withdraw')} sx={{ minHeight: 44 }}>
                    {t('payments.withdraw.action')}
                  </Button>
                </Box>
              ) : null}
              {staff ? <PaymentReversalSection cooperativeId={cooperativeId} payment={payment} /> : null}
              <Divider />

              <Box>
                <Typography variant="subtitle2" gutterBottom>
                  {t('payments.detail.history')}
                </Typography>
                {eventsQuery.isLoading ? <LoadingState variant="skeleton" rows={2} /> : null}
                {eventsQuery.isError ? <ErrorNotice error={eventsQuery.error} /> : null}
                <Stack spacing={0.5} data-testid="payment-events">
                  {(eventsQuery.data ?? []).map((event) => (
                    <Typography key={event.id} variant="body2">
                      {formatDateTime(event.createdAt)} —{' '}
                      {event.fromStatus === event.toStatus
                        ? t(`payments.status.${event.toStatus}`)
                        : t('payments.detail.transition', {
                            from: event.fromStatus ? t(`payments.status.${event.fromStatus}`) : '—',
                            to: t(`payments.status.${event.toStatus}`),
                          })}
                      {event.actorRole ? ` (${event.actorRole})` : ''}
                      {staff && event.comment && event.comment.startsWith('POSTING_FAILED:')
                        ? ` — ${t('payments.detail.postingFailedEvent')}`
                        : ''}
                    </Typography>
                  ))}
                </Stack>
              </Box>
            </Stack>
          ) : null}
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={onClose}>{t('common.close')}</Button>
        </DialogActions>
      </Dialog>

      {payment && (dialog === 'verify' || dialog === 'evidence') ? (
        <VerifyPaymentDialog
          cooperativeId={cooperativeId}
          payment={payment}
          mode={dialog}
          onClose={() => setDialog(null)}
          onDone={() => void afterAction(dialog === 'verify' ? 'payments.verification.done' : 'payments.verification.evidenceDone')}
        />
      ) : null}
      {payment && dialog === 'withdraw' ? (
        <WithdrawPaymentDialog
          cooperativeId={cooperativeId}
          payment={payment}
          onClose={() => setDialog(null)}
          onDone={() => void afterAction('payments.withdraw.done')}
        />
      ) : null}
      {payment && dialog === 'reject' ? (
        <RejectPaymentDialog
          cooperativeId={cooperativeId}
          payment={payment}
          onClose={() => setDialog(null)}
          onDone={() => void afterAction('payments.reject.done')}
        />
      ) : null}
      {payment && (dialog === 'post' || dialog === 'retry') ? (
        <PostPaymentDialog
          cooperativeId={cooperativeId}
          payment={payment}
          posting={posting}
          mode={dialog}
          onClose={() => setDialog(null)}
          onSettled={(outcome) => {
            // Always refetch: the backend, not the response of one request, says whether the payment was posted.
            void refresh().then(() => {
              if (outcome === 'posted') {
                setDialog(null)
                enqueueSnackbar(t('payments.posting.done'), { variant: 'success' })
              }
            })
          }}
        />
      ) : null}
    </>
  )
}
