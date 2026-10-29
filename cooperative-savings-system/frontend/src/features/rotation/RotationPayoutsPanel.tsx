import {
  Alert,
  Box,
  Button,
  Checkbox,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ErrorNotice } from '@/features/payments/PaymentParts'
import { ReasonConfirmDialog } from '@/features/payments/ReasonConfirmDialog'
import { getErrorMessage } from '@/shared/api/client'
import {
  approveRotationPayout,
  cancelRotationPayout,
  confirmRotationDisbursement,
  fetchMyRotationPayouts,
  fetchRotationPayoutEvents,
  fetchRotationPayouts,
  rejectRotationPayout,
} from '@/shared/api/rotationPayouts'
import { ErrorState } from '@/shared/components/ErrorState'
import { FinancialActionButton } from '@/shared/components/FinancialActionButton'
import { LoadingState } from '@/shared/components/LoadingState'
import { PAYMENT_METHODS, type PaymentMethod } from '@/shared/types/memberPayment'
import type { RotationPayout, RotationPayoutStatus } from '@/shared/types/rotationPayout'
import { formatMoney } from '@/shared/utils/formatMoney'
import { TurnPayoutsSection } from './TurnPayoutsSection'

interface RotationPayoutsPanelProps {
  cooperativeId: string
  /** Officers see every payout and the actions they may take; a recipient sees only their own payouts. */
  staff: boolean
}

const statusColor = (status: RotationPayoutStatus): 'info' | 'warning' | 'success' | 'error' | 'default' =>
  status === 'DISBURSED' ? 'success' : status === 'APPROVED' ? 'warning' : status === 'REQUESTED' ? 'info' : status === 'REJECTED' ? 'error' : 'default'

type Active =
  | { kind: 'reject' | 'cancel'; payout: RotationPayout }
  | { kind: 'confirm'; payout: RotationPayout }
  | { kind: 'events'; payout: RotationPayout }
  | null

const periodLabel = (p: RotationPayout) => `${p.periodYear}-${String(p.periodMonth).padStart(2, '0')}`

/**
 * Rotation payouts. A payout is requested by one officer, approved by a different one, and only an explicit
 * confirmation of the ACTUAL disbursement (method and reference) completes it. Actions are shown only to officers who
 * may take them; the backend enforces the same rules again.
 */
export function RotationPayoutsPanel({ cooperativeId, staff }: RotationPayoutsPanelProps) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const queryClient = useQueryClient()
  const [active, setActive] = useState<Active>(null)
  const [approveError, setApproveError] = useState<unknown>(null)
  const [approving, setApproving] = useState<string | null>(null)

  const query = useQuery({
    queryKey: ['rotation', cooperativeId, 'payouts', staff ? 'all' : 'mine'],
    queryFn: () => (staff ? fetchRotationPayouts(cooperativeId) : fetchMyRotationPayouts(cooperativeId)),
  })

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['rotation', cooperativeId] })
  const done = async (key: string) => {
    setActive(null)
    await refresh()
    enqueueSnackbar(t(key), { variant: 'success' })
  }

  const approve = async (payout: RotationPayout) => {
    setApproving(payout.id)
    setApproveError(null)
    try {
      await approveRotationPayout(cooperativeId, payout.id)
      await done('rotation.payouts.approved')
    } catch (error) {
      setApproveError(error)
      await refresh()
    } finally {
      setApproving(null)
    }
  }

  if (query.isLoading) return <LoadingState variant="skeleton" rows={3} />
  if (query.isError) {
    return <ErrorState message={getErrorMessage(query.error, t('errors.generic'))} onRetry={() => void query.refetch()} />
  }
  const payouts = query.data ?? []

  return (
    <Stack spacing={2} data-testid="rotation-payouts">
      <Alert severity="info">{t('rotation.payouts.explain')}</Alert>
      {staff ? <TurnPayoutsSection cooperativeId={cooperativeId} /> : null}
      {payouts.length === 0 ? (
        <Typography variant="body2" color="text.secondary">
          {staff ? t('rotation.payouts.emptyStaff') : t('rotation.payouts.emptyMine')}
        </Typography>
      ) : null}
      {approveError ? <ErrorNotice error={approveError} /> : null}
      {payouts.map((p) => {
        // What the signed-in user may do is decided by the backend (permissions, independence, status).
        const actions = p.actions
        return (
          <Box key={p.id} sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 1, p: 2 }} data-testid="rotation-payout">
            <Stack spacing={1}>
              <Stack direction="row" spacing={1} useFlexGap sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
                <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
                  {t('rotation.payouts.line', { period: periodLabel(p), recipient: p.recipientName })}
                </Typography>
                <Chip size="small" color={statusColor(p.status)} label={t(`rotation.payouts.status.${p.status}`)} data-testid="rotation-payout-status" />
                {p.partial ? <Chip size="small" variant="outlined" color="warning" label={t('rotation.payouts.partial')} /> : null}
              </Stack>
              <Typography variant="body2">
                {t('rotation.payouts.amountLine', {
                  amount: formatMoney(p.amount),
                  entitlement: formatMoney(p.entitlementAtRequest),
                  available: formatMoney(p.availableAtRequest),
                })}
              </Typography>
              {Number(p.arrearsNetted ?? 0) > 0 ? (
                <Typography variant="body2" color="text.secondary" data-testid="rotation-payout-netted">
                  {t('rotation.share.nettedLine', { netted: formatMoney(p.arrearsNetted ?? 0), cash: formatMoney(p.cashAmount ?? 0) })}
                </Typography>
              ) : null}
              {p.partial ? (
                <Typography variant="body2" color="text.secondary" data-testid="rotation-payout-shortfall">
                  {t('rotation.payouts.shortfall', { remaining: formatMoney(p.remainingAfterPayout) })}
                </Typography>
              ) : null}
              {p.status === 'DISBURSED' ? (
                <Typography variant="body2" color="text.secondary">
                  {t('rotation.payouts.disbursedLine', {
                    method: p.disbursementMethod ? t(`payments.method.${p.disbursementMethod}`) : '',
                    reference: p.disbursementReference ?? '',
                  })}
                </Typography>
              ) : null}
              {p.status === 'APPROVED' ? (
                <Alert severity="warning">{t('rotation.payouts.approvedNotDisbursed')}</Alert>
              ) : null}
              {p.rejectionReason ? <Typography variant="body2">{t('rotation.payouts.rejectedBecause', { reason: p.rejectionReason })}</Typography> : null}
              {p.cancelReason ? <Typography variant="body2">{t('rotation.payouts.cancelledBecause', { reason: p.cancelReason })}</Typography> : null}

              <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
                <Button size="small" onClick={() => setActive({ kind: 'events', payout: p })}>
                  {t('rotation.payouts.history')}
                </Button>
                {actions?.canApprove ? (
                  <>
                    <FinancialActionButton
                      size="small"
                      variant="contained"
                      onClick={() => void approve(p)}
                      disabled={approving === p.id}
                    >
                      {approving === p.id ? t('rotation.payouts.approving') : t('rotation.payouts.approve')}
                    </FinancialActionButton>
                    <Button size="small" color="error" onClick={() => setActive({ kind: 'reject', payout: p })}>
                      {t('rotation.payouts.reject')}
                    </Button>
                  </>
                ) : null}
                {actions?.approveBlockedReason === 'REQUESTER_CANNOT_APPROVE' || actions?.approveBlockedReason === 'RECIPIENT_CANNOT_APPROVE' ? (
                  <Typography variant="caption" color="text.secondary" data-testid="rotation-payout-independence">
                    {actions.approveBlockedReason === 'RECIPIENT_CANNOT_APPROVE'
                      ? t('rotation.payouts.recipientCannotDecide')
                      : t('rotation.payouts.needsAnotherApprover')}
                  </Typography>
                ) : null}
                {actions?.canConfirmDisbursement ? (
                  <Button size="small" variant="contained" onClick={() => setActive({ kind: 'confirm', payout: p })}>
                    {t('rotation.payouts.confirmDisbursement')}
                  </Button>
                ) : null}
                {actions?.canCancel ? (
                  <Button size="small" onClick={() => setActive({ kind: 'cancel', payout: p })}>
                    {t('rotation.payouts.cancel')}
                  </Button>
                ) : null}
              </Stack>
            </Stack>
          </Box>
        )
      })}

      {active?.kind === 'reject' ? (
        <ReasonConfirmDialog
          title={t('rotation.payouts.rejectTitle')}
          explain={t('rotation.payouts.rejectExplain')}
          reasonLabel={t('rotation.payouts.reason')}
          submitLabel={t('rotation.payouts.reject')}
          danger
          onSubmit={(reason) => rejectRotationPayout(cooperativeId, active.payout.id, reason)}
          onClose={() => setActive(null)}
          onDone={() => void done('rotation.payouts.rejected')}
        />
      ) : null}
      {active?.kind === 'cancel' ? (
        <ReasonConfirmDialog
          title={t('rotation.payouts.cancelTitle')}
          explain={t('rotation.payouts.cancelExplain')}
          reasonLabel={t('rotation.payouts.reason')}
          submitLabel={t('rotation.payouts.cancel')}
          danger
          onSubmit={(reason) => cancelRotationPayout(cooperativeId, active.payout.id, reason)}
          onClose={() => setActive(null)}
          onDone={() => void done('rotation.payouts.cancelled')}
        />
      ) : null}
      {active?.kind === 'confirm' ? (
        <ConfirmDisbursementDialog
          cooperativeId={cooperativeId}
          payout={active.payout}
          onClose={() => setActive(null)}
          onDone={() => void done('rotation.payouts.disbursed')}
        />
      ) : null}
      {active?.kind === 'events' ? (
        <PayoutEventsDialog cooperativeId={cooperativeId} payout={active.payout} onClose={() => setActive(null)} />
      ) : null}
    </Stack>
  )
}

export function ConfirmDisbursementDialog({
  cooperativeId,
  payout,
  onClose,
  onDone,
}: {
  cooperativeId: string
  payout: RotationPayout
  onClose: () => void
  onDone: () => void
}) {
  const { t } = useTranslation()
  const [method, setMethod] = useState<PaymentMethod>('BANK_TRANSFER')
  const [reference, setReference] = useState('')
  const [note, setNote] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [attempted, setAttempted] = useState(false)
  const mutation = useMutation({
    mutationFn: () =>
      confirmRotationDisbursement(cooperativeId, payout.id, {
        method,
        reference: reference.trim(),
        note: note.trim() || undefined,
      }),
    onSuccess: () => onDone(),
  })
  const referenceMissing = reference.trim() === ''

  return (
    <Dialog open onClose={mutation.isPending ? undefined : onClose} fullWidth maxWidth="sm" aria-label={t('rotation.payouts.confirmTitle')}>
      <DialogTitle>{t('rotation.payouts.confirmTitle')}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <Alert severity="warning">{t('rotation.payouts.confirmExplain')}</Alert>
          <Typography variant="body2">
            {t('rotation.payouts.confirmSummary', { amount: formatMoney(payout.amount), recipient: payout.recipientName })}
          </Typography>
          <TextField select label={t('rotation.payouts.method')} value={method} onChange={(e) => setMethod(e.target.value as PaymentMethod)}>
            {PAYMENT_METHODS.map((m) => (
              <MenuItem key={m} value={m}>
                {t(`payments.method.${m}`)}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            label={t('rotation.payouts.reference')}
            value={reference}
            onChange={(e) => setReference(e.target.value)}
            required
            error={attempted && referenceMissing}
            helperText={attempted && referenceMissing ? t('rotation.payouts.referenceRequired') : t('rotation.payouts.referenceHint')}
            slotProps={{ htmlInput: { maxLength: 128 } }}
          />
          <TextField
            label={t('rotation.payouts.note')}
            value={note}
            onChange={(e) => setNote(e.target.value)}
            multiline
            minRows={2}
            slotProps={{ htmlInput: { maxLength: 2000 } }}
          />
          <FormControlLabel
            control={<Checkbox checked={confirmed} onChange={(_, v) => setConfirmed(v)} />}
            label={t('rotation.payouts.confirmCheckbox')}
          />
          {attempted && !confirmed ? (
            <Typography variant="caption" color="error" role="alert">
              {t('rotation.payouts.confirmRequired')}
            </Typography>
          ) : null}
          {mutation.isError ? <ErrorNotice error={mutation.error} /> : null}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose} disabled={mutation.isPending}>
          {t('common.cancel')}
        </Button>
        <FinancialActionButton
          variant="contained"
          disabled={mutation.isPending}
          onClick={() => {
            setAttempted(true)
            if (referenceMissing || !confirmed || mutation.isPending) return
            mutation.mutate()
          }}
          sx={{ minHeight: 44 }}
        >
          {mutation.isPending ? t('rotation.payouts.confirming') : t('rotation.payouts.confirmSubmit')}
        </FinancialActionButton>
      </DialogActions>
    </Dialog>
  )
}

function PayoutEventsDialog({
  cooperativeId,
  payout,
  onClose,
}: {
  cooperativeId: string
  payout: RotationPayout
  onClose: () => void
}) {
  const { t } = useTranslation()
  const query = useQuery({
    queryKey: ['rotation', cooperativeId, 'payout-events', payout.id],
    queryFn: () => fetchRotationPayoutEvents(cooperativeId, payout.id),
  })
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm" aria-label={t('rotation.payouts.history')}>
      <DialogTitle>{t('rotation.payouts.history')}</DialogTitle>
      <DialogContent>
        {query.isLoading ? <LoadingState variant="skeleton" rows={2} /> : null}
        {query.isError ? <ErrorNotice error={query.error} /> : null}
        <Stack spacing={0.5} sx={{ pt: 1 }} data-testid="rotation-payout-events">
          {(query.data ?? []).map((event) => (
            <Typography key={event.id} variant="body2">
              {new Date(event.createdAt).toLocaleString()} — {t(`rotation.payouts.status.${event.toStatus}`)}
              {event.actorRole ? ` (${event.actorRole})` : ''}
            </Typography>
          ))}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose}>{t('common.close')}</Button>
      </DialogActions>
    </Dialog>
  )
}
