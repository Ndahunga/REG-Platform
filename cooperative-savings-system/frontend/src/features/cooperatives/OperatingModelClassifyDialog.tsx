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
} from '@mui/material'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { SchemeOperatingModel } from '@/shared/types/cooperative'
import { OperatingModelSelect } from './OperatingModelSelect'

interface OperatingModelClassifyDialogProps {
  open: boolean
  loading?: boolean
  onClose: () => void
  onSubmit: (model: SchemeOperatingModel) => void
}

/**
 * One-time classification of a legacy Saving Scheme. Needs a chosen model and an explicit confirmation: once saved the
 * model cannot be changed from here.
 */
export function OperatingModelClassifyDialog({
  open,
  loading,
  onClose,
  onSubmit,
}: OperatingModelClassifyDialogProps) {
  const { t } = useTranslation()
  const [model, setModel] = useState<SchemeOperatingModel | ''>('')
  const [confirmed, setConfirmed] = useState(false)
  const [submitted, setSubmitted] = useState(false)

  const close = () => {
    setModel('')
    setConfirmed(false)
    setSubmitted(false)
    onClose()
  }

  const submit = () => {
    setSubmitted(true)
    if (!model || !confirmed) return
    onSubmit(model)
  }

  return (
    <Dialog open={open} onClose={loading ? undefined : close} fullWidth maxWidth="sm">
      <DialogTitle>{t('operatingModel.classify.title')}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <Alert severity="warning">{t('operatingModel.classify.warning')}</Alert>
          <OperatingModelSelect
            value={model}
            onChange={setModel}
            disabled={loading}
            error={submitted && !model ? t('operatingModel.required') : undefined}
          />
          <FormControlLabel
            control={
              <Checkbox
                checked={confirmed}
                onChange={(event) => setConfirmed(event.target.checked)}
                disabled={loading}
              />
            }
            label={t('operatingModel.classify.confirm')}
          />
          {submitted && !confirmed ? (
            <Alert severity="error">{t('operatingModel.classify.confirmRequired')}</Alert>
          ) : null}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={close} disabled={loading}>
          {t('common.cancel')}
        </Button>
        <Button variant="contained" onClick={submit} disabled={loading}>
          {t('operatingModel.classify.submit')}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
