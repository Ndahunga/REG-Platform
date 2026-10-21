import {
  Alert,
  Box,
  Button,
  FormControlLabel,
  MenuItem,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material'
import { yupResolver } from '@hookform/resolvers/yup'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useEffect } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Link as RouterLink } from 'react-router-dom'
import { useAppSelector } from '@/app/store/hooks'
import { selectCanManageFineSettings } from '@/app/store/authSlice'
import { getErrorMessage } from '@/shared/api/client'
import { fetchCooperative } from '@/shared/api/cooperatives'
import { fetchFineSettings, updateFineSettings } from '@/shared/api/fineSettings'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { ROUTES } from '@/shared/constants/routes'
import { FINE_CALCULATION_MODES } from '@/shared/types/fine'
import {
  fineSettingsDefaults,
  fineSettingsSchema,
  toFineSettingsPayload,
  type FineSettingsFormValues,
} from './fineFormSchemas'

interface FineSettingsPanelProps {
  cooperativeId: string
}

export function FineSettingsPanel({ cooperativeId }: FineSettingsPanelProps) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const { enqueueSnackbar } = useSnackbar()
  const canEdit = useAppSelector(selectCanManageFineSettings)

  const query = useQuery({
    queryKey: ['fine-settings', cooperativeId],
    queryFn: () => fetchFineSettings(cooperativeId),
    enabled: Boolean(cooperativeId),
  })

  const coopQuery = useQuery({
    queryKey: ['cooperatives', cooperativeId],
    queryFn: () => fetchCooperative(cooperativeId),
    enabled: Boolean(cooperativeId),
  })

  const {
    register,
    handleSubmit,
    reset,
    control,
    formState: { errors, isDirty },
  } = useForm<FineSettingsFormValues>({
    defaultValues: fineSettingsDefaults,
    resolver: yupResolver(fineSettingsSchema),
  })

  useEffect(() => {
    if (!query.data) return
    reset({
      autoFinesEnabled: Boolean(query.data.autoFinesEnabled),
      fineMode: (query.data.fineMode as 'FIXED' | 'PROGRESSIVE') || 'FIXED',
      baseFineAmount: String(query.data.baseFineAmount ?? '0'),
      dailyIncrement: String(query.data.dailyIncrement ?? '0'),
      graceDays: String(query.data.graceDays ?? 0),
    })
  }, [query.data, reset])

  const mutation = useMutation({
    mutationFn: (values: FineSettingsFormValues) =>
      updateFineSettings(cooperativeId, toFineSettingsPayload(values)),
    onSuccess: () => {
      enqueueSnackbar(t('fines.settings.saveSuccess'), { variant: 'success' })
      void queryClient.invalidateQueries({ queryKey: ['fine-settings', cooperativeId] })
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  if (query.isLoading) return <LoadingState variant="skeleton" rows={4} />
  if (query.isError) {
    return (
      <ErrorState
        message={getErrorMessage(query.error)}
        onRetry={() => void query.refetch()}
      />
    )
  }

  const dueDay = coopQuery.data?.contributionDueDay

  return (
    <Box
      component="form"
      onSubmit={handleSubmit((values) => {
        if (!canEdit) return
        mutation.mutate(values)
      })}
      sx={{
        maxWidth: 560,
        p: { xs: 2, sm: 2.5 },
        border: '1px solid',
        borderColor: 'divider',
        borderRadius: 1,
      }}
    >
      <Typography variant="h6" gutterBottom>
        {t('fines.settings.title')}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {t('fines.settings.description')}
      </Typography>
      {!canEdit ? (
        <Alert severity="warning" sx={{ mb: 2 }}>
          {t('fines.settings.presidentOnly')}
        </Alert>
      ) : null}

      <Alert severity="info" sx={{ mb: 2 }}>
        <Typography variant="body2" sx={{ fontWeight: 600 }}>
          {t('fines.settings.contributionDueDay')}:{' '}
          {dueDay != null
            ? t('common.dayOfMonth', { day: dueDay, defaultValue: `Day ${dueDay}` })
            : '—'}
        </Typography>
        <Typography variant="caption" sx={{ display: 'block', mt: 0.5 }}>
          {t('fines.settings.editDueDayHint')}
        </Typography>
        <Button component={RouterLink} to={ROUTES.settings} size="small" sx={{ mt: 1 }}>
          {t('fines.settings.editDueDay')}
        </Button>
      </Alert>

      <Alert severity="info" sx={{ mb: 2 }}>
        {t('fines.settings.progressiveHint')}
      </Alert>

      <Stack spacing={2}>
        <Controller
          name="autoFinesEnabled"
          control={control}
          render={({ field }) => (
            <FormControlLabel
              control={
                <Switch
                  checked={field.value}
                  onChange={(_, checked) => field.onChange(checked)}
                  disabled={!canEdit}
                />
              }
              label={t('fines.settings.autoFinesEnabled')}
            />
          )}
        />
        <TextField
          select
          label={t('fines.settings.fineMode')}
          error={Boolean(errors.fineMode)}
          helperText={errors.fineMode?.message}
          {...register('fineMode')}
          fullWidth
        >
          {FINE_CALCULATION_MODES.map((mode) => (
            <MenuItem key={mode} value={mode}>
              {t(`fines.calculationMode.${mode}`)}
            </MenuItem>
          ))}
        </TextField>
        <TextField
          label={t('fines.settings.baseFineAmount')}
          error={Boolean(errors.baseFineAmount)}
          helperText={errors.baseFineAmount?.message}
          {...register('baseFineAmount')}
          fullWidth
        />
        <TextField
          label={t('fines.settings.dailyIncrement')}
          error={Boolean(errors.dailyIncrement)}
          helperText={errors.dailyIncrement?.message}
          {...register('dailyIncrement')}
          fullWidth
        />
        <TextField
          label={t('fines.settings.graceDays')}
          error={Boolean(errors.graceDays)}
          helperText={errors.graceDays?.message}
          {...register('graceDays')}
          fullWidth
        />
        <Button
          type="submit"
          variant="contained"
          disabled={!canEdit || mutation.isPending || !isDirty}
          sx={{ alignSelf: 'flex-start' }}
        >
          {t('common.save')}
        </Button>
      </Stack>
    </Box>
  )
}
