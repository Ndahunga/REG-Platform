import { Alert, Autocomplete, Button, Dialog, DialogActions, DialogContent, DialogTitle, Stack, TextField } from '@mui/material'
import { useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'

export interface RecipientOption {
  userId: string
  label: string
}

interface ReasonDialogProps {
  open: boolean
  title: string
  description?: ReactNode
  submitLabel: string
  loading?: boolean
  /** When given, the user must also pick a member (used to reassign a recipient). */
  recipientOptions?: RecipientOption[]
  onClose: () => void
  onSubmit: (reason: string, recipientUserId?: string) => void
}

/** A mandatory written reason, optionally together with a recipient choice. Used to cancel a cycle or reassign a turn. */
export function ReasonDialog({
  open,
  title,
  description,
  submitLabel,
  loading,
  recipientOptions,
  onClose,
  onSubmit,
}: ReasonDialogProps) {
  const { t } = useTranslation()
  const [reason, setReason] = useState('')
  const [recipient, setRecipient] = useState<RecipientOption | null>(null)
  const [submitted, setSubmitted] = useState(false)

  const needsRecipient = recipientOptions !== undefined
  const reasonProblem = !reason.trim()
  const recipientProblem = needsRecipient && !recipient

  const close = () => {
    setReason('')
    setRecipient(null)
    setSubmitted(false)
    onClose()
  }

  const submit = () => {
    setSubmitted(true)
    if (reasonProblem || recipientProblem) return
    onSubmit(reason.trim(), recipient?.userId)
  }

  return (
    <Dialog open={open} onClose={loading ? undefined : close} fullWidth maxWidth="sm">
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {description ? <Alert severity="info">{description}</Alert> : null}
          {needsRecipient ? (
            <Autocomplete
              options={recipientOptions}
              value={recipient}
              onChange={(_, value) => setRecipient(value)}
              getOptionLabel={(option) => option.label}
              isOptionEqualToValue={(a, b) => a.userId === b.userId}
              renderInput={(params) => (
                <TextField
                  {...params}
                  required
                  label={t('rotation.recipient.newRecipient')}
                  error={submitted && recipientProblem}
                  helperText={submitted && recipientProblem ? t('rotation.recipient.recipientRequired') : undefined}
                />
              )}
            />
          ) : null}
          <TextField
            label={t('rotation.reason')}
            required
            multiline
            minRows={2}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            error={submitted && reasonProblem}
            helperText={submitted && reasonProblem ? t('rotation.reasonRequired') : undefined}
            slotProps={{ htmlInput: { maxLength: 1000 } }}
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={close} disabled={loading}>
          {t('common.cancel')}
        </Button>
        <Button variant="contained" onClick={submit} disabled={loading}>
          {submitLabel}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
