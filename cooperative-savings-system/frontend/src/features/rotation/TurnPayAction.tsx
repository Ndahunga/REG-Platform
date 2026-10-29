import {
  Alert,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  Typography,
} from '@mui/material'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ErrorNotice } from '@/features/payments/PaymentParts'
import { getErrorMessage } from '@/shared/api/client'
import { requestTurnPayout } from '@/shared/api/rotation'
import { approveRotationPayout } from '@/shared/api/rotationPayouts'
import { FinancialActionButton } from '@/shared/components/FinancialActionButton'
import type { RotationTurn, SharePool, TurnPayoutEligibility } from '@/shared/types/rotation'
import type { RotationPayout } from '@/shared/types/rotationPayout'
import { ConfirmDisbursementDialog } from './RotationPayoutsPanel'
import { monthKey, newIdempotencyKey } from './rotationHelpers'
import { money } from './shareCycle'

/**
 * Paying a member's turn from the schedule itself, one step at a time: Pay (request), Approve (a different officer),
 * Confirm paid (the actual transfer and its reference). Each button appears only when the signed-in officer may take
 * that step; the backend checks again.
 */
export function TurnPayAction({
  cooperativeId,
  turn,
  eligibility,
  openPayout,
  pool,
}: {
  cooperativeId: string
  turn: RotationTurn
  eligibility?: TurnPayoutEligibility
  openPayout?: RotationPayout
  pool?: SharePool
}) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const queryClient = useQueryClient()
  const [payOpen, setPayOpen] = useState(false)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['rotation', cooperativeId] })

  const approve = useMutation({
    mutationFn: (id: string) => approveRotationPayout(cooperativeId, id),
    onSuccess: async () => {
      await refresh()
      enqueueSnackbar(t('rotation.payouts.approved'), { variant: 'success' })
    },
    onError: (error) => enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' }),
  })

  if (openPayout) {
    const actions = openPayout.actions
    if (openPayout.status === 'REQUESTED') {
      return actions?.canApprove ? (
        <FinancialActionButton
          size="small"
          variant="contained"
          disabled={approve.isPending}
          onClick={() => approve.mutate(openPayout.id)}
        >
          {t('rotation.pay.approve', { amount: money(openPayout.amount) })}
        </FinancialActionButton>
      ) : (
        <Chip size="small" color="info" variant="outlined" label={t('rotation.pay.awaitingApproval')} />
      )
    }
    if (openPayout.status === 'APPROVED') {
      return (
        <>
          {actions?.canConfirmDisbursement ? (
            <Button size="small" variant="contained" color="success" onClick={() => setConfirmOpen(true)}>
              {t('rotation.pay.confirmPaid')}
            </Button>
          ) : (
            <Chip size="small" color="warning" variant="outlined" label={t('rotation.pay.awaitingTransfer')} />
          )}
          {confirmOpen ? (
            <ConfirmDisbursementDialog
              cooperativeId={cooperativeId}
              payout={openPayout}
              onClose={() => setConfirmOpen(false)}
              onDone={() => {
                setConfirmOpen(false)
                void refresh()
                enqueueSnackbar(t('rotation.payouts.disbursed'), { variant: 'success' })
              }}
            />
          ) : null}
        </>
      )
    }
  }

  if (Number(turn.outstanding) <= 0 || !eligibility) return null
  if (!eligibility.canRequestPayout) {
    return (
      <Chip size="small" variant="outlined" label={t(`rotation.share.blocked.${eligibility.payoutBlockedReason ?? 'OTHER'}`)} />
    )
  }
  return (
    <>
      <Button size="small" variant="contained" onClick={() => setPayOpen(true)}>
        {t('rotation.pay.pay')}
      </Button>
      {payOpen ? (
        <PayTurnDialog
          cooperativeId={cooperativeId}
          eligibility={eligibility}
          pool={pool}
          onClose={() => setPayOpen(false)}
          onDone={() => {
            setPayOpen(false)
            void refresh()
            enqueueSnackbar(t('rotation.pay.requested'), { variant: 'success' })
          }}
        />
      ) : null}
    </>
  )
}

function PayTurnDialog({
  cooperativeId,
  eligibility,
  pool,
  onClose,
  onDone,
}: {
  cooperativeId: string
  eligibility: TurnPayoutEligibility
  pool?: SharePool
  onClose: () => void
  onDone: () => void
}) {
  const { t } = useTranslation()
  const max = Number(eligibility.maxRequestable) || Number(eligibility.outstanding)
  const netted = Math.min(Number(eligibility.arrears), max)
  const request = useMutation({
    mutationFn: () => requestTurnPayout(cooperativeId, eligibility.turnId, undefined, newIdempotencyKey()),
    onSuccess: onDone,
  })
  return (
    <Dialog open onClose={request.isPending ? undefined : onClose} fullWidth maxWidth="sm">
      <DialogTitle>
        {t('rotation.pay.title', {
          name: eligibility.memberName,
          month: monthKey(eligibility.periodYear, eligibility.periodMonth),
        })}
      </DialogTitle>
      <DialogContent>
        <Stack spacing={1.25} sx={{ pt: 1 }}>
          <Row label={t('rotation.pay.turnAmount')} value={money(eligibility.outstanding)} />
          {netted > 0 ? <Row label={t('rotation.pay.netted')} value={`− ${money(netted)}`} /> : null}
          <Row label={t('rotation.pay.cashToHand')} value={money(max - netted)} strong />
          {max < Number(eligibility.outstanding) ? (
            <Alert severity="warning">{t('rotation.pay.partial', { amount: money(max), rest: money(Number(eligibility.outstanding) - max) })}</Alert>
          ) : null}
          {pool ? (
            <Typography variant="caption" color="text.secondary">
              {t('rotation.share.poolLine', { available: money(pool.available), collected: money(pool.collected), paid: money(pool.paidOut) })}
            </Typography>
          ) : null}
          <Alert severity="info">{t('rotation.pay.steps')}</Alert>
          {request.isError ? <ErrorNotice error={request.error} /> : null}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose} disabled={request.isPending}>
          {t('common.cancel')}
        </Button>
        <FinancialActionButton variant="contained" disabled={request.isPending} onClick={() => request.mutate()}>
          {t('rotation.pay.request', { amount: money(max) })}
        </FinancialActionButton>
      </DialogActions>
    </Dialog>
  )
}

function Row({ label, value, strong }: { label: string; value: string; strong?: boolean }) {
  return (
    <Stack direction="row" sx={{ justifyContent: 'space-between' }}>
      <Typography variant="body2">{label}</Typography>
      <Typography variant="body2" sx={{ fontWeight: strong ? 700 : 400 }}>
        {value}
      </Typography>
    </Stack>
  )
}
