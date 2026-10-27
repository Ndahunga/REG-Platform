import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { useSnackbar } from 'notistack'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  deactivateMember,
  deleteMember,
  fetchMemberRemovalEligibility,
  reactivateMember,
} from '@/shared/api/members'
import { LoadingState } from '@/shared/components/LoadingState'
import { ROUTES } from '@/shared/constants/routes'
import { localizedApiError } from '@/shared/utils/localizedApiError'
import { formatMoney } from '@/shared/utils/formatMoney'
import { MemberFullDeletionDialog } from './MemberFullDeletionDialog'

interface MemberRemovalDialogProps {
  cooperativeId: string
  userId: string
  memberName: string
  open: boolean
  onClose: () => void
  /** Where to go after a permanent deletion (the member's own page no longer exists). */
  leaveAfterDelete?: boolean
}

type Mode = 'delete' | 'deactivate'

/**
 * Removes a member from the scheme in the only ways that keep financial history safe. The backend says which are
 * possible: a member with no history at all can be permanently deleted; anyone else is deactivated, which keeps every
 * record, loan and obligation. The dialog names exactly what will happen.
 */
export function MemberRemovalDialog({
  cooperativeId,
  userId,
  memberName,
  open,
  onClose,
  leaveAfterDelete = false,
}: MemberRemovalDialogProps) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [forceDeactivate, setForceDeactivate] = useState(false)
  const [reason, setReason] = useState('')
  const [attempted, setAttempted] = useState(false)
  const [fullDeletion, setFullDeletion] = useState(false)

  const eligibility = useQuery({
    queryKey: ['members', cooperativeId, userId, 'removal-eligibility'],
    queryFn: () => fetchMemberRemovalEligibility(cooperativeId, userId),
    enabled: open,
  })

  const finish = async (messageKey: string, leave: boolean) => {
    await queryClient.invalidateQueries({ queryKey: ['members', cooperativeId] })
    enqueueSnackbar(t(messageKey), { variant: 'success' })
    onClose()
    if (leave) navigate(ROUTES.members)
  }
  const remove = useMutation({
    mutationFn: () => deleteMember(cooperativeId, userId),
    onSuccess: () => finish('members.removal.deleted', leaveAfterDelete),
  })
  const deactivate = useMutation({
    mutationFn: () => deactivateMember(cooperativeId, userId, reason.trim()),
    onSuccess: () => finish('members.removal.deactivated', false),
  })
  const reactivate = useMutation({
    mutationFn: () => reactivateMember(cooperativeId, userId),
    onSuccess: () => finish('members.removal.reactivated', false),
  })

  const e = eligibility.data
  const busy = remove.isPending || deactivate.isPending || reactivate.isPending
  const failure = remove.error ?? deactivate.error ?? reactivate.error
  const mode: Mode | null = !e ? null : e.canDelete && !forceDeactivate ? 'delete' : e.canDeactivate ? 'deactivate' : null
  const reasonInvalid = reason.trim().length < 5

  const impactLines =
    e && mode === 'deactivate'
      ? [
          e.impact.openLoans > 0
            ? t('members.removal.impact.loans', {
                count: e.impact.openLoans,
                amount: formatMoney(e.impact.outstandingLoanPrincipal),
              })
            : null,
          Number(e.impact.unpaidContributions) > 0
            ? t('members.removal.impact.unpaid', { amount: formatMoney(e.impact.unpaidContributions) })
            : null,
          Number(e.impact.heldCredit) > 0
            ? t('members.removal.impact.credit', { amount: formatMoney(e.impact.heldCredit) })
            : null,
          e.impact.upcomingRecipientPeriods > 0
            ? t('members.removal.impact.rotation', { count: e.impact.upcomingRecipientPeriods })
            : null,
        ].filter((line): line is string => Boolean(line))
      : []

  const title = !e
    ? t('members.removal.loading')
    : e.canReactivate && !e.canDeactivate && !e.canDelete
      ? t('members.removal.reactivateTitle')
      : mode === 'delete'
        ? t('members.removal.deleteTitle')
        : mode === 'deactivate'
          ? t('members.removal.deactivateTitle')
          : t('members.removal.blockedTitle')

  return (
    <>
    <MemberFullDeletionDialog
      cooperativeId={cooperativeId}
      userId={userId}
      memberName={memberName}
      open={fullDeletion}
      onClose={() => {
        setFullDeletion(false)
        onClose()
      }}
      leaveAfterDelete={leaveAfterDelete}
    />
    <Dialog open={open && !fullDeletion} onClose={busy ? undefined : onClose} fullWidth maxWidth="sm" aria-label={title}>
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <Typography variant="body2" sx={{ fontWeight: 600 }}>
            {memberName}
          </Typography>
          {eligibility.isLoading ? <LoadingState variant="skeleton" rows={2} /> : null}
          {eligibility.isError ? (
            <Alert severity="error">{localizedApiError(t, eligibility.error, 'members.removal.reasons')}</Alert>
          ) : null}

          {e && mode === 'delete' ? <Alert severity="warning">{t('members.removal.deleteMessage')}</Alert> : null}

          {e && mode === 'deactivate' ? (
            <>
              <Alert severity="info">
                {e.canDelete ? t('members.removal.deactivateInsteadMessage') : t('members.removal.deactivateMessage')}
              </Alert>
              {impactLines.length > 0 ? (
                <Stack spacing={0.5} data-testid="member-removal-impact">
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>
                    {t('members.removal.impact.heading')}
                  </Typography>
                  {impactLines.map((line) => (
                    <Typography key={line} variant="body2">
                      {line}
                    </Typography>
                  ))}
                </Stack>
              ) : null}
              <TextField
                label={t('members.removal.reason')}
                value={reason}
                onChange={(event) => setReason(event.target.value)}
                error={attempted && reasonInvalid}
                helperText={attempted && reasonInvalid ? t('members.removal.reasonRequired') : ' '}
                multiline
                minRows={2}
                slotProps={{ htmlInput: { maxLength: 200 } }}
              />
            </>
          ) : null}

          {e && !mode && e.canReactivate ? <Alert severity="info">{t('members.removal.reactivateMessage')}</Alert> : null}

          {e && !mode && !e.canReactivate ? (
            <Alert severity="warning" data-testid="member-removal-blocked">
              {t(`members.removal.reasons.${e.deactivationBlockedReason ?? e.deletionBlockedReason}`, {
                defaultValue: t('members.removal.reasons.GENERIC'),
              })}
            </Alert>
          ) : null}

          {failure ? <Alert severity="error">{localizedApiError(t, failure, 'members.removal.reasons')}</Alert> : null}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose} disabled={busy}>
          {t('common.cancel')}
        </Button>
        {e && !e.canDelete ? (
          <Button color="error" onClick={() => setFullDeletion(true)} disabled={busy} data-testid="member-full-deletion-open">
            {t('members.fullDeletion.open')}
          </Button>
        ) : null}
        {e && mode === 'delete' && e.canDeactivate ? (
          <Button onClick={() => setForceDeactivate(true)} disabled={busy}>
            {t('members.removal.deactivateInstead')}
          </Button>
        ) : null}
        {e && mode === 'delete' ? (
          <Button color="error" variant="contained" onClick={() => remove.mutate()} disabled={busy}>
            {t('members.removal.deleteConfirm')}
          </Button>
        ) : null}
        {e && mode === 'deactivate' ? (
          <Button
            color="error"
            variant="contained"
            disabled={busy}
            onClick={() => {
              setAttempted(true)
              if (!reasonInvalid) deactivate.mutate()
            }}
          >
            {t('members.removal.deactivateConfirm')}
          </Button>
        ) : null}
        {e && !mode && e.canReactivate ? (
          <Button variant="contained" onClick={() => reactivate.mutate()} disabled={busy}>
            {t('members.removal.reactivateConfirm')}
          </Button>
        ) : null}
      </DialogActions>
    </Dialog>
    </>
  )
}
