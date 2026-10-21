import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Stack,
  TextField,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useAppSelector } from '@/app/store/hooks'
import {
  fetchMigrationStrategy,
  resolveMigrationReview,
  type MigrationStrategy,
} from '@/shared/api/migrationStrategy'
import { hasPermission } from '@/shared/types/auth'
import { localizedApiError } from '@/shared/utils/localizedApiError'

/**
 * Shown while a Saving Scheme is flagged for migration review (it brought its history in both ways before the
 * safeguard existed). Historical imports are refused until a person with fund authority resolves it, saying which
 * strategy is authoritative and how the scheme was reconciled. Everything else in the application keeps working.
 */
export function MigrationReviewNotice({ cooperativeId }: { cooperativeId: string }) {
  const { t } = useTranslation()
  const user = useAppSelector((s) => s.auth.user)
  const canResolve = hasPermission(user, 'FUND_AUTHORIZE')
  const [open, setOpen] = useState(false)
  const state = useQuery({
    queryKey: ['migration-strategy', cooperativeId],
    queryFn: () => fetchMigrationStrategy(cooperativeId),
    enabled: Boolean(cooperativeId),
  })
  if (!state.data?.needsReview) return null
  return (
    <>
      <Alert
        severity="warning"
        sx={{ mb: 2 }}
        data-testid="migration-review-notice"
        action={
          canResolve ? (
            <Button color="inherit" size="small" onClick={() => setOpen(true)}>
              {t('migrationReview.resolve')}
            </Button>
          ) : undefined
        }
      >
        {canResolve ? t('migrationReview.noticeAuthority') : t('migrationReview.notice')}
      </Alert>
      {open ? <ResolveDialog cooperativeId={cooperativeId} onClose={() => setOpen(false)} /> : null}
    </>
  )
}

function ResolveDialog({ cooperativeId, onClose }: { cooperativeId: string; onClose: () => void }) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const queryClient = useQueryClient()
  const [strategy, setStrategy] = useState<MigrationStrategy>('OPENING_BALANCE')
  const [cutover, setCutover] = useState('')
  const [note, setNote] = useState('')
  const [attempted, setAttempted] = useState(false)
  const noteInvalid = note.trim().length < 20
  const cutoverInvalid = strategy === 'OPENING_BALANCE' && !cutover
  const mutation = useMutation({
    mutationFn: () =>
      resolveMigrationReview(cooperativeId, {
        strategy,
        cutoverDate: strategy === 'OPENING_BALANCE' ? cutover : undefined,
        note: note.trim(),
      }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['migration-strategy', cooperativeId] })
      enqueueSnackbar(t('migrationReview.resolved'), { variant: 'success' })
      onClose()
    },
  })
  return (
    <Dialog open onClose={mutation.isPending ? undefined : onClose} fullWidth maxWidth="sm" aria-label={t('migrationReview.resolveTitle')}>
      <DialogTitle>{t('migrationReview.resolveTitle')}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <Alert severity="info">{t('migrationReview.resolveExplain')}</Alert>
          <TextField
            select
            label={t('migrationReview.strategy')}
            value={strategy}
            onChange={(e) => setStrategy(e.target.value as MigrationStrategy)}
          >
            <MenuItem value="OPENING_BALANCE">{t('migrationReview.strategies.OPENING_BALANCE')}</MenuItem>
            <MenuItem value="DETAILED_TRANSACTION_HISTORY">{t('migrationReview.strategies.DETAILED_TRANSACTION_HISTORY')}</MenuItem>
          </TextField>
          {strategy === 'OPENING_BALANCE' ? (
            <TextField
              type="date"
              label={t('migrationReview.cutover')}
              value={cutover}
              onChange={(e) => setCutover(e.target.value)}
              error={attempted && cutoverInvalid}
              slotProps={{ inputLabel: { shrink: true } }}
            />
          ) : null}
          <TextField
            label={t('migrationReview.note')}
            value={note}
            onChange={(e) => setNote(e.target.value)}
            multiline
            minRows={3}
            error={attempted && noteInvalid}
            helperText={attempted && noteInvalid ? t('migrationReview.noteRequired') : ' '}
            slotProps={{ htmlInput: { maxLength: 1000 } }}
          />
          {mutation.isError ? <Alert severity="error">{localizedApiError(t, mutation.error, 'historicalImport.errorCodes')}</Alert> : null}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose} disabled={mutation.isPending}>
          {t('common.cancel')}
        </Button>
        <Button
          variant="contained"
          disabled={mutation.isPending}
          onClick={() => {
            setAttempted(true)
            if (!noteInvalid && !cutoverInvalid) mutation.mutate()
          }}
        >
          {t('migrationReview.resolveConfirm')}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
