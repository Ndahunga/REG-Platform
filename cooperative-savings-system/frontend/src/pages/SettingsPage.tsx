import {
  Box,
  FormControlLabel,
  MenuItem,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useEffect } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { selectIsLeadership, selectIsSuperAdmin } from '@/app/store/authSlice'
import { useAppSelector } from '@/app/store/hooks'
import { ContributionDueDayCard } from '@/features/contributions'
import { OperatingModelCard } from '@/features/cooperatives/OperatingModelCard'
import {
  cooperativeSettingsDefaults,
  toSettingsFormValues,
  toSettingsPayload,
  validateBaseSharePrice,
  type CooperativeSettingsFormValues,
} from '@/features/settings'
import { getErrorMessage } from '@/shared/api/client'
import {
  fetchCooperativeSettings,
  updateCooperativeSettings,
} from '@/shared/api/cooperativeSettings'
import { EmptyState } from '@/shared/components/EmptyState'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { PageHeader } from '@/shared/components/PageHeader'
import { SubscriptionAwareButton } from '@/shared/components/SubscriptionAwareButton'
import { hasPermission } from '@/shared/types/auth'
import {
  COMMON_TIMEZONES,
  SUPPORTED_LOCALES,
} from '@/shared/types/cooperativeSettings'

export function SettingsPage() {
  const { t } = useTranslation()
  const cooperativeId = useAppSelector((s) => s.auth.selectedCooperativeId)
  const queryClient = useQueryClient()
  const user = useAppSelector((s) => s.auth.user)
  const isLeadership = useAppSelector(selectIsLeadership)
  const isSuperAdmin = useAppSelector(selectIsSuperAdmin)
  const canManageDueDay = (isSuperAdmin || isLeadership) && hasPermission(user, 'COOPERATIVE_WRITE')
  const { enqueueSnackbar } = useSnackbar()

  const query = useQuery({
    queryKey: ['cooperative-settings', cooperativeId],
    queryFn: () => fetchCooperativeSettings(cooperativeId!),
    enabled: Boolean(cooperativeId),
  })

  const {
    register,
    handleSubmit,
    reset,
    control,
    formState: { isDirty, errors },
  } = useForm<CooperativeSettingsFormValues>({
    defaultValues: cooperativeSettingsDefaults,
  })

  useEffect(() => {
    if (!query.data) return
    reset(toSettingsFormValues(query.data))
  }, [query.data, reset])

  const mutation = useMutation({
    mutationFn: (values: CooperativeSettingsFormValues) =>
      updateCooperativeSettings(cooperativeId!, toSettingsPayload(values)),
    onSuccess: (data) => {
      enqueueSnackbar(t('settings.saveSuccess'), { variant: 'success' })
      reset(toSettingsFormValues(data))
      void queryClient.invalidateQueries({
        queryKey: ['cooperative-settings', cooperativeId],
      })
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  if (!cooperativeId) {
    return (
      <Box>
        <PageHeader
          title={t('pages.settings.title')}
          description={t('pages.settings.description')}
        />
        <EmptyState
          title={t('settings.selectCooperativeTitle')}
          description={t('settings.selectCooperativeDescription')}
        />
      </Box>
    )
  }

  return (
    <Box>
      <PageHeader
        title={t('pages.settings.title')}
        description={t('pages.settings.description')}
      />

      {query.isLoading ? <LoadingState variant="skeleton" rows={5} /> : null}

      {query.isError ? (
        <ErrorState
          message={getErrorMessage(query.error)}
          onRetry={() => void query.refetch()}
        />
      ) : null}

      {!query.isLoading && !query.isError ? (
        <Box
          component="form"
          onSubmit={handleSubmit((values) => mutation.mutate(values))}
          sx={{
            maxWidth: 560,
            p: { xs: 2, sm: 2.5 },
            border: '1px solid',
            borderColor: 'divider',
            borderRadius: 1,
            bgcolor: 'background.paper',
          }}
        >
          <Typography variant="h6" gutterBottom>
            {t('settings.formTitle')}
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            {t('settings.formDescription')}
          </Typography>

          <Stack spacing={2}>
            <TextField
              select
              label={t('settings.fields.timezone')}
              {...register('timezone')}
              fullWidth
            >
              {COMMON_TIMEZONES.map((tz) => (
                <MenuItem key={tz} value={tz}>
                  {tz}
                </MenuItem>
              ))}
            </TextField>

            <TextField
              select
              label={t('settings.fields.locale')}
              {...register('locale')}
              fullWidth
            >
              {SUPPORTED_LOCALES.map((locale) => (
                <MenuItem key={locale} value={locale}>
                  {t(`settings.locales.${locale}`, { defaultValue: locale })}
                </MenuItem>
              ))}
            </TextField>

            <TextField
              label={t('settings.fields.baseSharePrice')}
              helperText={
                errors.baseSharePrice?.message || t('settings.fields.baseSharePriceHint')
              }
              type="number"
              error={Boolean(errors.baseSharePrice)}
              slotProps={{ htmlInput: { min: 0, step: 'any' } }}
              {...register('baseSharePrice', { validate: validateBaseSharePrice })}
              fullWidth
            />

            <Typography variant="subtitle2" sx={{ pt: 1 }}>
              {t('settings.notifySection')}
            </Typography>

            <Controller
              name="notifyContributions"
              control={control}
              render={({ field }) => (
                <FormControlLabel
                  control={
                    <Switch
                      checked={field.value}
                      onChange={(_, checked) => field.onChange(checked)}
                    />
                  }
                  label={t('settings.fields.notifyContributions')}
                />
              )}
            />
            <Controller
              name="notifyLoans"
              control={control}
              render={({ field }) => (
                <FormControlLabel
                  control={
                    <Switch
                      checked={field.value}
                      onChange={(_, checked) => field.onChange(checked)}
                    />
                  }
                  label={t('settings.fields.notifyLoans')}
                />
              )}
            />
            <Controller
              name="notifyFines"
              control={control}
              render={({ field }) => (
                <FormControlLabel
                  control={
                    <Switch
                      checked={field.value}
                      onChange={(_, checked) => field.onChange(checked)}
                    />
                  }
                  label={t('settings.fields.notifyFines')}
                />
              )}
            />
            <Controller
              name="notifyPayouts"
              control={control}
              render={({ field }) => (
                <FormControlLabel
                  control={
                    <Switch
                      checked={field.value}
                      onChange={(_, checked) => field.onChange(checked)}
                    />
                  }
                  label={t('settings.fields.notifyPayouts')}
                />
              )}
            />

            <SubscriptionAwareButton
              type="submit"
              variant="contained"
              disabled={!isDirty || mutation.isPending}
              sx={{ minHeight: 44, alignSelf: 'flex-start' }}
            >
              {t('common.save')}
            </SubscriptionAwareButton>
          </Stack>
        </Box>
      ) : null}
      {cooperativeId ? <OperatingModelCard cooperativeId={cooperativeId} canManage={canManageDueDay} /> : null}
      {cooperativeId ? <ContributionDueDayCard cooperativeId={cooperativeId} canManage={canManageDueDay} /> : null}
    </Box>
  )
}
