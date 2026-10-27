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
import { deleteSchemePermanently, fetchSchemeDeletionPreview } from '@/shared/api/cooperatives'
import { LoadingState } from '@/shared/components/LoadingState'
import { SUPER_ADMIN_ROUTES } from '@/shared/constants/routes'
import { localizedApiError } from '@/shared/utils/localizedApiError'

interface Props {
  cooperativeId: string
  open: boolean
  onClose: () => void
}

/**
 * Platform administrators: deletes a scheme and all its data for good (beyond suspending or archiving it). Shows what
 * will be removed, asks for the reason and for the scheme's name to be typed. The deletion record and the audit trail
 * stay.
 */
export function SchemeDeletionDialog({ cooperativeId, open, onClose }: Props) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [typed, setTyped] = useState('')
  const [reason, setReason] = useState('')

  const preview = useQuery({
    queryKey: ['cooperatives', cooperativeId, 'permanent-deletion'],
    queryFn: () => fetchSchemeDeletionPreview(cooperativeId),
    enabled: open,
  })
  const remove = useMutation({
    mutationFn: () => deleteSchemePermanently(cooperativeId, { confirmName: typed.trim(), reason: reason.trim() }),
    onSuccess: async (result) => {
      await queryClient.invalidateQueries({ queryKey: ['cooperatives'] })
      enqueueSnackbar(t('cooperatives.permanentDeletion.done', { name: result.schemeName }), { variant: 'success' })
      onClose()
      navigate(SUPER_ADMIN_ROUTES.cooperatives)
    },
  })
  const p = preview.data
  const nameOk = Boolean(p) && typed.trim().toLowerCase() === p!.schemeName.trim().toLowerCase()
  const reasonOk = reason.trim().length >= 10
  const busy = remove.isPending

  return (
    <Dialog open={open} onClose={busy ? undefined : onClose} fullWidth maxWidth="sm" aria-label={t('cooperatives.permanentDeletion.title')}>
      <DialogTitle>{t('cooperatives.permanentDeletion.title')}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          {preview.isLoading ? <LoadingState variant="skeleton" rows={2} /> : null}
          {preview.isError ? <Alert severity="error">{localizedApiError(t, preview.error, 'cooperatives.permanentDeletion.reasons')}</Alert> : null}
          {p ? (
            <>
              <Alert severity="error">{t('cooperatives.permanentDeletion.warning', { name: p.schemeName })}</Alert>
              <Typography variant="body2">
                {t('cooperatives.permanentDeletion.impact', {
                  members: p.members,
                  accounts: p.accountsRemoved,
                  rows: p.rowsTotal,
                })}
              </Typography>
              <TextField
                label={t('cooperatives.permanentDeletion.reason')}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                helperText={t('cooperatives.permanentDeletion.reasonHint')}
                multiline
                minRows={2}
                slotProps={{ htmlInput: { maxLength: 500 } }}
              />
              <TextField
                label={t('cooperatives.permanentDeletion.typeName', { name: p.schemeName })}
                value={typed}
                onChange={(e) => setTyped(e.target.value)}
                autoComplete="off"
              />
            </>
          ) : null}
          {remove.error ? <Alert severity="error">{localizedApiError(t, remove.error, 'cooperatives.permanentDeletion.reasons')}</Alert> : null}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose} disabled={busy}>
          {t('common.cancel')}
        </Button>
        <Button color="error" variant="contained" disabled={!p || busy || !nameOk || !reasonOk} onClick={() => remove.mutate()}>
          {t('cooperatives.permanentDeletion.confirm')}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
