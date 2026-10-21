import { Alert, Box, Button, Chip, Stack, Typography } from '@mui/material'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useAppSelector } from '@/app/store/hooks'
import {
  approvePaymentReversal,
  declinePaymentReversal,
  fetchPaymentReversals,
  requestPaymentReversal,
  withdrawPaymentReversal,
} from '@/shared/api/memberPayments'
import { ConfirmDialog } from '@/shared/components/ConfirmDialog'
import { FinancialActionButton } from '@/shared/components/FinancialActionButton'
import type { MemberPayment, PaymentReversal } from '@/shared/types/memberPayment'
import { PERMISSION_CONTRIBUTION_WRITE, hasPermission } from '@/shared/types/auth'
import { formatMoney } from '@/shared/utils/formatMoney'
import { ErrorNotice, periodLabel, shortId } from './PaymentParts'
import { ReasonConfirmDialog } from './ReasonConfirmDialog'

interface PaymentReversalSectionProps {
  cooperativeId: string
  payment: MemberPayment
}

type Active = { kind: 'request' } | { kind: 'decline'; reversal: PaymentReversal } | { kind: 'withdraw'; reversal: PaymentReversal } | null

const reversalColor = (status: PaymentReversal['status']) =>
  status === 'EXECUTED' ? 'success' : status === 'DECLINED' || status === 'WITHDRAWN' ? 'default' : 'warning'

/**
 * Contribution-only reversal of a posted payment (officers). A request moves no money; a different officer approves it
 * and the backend executes the compensating accounting atomically, or refuses with its reason (a later change to the
 * same period, or funds already paid out to a recipient). Loan-inclusive payments are explained as blocked.
 */
export function PaymentReversalSection({ cooperativeId, payment }: PaymentReversalSectionProps) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const queryClient = useQueryClient()
  const user = useAppSelector((s) => s.auth.user)
  const [active, setActive] = useState<Active>(null)
  const [approveError, setApproveError] = useState<unknown>(null)
  const [approving, setApproving] = useState(false)

  const relevant = payment.status === 'POSTED' || payment.status === 'REVERSED'
  const query = useQuery({
    queryKey: ['member-payments', cooperativeId, 'reversals', payment.id],
    queryFn: () => fetchPaymentReversals(cooperativeId, payment.id),
    enabled: relevant,
  })
  if (!relevant) return null

  const canHandle = hasPermission(user, PERMISSION_CONTRIBUTION_WRITE)
  // Approving or declining is a fund movement decision: it needs the independent fund authority.
  const canApprove = hasPermission(user, 'FUND_AUTHORIZE')
  const involvesLoanOrCredit = payment.allocations.some((a) => a.type !== 'CONTRIBUTION')
  const reversals = query.data ?? []
  const open = reversals.find((r) => r.status === 'REQUESTED')
  const isPayer = user?.id === payment.memberUserId

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['member-payments', cooperativeId] })
  const done = async (messageKey: string) => {
    setActive(null)
    await refresh()
    enqueueSnackbar(t(messageKey), { variant: 'success' })
  }

  const approve = async (reversal: PaymentReversal) => {
    setApproving(true)
    setApproveError(null)
    try {
      await approvePaymentReversal(cooperativeId, reversal.id)
      await done('payments.reversal.approved')
    } catch (error) {
      setApproveError(error)
      await refresh()
    } finally {
      setApproving(false)
    }
  }

  const blockedForApproval = (r: PaymentReversal): boolean =>
    r.requestedBy === user?.id || isPayer || payment.submittedBy === user?.id

  return (
    <Box data-testid="payment-reversal-section">
      <Typography variant="subtitle2" gutterBottom>
        {t('payments.reversal.title')}
      </Typography>
      <Stack spacing={1}>
        {payment.status === 'POSTED' && involvesLoanOrCredit ? (
          <Alert severity="info" data-testid="payment-reversal-loan-note">
            {t('payments.reversal.loanNote')}
          </Alert>
        ) : null}

        {reversals.map((r) => (
          <Box key={r.id} sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 1, p: 1 }} data-testid="payment-reversal">
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
              <Chip size="small" color={reversalColor(r.status)} label={t(`payments.reversal.status.${r.status}`)} />
              <Typography variant="body2">{t('payments.reversal.reason', { reason: r.reason })}</Typography>
            </Stack>
            {r.decisionNote ? (
              <Typography variant="caption" color="text.secondary" component="div">
                {t('payments.reversal.decisionNote', { note: r.decisionNote })}
              </Typography>
            ) : null}
            {r.takenBack.map((tb) => (
              <Typography key={tb.postingId} variant="caption" color="text.secondary" component="div" data-testid="payment-reversal-takenback">
                {t('payments.reversal.takenBack', {
                  period: periodLabel(tb.periodYear, tb.periodMonth),
                  amount: formatMoney(tb.amount, { currency: payment.currency }),
                  paidAfter: formatMoney(tb.paidAfter, { currency: payment.currency }),
                })}
                {tb.rotatingAmount != null
                  ? ` — ${t('payments.reversal.split', {
                      rotating: formatMoney(tb.rotatingAmount, { currency: payment.currency }),
                      retained: formatMoney(tb.retainedAmount ?? 0, { currency: payment.currency }),
                    })}`
                  : ''}
              </Typography>
            ))}
            {(r.loansGivenBack ?? []).map((lb) => (
              <Typography key={lb.postingId} variant="caption" color="text.secondary" component="div" data-testid="payment-reversal-loan">
                {t('payments.reversal.loanGivenBack', {
                  loan: shortId(lb.loanId),
                  principal: formatMoney(lb.principalAmount, { currency: payment.currency }),
                  interest: formatMoney(lb.interestAmount, { currency: payment.currency }),
                  outstanding: formatMoney(lb.outstandingPrincipalAfter, { currency: payment.currency }),
                })}
              </Typography>
            ))}
            {(r.creditsTakenBack ?? []).map((ct) => (
              <Typography key={ct.journalEntryId} variant="caption" color="text.secondary" component="div" data-testid="payment-reversal-credit">
                {t('payments.reversal.creditTakenBack', {
                  purpose: t(`payments.credit.purposes.${ct.purpose}`),
                  amount: formatMoney(ct.amount, { currency: payment.currency }),
                })}
              </Typography>
            ))}
            {r.status === 'REQUESTED' && r.requestedBy === user?.id && canHandle ? (
              <Box sx={{ mt: 1 }}>
                <Button variant="outlined" onClick={() => setActive({ kind: 'withdraw', reversal: r })} sx={{ minHeight: 44 }}>
                  {t('payments.reversal.withdraw')}
                </Button>
              </Box>
            ) : null}
            {r.status === 'REQUESTED' && !canApprove && r.requestedBy !== user?.id ? (
              <Alert severity="info" sx={{ mt: 1 }} data-testid="payment-reversal-needs-authority">
                {t('payments.reversal.needsFundAuthority')}
              </Alert>
            ) : null}
            {r.status === 'REQUESTED' && canApprove ? (
              blockedForApproval(r) ? (
                <Alert severity="info" sx={{ mt: 1 }} data-testid="payment-reversal-independence">
                  {t('payments.reversal.needsAnotherOfficer')}
                </Alert>
              ) : (
                <Stack direction="row" spacing={1} sx={{ mt: 1 }}>
                  <FinancialActionButton
                    variant="contained"
                    onClick={() => void approve(r)}
                    disabled={approving}
                    sx={{ minHeight: 44 }}
                  >
                    {approving ? t('payments.reversal.approving') : t('payments.reversal.approve')}
                  </FinancialActionButton>
                  <Button variant="outlined" onClick={() => setActive({ kind: 'decline', reversal: r })} sx={{ minHeight: 44 }}>
                    {t('payments.reversal.decline')}
                  </Button>
                </Stack>
              )
            ) : null}
          </Box>
        ))}
        {approveError ? <ErrorNotice error={approveError} /> : null}

        {payment.status === 'POSTED' && canHandle && !isPayer && !open ? (
          <Box>
            <Button variant="outlined" color="warning" onClick={() => setActive({ kind: 'request' })} sx={{ minHeight: 44 }}>
              {t('payments.reversal.request')}
            </Button>
          </Box>
        ) : null}
      </Stack>

      {active?.kind === 'request' ? (
        <ReasonConfirmDialog
          title={t('payments.reversal.requestTitle')}
          explain={t('payments.reversal.requestExplain')}
          reasonLabel={t('payments.reversal.reasonLabel')}
          submitLabel={t('payments.reversal.requestSubmit')}
          onSubmit={(reason) => requestPaymentReversal(cooperativeId, payment.id, reason)}
          onClose={() => setActive(null)}
          onDone={() => void done('payments.reversal.requested')}
        />
      ) : null}
      {active?.kind === 'withdraw' ? (
        <ConfirmDialog
          open
          title={t('payments.reversal.withdrawTitle')}
          message={t('payments.reversal.withdrawExplain')}
          confirmLabel={t('payments.reversal.withdraw')}
          onCancel={() => setActive(null)}
          onConfirm={() => {
            const target = active.reversal
            void (async () => {
              try {
                await withdrawPaymentReversal(cooperativeId, target.id)
                await done('payments.reversal.withdrawn')
              } catch (error) {
                setActive(null)
                setApproveError(error)
                await refresh()
              }
            })()
          }}
        />
      ) : null}
      {active?.kind === 'decline' ? (
        <ReasonConfirmDialog
          title={t('payments.reversal.declineTitle')}
          explain={t('payments.reversal.declineExplain')}
          reasonLabel={t('payments.reversal.declineReason')}
          submitLabel={t('payments.reversal.decline')}
          onSubmit={(reason) => declinePaymentReversal(cooperativeId, active.reversal.id, reason)}
          onClose={() => setActive(null)}
          onDone={() => void done('payments.reversal.declined')}
        />
      ) : null}
    </Box>
  )
}
