import FavoriteIcon from '@mui/icons-material/Favorite'
import HourglassEmptyIcon from '@mui/icons-material/HourglassEmpty'
import PaymentsIcon from '@mui/icons-material/Payments'
import VolunteerActivismIcon from '@mui/icons-material/VolunteerActivism'
import {
  Alert,
  Box,
  FormControlLabel,
  Grid,
  Stack,
  Switch,
  TextField,
  Typography,
  Button,
} from '@mui/material'
import { yupResolver } from '@hookform/resolvers/yup'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useEffect } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { getErrorMessage } from '@/shared/api/client'
import {
  fetchSocialFundSettings,
  fetchSocialFundSummary,
  updateSocialFundSettings,
} from '@/shared/api/socialFund'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { MetricCard } from '@/shared/components/MetricCard'
import { formatMoney } from '@/shared/utils/formatMoney'
import { pendingSocialApprovalsTotal } from './socialFundHelpers'
import {
  socialFundSettingsDefaults,
  socialFundSettingsSchema,
  toSocialFundSettingsPayload,
  type SocialFundSettingsFormValues,
} from './socialFundFormSchemas'

interface SocialFundOverviewPanelProps {
  cooperativeId: string
  isAdmin: boolean
}

export function SocialFundOverviewPanel({
  cooperativeId,
  isAdmin,
}: SocialFundOverviewPanelProps) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const { enqueueSnackbar } = useSnackbar()

  const summaryQuery = useQuery({
    queryKey: ['social-fund', 'summary', cooperativeId],
    queryFn: () => fetchSocialFundSummary(cooperativeId),
    enabled: Boolean(cooperativeId),
  })

  const settingsQuery = useQuery({
    queryKey: ['social-fund', 'settings', cooperativeId],
    queryFn: () => fetchSocialFundSettings(cooperativeId),
    enabled: Boolean(cooperativeId) && isAdmin,
    retry: false,
  })

  const {
    register,
    handleSubmit,
    reset,
    control,
    formState: { errors, isDirty },
  } = useForm<SocialFundSettingsFormValues>({
    defaultValues: socialFundSettingsDefaults,
    resolver: yupResolver(socialFundSettingsSchema),
  })

  useEffect(() => {
    if (!settingsQuery.data) return
    reset({
      suggestedContributionAmount:
        settingsQuery.data.suggestedContributionAmount != null
          ? String(settingsQuery.data.suggestedContributionAmount)
          : '',
      enabled: settingsQuery.data.enabled ?? true,
    })
  }, [settingsQuery.data, reset])

  const settingsMutation = useMutation({
    mutationFn: (values: SocialFundSettingsFormValues) =>
      updateSocialFundSettings(cooperativeId, toSocialFundSettingsPayload(values)),
    onSuccess: () => {
      enqueueSnackbar(t('socialFund.settings.saveSuccess'), { variant: 'success' })
      void queryClient.invalidateQueries({
        queryKey: ['social-fund', 'settings', cooperativeId],
      })
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  if (summaryQuery.isLoading) return <LoadingState variant="skeleton" rows={4} />
  if (summaryQuery.isError) {
    return (
      <ErrorState
        message={getErrorMessage(summaryQuery.error)}
        onRetry={() => void summaryQuery.refetch()}
      />
    )
  }

  const summary = summaryQuery.data
  const currency = summary?.currency || 'RWF'
  const pendingTotal = pendingSocialApprovalsTotal(
    summary?.pendingContributions,
    summary?.pendingDisbursements,
  )

  return (
    <Stack spacing={2.5}>
      <Alert severity="info" icon={<FavoriteIcon fontSize="inherit" />}>
        <Typography variant="subtitle2" sx={{ fontWeight: 700, mb: 0.5 }}>
          {t('socialFund.separationTitle')}
        </Typography>
        <Typography variant="body2">{t('socialFund.separationDescription')}</Typography>
      </Alert>

      <Grid container spacing={2}>
        <Grid size={{ xs: 12, sm: 6, md: 3 }}>
          <Box
            sx={{
              height: '100%',
              borderRadius: 1,
              border: '2px solid',
              borderColor: 'secondary.main',
              bgcolor: 'rgba(196, 92, 58, 0.06)',
            }}
          >
            <MetricCard
              label={t('socialFund.metrics.balance')}
              value={
                summary ? formatMoney(summary.balance, { currency }) : '—'
              }
              hint={t('socialFund.metrics.balanceHint')}
              icon={<FavoriteIcon fontSize="small" />}
            />
          </Box>
        </Grid>
        <Grid size={{ xs: 12, sm: 6, md: 3 }}>
          <MetricCard
            label={t('socialFund.metrics.approvedContributions')}
            value={
              summary
                ? formatMoney(summary.totalApprovedContributions, { currency })
                : '—'
            }
            icon={<VolunteerActivismIcon fontSize="small" />}
          />
        </Grid>
        <Grid size={{ xs: 12, sm: 6, md: 3 }}>
          <MetricCard
            label={t('socialFund.metrics.approvedDisbursements')}
            value={
              summary
                ? formatMoney(summary.totalApprovedDisbursements, { currency })
                : '—'
            }
            icon={<PaymentsIcon fontSize="small" />}
          />
        </Grid>
        <Grid size={{ xs: 12, sm: 6, md: 3 }}>
          <MetricCard
            label={t('socialFund.metrics.pendingApprovals')}
            value={String(pendingTotal)}
            hint={t('socialFund.metrics.pendingHint', {
              contributions: summary?.pendingContributions ?? 0,
              disbursements: summary?.pendingDisbursements ?? 0,
            })}
            icon={<HourglassEmptyIcon fontSize="small" />}
          />
        </Grid>
      </Grid>

      {isAdmin ? (
        <Box
          component="form"
          onSubmit={handleSubmit((values) => settingsMutation.mutate(values))}
          sx={{
            maxWidth: 480,
            p: { xs: 2, sm: 2.5 },
            border: '1px solid',
            borderColor: 'divider',
            borderRadius: 1,
          }}
        >
          <Typography variant="h6" gutterBottom>
            {t('socialFund.settings.title')}
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            {t('socialFund.settings.description')}
          </Typography>

          {settingsQuery.isError ? (
            <Alert severity="warning" sx={{ mb: 2 }}>
              {t('socialFund.settings.unavailable')}
            </Alert>
          ) : null}

          <Stack spacing={2}>
            <TextField
              label={t('socialFund.settings.suggestedAmount')}
              error={Boolean(errors.suggestedContributionAmount)}
              helperText={errors.suggestedContributionAmount?.message}
              {...register('suggestedContributionAmount')}
              fullWidth
            />
            <Controller
              name="enabled"
              control={control}
              render={({ field }) => (
                <FormControlLabel
                  control={
                    <Switch
                      checked={field.value}
                      onChange={(_, checked) => field.onChange(checked)}
                    />
                  }
                  label={t('socialFund.settings.enabled')}
                />
              )}
            />
            <Button
              type="submit"
              variant="contained"
              disabled={!isDirty || settingsMutation.isPending || settingsQuery.isError}
            >
              {t('common.save')}
            </Button>
          </Stack>
        </Box>
      ) : null}
    </Stack>
  )
}
