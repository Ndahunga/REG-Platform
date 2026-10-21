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
import {
  campaignFormDefaults,
  campaignFormSchema,
  toCampaignCreatePayload,
  type CampaignFormValues,
} from './contributionFormSchemas'
import type { SpecialCampaignCreateRequest } from '@/shared/types/specialContribution'

interface CampaignFormDialogProps {
  open: boolean
  loading?: boolean
  onClose: () => void
  onSubmit: (payload: SpecialCampaignCreateRequest) => void
}

export function CampaignFormDialog({
  open,
  loading,
  onClose,
  onSubmit,
}: CampaignFormDialogProps) {
  const { t } = useTranslation()
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<CampaignFormValues>({
    defaultValues: campaignFormDefaults,
    resolver: yupResolver(campaignFormSchema),
  })

  const handleClose = () => {
    reset(campaignFormDefaults)
    onClose()
  }

  return (
    <Dialog open={open} onClose={handleClose} fullWidth maxWidth="sm">
      <DialogTitle>{t('contributions.campaigns.createTitle')}</DialogTitle>
      <form
        onSubmit={handleSubmit((values) => {
          onSubmit(toCampaignCreatePayload(values))
        })}
      >
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 1 }}>
            <TextField
              label={t('contributions.campaigns.fields.name')}
              error={Boolean(errors.name)}
              helperText={errors.name?.message}
              {...register('name')}
              fullWidth
            />
            <TextField
              label={t('contributions.campaigns.fields.purpose')}
              {...register('purpose')}
              fullWidth
            />
            <TextField
              label={t('contributions.campaigns.fields.description')}
              {...register('description')}
              fullWidth
              multiline
              minRows={2}
            />
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <TextField
                label={t('contributions.campaigns.fields.suggestedAmount')}
                error={Boolean(errors.suggestedAmount)}
                helperText={errors.suggestedAmount?.message}
                {...register('suggestedAmount')}
                fullWidth
              />
              <TextField
                label={t('contributions.campaigns.fields.targetAmount')}
                error={Boolean(errors.targetAmount)}
                helperText={errors.targetAmount?.message}
                {...register('targetAmount')}
                fullWidth
              />
            </Stack>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <TextField
                type="date"
                label={t('contributions.campaigns.fields.startDate')}
                slotProps={{ inputLabel: { shrink: true } }}
                error={Boolean(errors.startDate)}
                helperText={errors.startDate?.message}
                {...register('startDate')}
                fullWidth
              />
              <TextField
                type="date"
                label={t('contributions.campaigns.fields.endDate')}
                slotProps={{ inputLabel: { shrink: true } }}
                error={Boolean(errors.endDate)}
                helperText={errors.endDate?.message}
                {...register('endDate')}
                fullWidth
              />
            </Stack>
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={handleClose} disabled={loading}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" variant="contained" disabled={loading}>
            {t('common.save')}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}
