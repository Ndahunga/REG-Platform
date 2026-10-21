import {
  Alert,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { useMutation } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { FinancialActionButton } from '@/shared/components/FinancialActionButton'
import { ErrorNotice } from './PaymentParts'

interface ReasonConfirmDialogProps {
  title: string
  explain: string
  /** When set the officer must also tick this explicit confirmation before submitting. */
  confirmLabel?: string
  reasonLabel: string
  submitLabel: string
  /** Runs the action with the written reason; the dialog shows the backend error code if it is refused. */
  onSubmit: (reason: string) => Promise<unknown>
  onClose: () => void
  onDone: () => void
  /** Use the error colour for destructive actions. */
  danger?: boolean
}

/** A reason plus (optionally) an explicit confirmation, for decisions that change money or refuse a request. */
export function ReasonConfirmDialog({
  title,
  explain,
  confirmLabel,
  reasonLabel,
  submitLabel,
  onSubmit,
  onClose,
  onDone,
  danger,
}: ReasonConfirmDialogProps) {
  const { t } = useTranslation()
  const [reason, setReason] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [attempted, setAttempted] = useState(false)
  const mutation = useMutation({ mutationFn: (value: string) => onSubmit(value), onSuccess: () => onDone() })
  const missing = reason.trim() === ''

  const submit = () => {
    setAttempted(true)
    if (missing || (confirmLabel && !confirmed) || mutation.isPending) return
    mutation.mutate(reason.trim())
  }

  return (
    <Dialog open onClose={mutation.isPending ? undefined : onClose} fullWidth maxWidth="sm" aria-label={title}>
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <Alert severity={danger ? 'warning' : 'info'}>{explain}</Alert>
          <TextField
            label={reasonLabel}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            multiline
            minRows={2}
            required
            error={attempted && missing}
            helperText={attempted && missing ? t('payments.reason.required') : undefined}
            slotProps={{ htmlInput: { maxLength: 2000 } }}
          />
          {confirmLabel ? (
            <>
              <FormControlLabel
                control={<Checkbox checked={confirmed} onChange={(_, v) => setConfirmed(v)} />}
                label={confirmLabel}
              />
              {attempted && !confirmed ? (
                <Typography variant="caption" color="error" role="alert">
                  {t('payments.reason.confirmRequired')}
                </Typography>
              ) : null}
            </>
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
          color={danger ? 'error' : 'primary'}
          onClick={submit}
          disabled={mutation.isPending}
          sx={{ minHeight: 44 }}
        >
          {submitLabel}
        </FinancialActionButton>
      </DialogActions>
    </Dialog>
  )
}
