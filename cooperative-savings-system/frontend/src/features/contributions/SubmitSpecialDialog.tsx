import {
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  TextField,
} from '@mui/material'
import { yupResolver } from '@hookform/resolvers/yup'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { todayInKigaliIso } from '@/shared/utils/rwandaCooperative'
import type { SpecialContributionSubmitRequest } from '@/shared/types/specialContribution'
import {
  specialSubmitDefaults,
  specialSubmitSchema,
  toSpecialSubmitPayload,
  type SpecialSubmitFormValues,
} from './contributionFormSchemas'

interface SubmitSpecialDialogProps {
  open: boolean
  loading?: boolean
  suggestedAmount?: string | number | null
  onClose: () => void
  onSubmit: (payload: SpecialContributionSubmitRequest) => void
}

export function SubmitSpecialDialog({
  open,
  loading,
  suggestedAmount,
  onClose,
  onSubmit,
}: SubmitSpecialDialogProps) {
  const { t } = useTranslation()
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<SpecialSubmitFormValues>({
    defaultValues: {
      ...specialSubmitDefaults,
      amount: suggestedAmount != null ? String(suggestedAmount) : '',
    },
    resolver: yupResolver(specialSubmitSchema),
  })

  const handleClose = () => {
    reset({
      ...specialSubmitDefaults,
      amount: suggestedAmount != null ? String(suggestedAmount) : '',
    })
    onClose()
  }

  return (
    <Dialog open={open} onClose={handleClose} fullWidth maxWidth="sm">
      <DialogTitle>{t('contributions.campaigns.submitTitle')}</DialogTitle>
      <form
        noValidate
        onSubmit={handleSubmit((values) => {
          onSubmit(toSpecialSubmitPayload(values))
        })}
      >
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 1 }}>
            <TextField
              label={t('contributions.fields.paid')}
              error={Boolean(errors.amount)}
              helperText={errors.amount?.message}
              {...register('amount')}
              fullWidth
            />
            <TextField
              type="date"
              label={t('contributions.fields.paymentDate')}
              slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: todayInKigaliIso() } }}
              error={Boolean(errors.contributionDate)}
              helperText={errors.contributionDate?.message}
              {...register('contributionDate')}
              fullWidth
            />
            <TextField
              label={t('contributions.fields.reference')}
              error={Boolean(errors.paymentReference)}
              helperText={errors.paymentReference?.message}
              {...register('paymentReference')}
              fullWidth
            />
            <TextField
              label={t('contributions.fields.notes')}
              error={Boolean(errors.notes)}
              helperText={errors.notes?.message}
              {...register('notes')}
              fullWidth
              multiline
              minRows={2}
            />
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={handleClose} disabled={loading}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" variant="contained" disabled={loading}>
            {t('contributions.campaigns.submit')}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}
