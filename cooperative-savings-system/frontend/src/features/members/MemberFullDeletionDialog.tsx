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
import { useSnackbar } from 'notistack'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { deleteMemberFully, fetchMemberFullDeletion } from '@/shared/api/members'
import { LoadingState } from '@/shared/components/LoadingState'
import { ROUTES } from '@/shared/constants/routes'
import { localizedApiError } from '@/shared/utils/localizedApiError'

interface Props {
  cooperativeId: string
  userId: string
  memberName: string
  open: boolean
  onClose: () => void
  /** Leave the member's own page after deleting (it no longer exists). */
  leaveAfterDelete?: boolean
}

/**
 * President or Vice President: deletes a member and every record they have in the scheme, for good. Meant for
 * records that should never have been there (a member uploaded with false information, say). The dialog says how much
 * goes, asks for the reason and for the username to be typed; the deletion itself is kept in the audit trail.
 */
export function MemberFullDeletionDialog({ cooperativeId, userId, memberName, open, onClose, leaveAfterDelete }: Props) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [reason, setReason] = useState('')
  const [typed, setTyped] = useState('')

  const preview = useQuery({
    queryKey: ['members', cooperativeId, userId, 'full-deletion'],
    queryFn: () => fetchMemberFullDeletion(cooperativeId, userId),
    enabled: open,
  })
  const remove = useMutation({
    mutationFn: () => deleteMemberFully(cooperativeId, userId, { reason: reason.trim(), confirmUsername: typed.trim() }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['members', cooperativeId] })
      enqueueSnackbar(t('members.fullDeletion.done'), { variant: 'success' })
      onClose()
      if (leaveAfterDelete) navigate(ROUTES.members)
    },
  })

  const p = preview.data
  const reasonOk = reason.trim().length >= 10
  const nameOk = Boolean(p) && typed.trim().toLowerCase() === p!.username.toLowerCase()
  const busy = remove.isPending

  return (
    <Dialog open={open} onClose={busy ? undefined : onClose} fullWidth maxWidth="sm" aria-label={t('members.fullDeletion.title')}>
      <DialogTitle>{t('members.fullDeletion.title')}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <Typography variant="body2" sx={{ fontWeight: 600 }}>
            {memberName}
          </Typography>
          {preview.isLoading ? <LoadingState variant="skeleton" rows={2} /> : null}
          {preview.isError ? (
            <Alert severity="error">{localizedApiError(t, preview.error, 'members.fullDeletion.reasons')}</Alert>
          ) : null}
          {p && !p.canPurge ? (
            <Alert severity="warning" data-testid="member-full-deletion-blocked">
              {t(`members.fullDeletion.reasons.${p.blockedReason}`, { defaultValue: t('members.fullDeletion.reasons.GENERIC') })}
              {p.blockedDetails && p.blockedDetails.length > 0 ? ` (${p.blockedDetails.join(', ')})` : ''}
            </Alert>
          ) : null}
          {p && p.canPurge ? (
            <>
              <Alert severity="error">{t('members.fullDeletion.warning')}</Alert>
              <Typography variant="body2">
                {t('members.fullDeletion.impact', { count: p.rowsTotal })}
                {p.accountRemoved ? ` ${t('members.fullDeletion.accountRemoved')}` : ` ${t('members.fullDeletion.accountKept')}`}
              </Typography>
              <TextField
                label={t('members.fullDeletion.reason')}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                helperText={t('members.fullDeletion.reasonHint')}
                multiline
                minRows={2}
                slotProps={{ htmlInput: { maxLength: 500 } }}
              />
              <TextField
                label={t('members.fullDeletion.typeUsername', { username: p.username })}
                value={typed}
                onChange={(e) => setTyped(e.target.value)}
                autoComplete="off"
              />
            </>
          ) : null}
          {remove.error ? <Alert severity="error">{localizedApiError(t, remove.error, 'members.fullDeletion.reasons')}</Alert> : null}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose} disabled={busy}>
          {t('common.cancel')}
        </Button>
        {p && p.canPurge ? (
          <Button color="error" variant="contained" disabled={busy || !reasonOk || !nameOk} onClick={() => remove.mutate()}>
            {t('members.fullDeletion.confirm')}
          </Button>
        ) : null}
      </DialogActions>
    </Dialog>
  )
}
