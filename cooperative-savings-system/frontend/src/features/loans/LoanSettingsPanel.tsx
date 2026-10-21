import AddIcon from '@mui/icons-material/Add'
import DeleteIcon from '@mui/icons-material/Delete'
import {
  Alert,
  Box,
  Button,
  FormControl,
  FormControlLabel,
  FormHelperText,
  FormLabel,
  IconButton,
  MenuItem,
  Radio,
  RadioGroup,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material'
import { yupResolver } from '@hookform/resolvers/yup'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useEffect } from 'react'
import { Controller, useFieldArray, useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { useAppSelector } from '@/app/store/hooks'
import { selectCanManageFineSettings } from '@/app/store/authSlice'
import { getErrorMessage } from '@/shared/api/client'
import { fetchLoanSettings, updateLoanSettings } from '@/shared/api/loanSettings'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import {
  INTEREST_TYPES,
  LOAN_PENALTY_FREQUENCIES,
  LOAN_PENALTY_TYPES,
  LOAN_REPAYMENT_COMPONENTS,
  type LoanRepaymentComponent,
} from '@/shared/types/loan'
import {
  loanSettingsDefaults,
  loanSettingsSchema,
  toLoanSettingsPayload,
  type LoanSettingsFormValues,
} from './loanFormSchemas'

interface LoanSettingsPanelProps {
  cooperativeId: string
}

export function LoanSettingsPanel({ cooperativeId }: LoanSettingsPanelProps) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const { enqueueSnackbar } = useSnackbar()
  const canManageShareTiers = useAppSelector(selectCanManageFineSettings)

  const query = useQuery({
    queryKey: ['loan-settings', cooperativeId],
    queryFn: () => fetchLoanSettings(cooperativeId),
    enabled: Boolean(cooperativeId),
  })

  const {
    register,
    handleSubmit,
    reset,
    control,
    watch,
    setValue,
    formState: { errors, isDirty },
  } = useForm<LoanSettingsFormValues>({
    defaultValues: loanSettingsDefaults,
    resolver: yupResolver(loanSettingsSchema),
  })

  const { fields, append, remove } = useFieldArray({
    control,
    name: 'shareTiers',
  })

  useEffect(() => {
    if (!query.data) return
    reset({
      interestRatePercent: String(query.data.interestRatePercent ?? '0'),
      interestType: (query.data.interestType as 'FLAT' | 'REDUCING') || 'FLAT',
      maxLoanAmount:
        query.data.maxLoanAmount != null && query.data.maxLoanAmount !== ''
          ? String(query.data.maxLoanAmount)
          : '',
      maxTermMonths:
        query.data.maxTermMonths != null ? String(query.data.maxTermMonths) : '',
      minMembershipMonths:
        query.data.minMembershipMonths != null
          ? String(query.data.minMembershipMonths)
          : '0',
      allowMemberRequests: Boolean(query.data.allowMemberRequests),
      repaymentDateModel:
        query.data.repaymentDateModel === 'MONTH_END' ? 'MONTH_END' : 'SAME_DAY_OF_MONTH',
      loanPenaltyEnabled: Boolean(query.data.loanPenaltyEnabled ?? query.data.lateFeeEnabled),
      penaltyType:
        (query.data.penaltyType as LoanSettingsFormValues['penaltyType']) || 'FIXED_AMOUNT',
      penaltyRateOrAmount:
        query.data.penaltyRateOrAmount != null ? String(query.data.penaltyRateOrAmount) : '0',
      penaltyFrequency:
        (query.data.penaltyFrequency as LoanSettingsFormValues['penaltyFrequency']) || 'ONE_TIME',
      gracePeriodDays:
        query.data.gracePeriodDays != null ? String(query.data.gracePeriodDays) : '0',
      allocationOrder:
        query.data.allocationOrder && query.data.allocationOrder.length === 3
          ? (query.data.allocationOrder as LoanSettingsFormValues['allocationOrder'])
          : ['PENALTY', 'INTEREST', 'PRINCIPAL'],
      shareTiers: (query.data.shareTiers ?? []).map((tier) => ({
        minSharePercent: String(tier.minSharePercent ?? ''),
        maxLoanAmount: String(tier.maxLoanAmount ?? ''),
      })),
    })
  }, [query.data, reset])

  const mutation = useMutation({
    mutationFn: (values: LoanSettingsFormValues) =>
      updateLoanSettings(cooperativeId, toLoanSettingsPayload(values, canManageShareTiers)),
    onSuccess: () => {
      enqueueSnackbar(t('loans.settings.saveSuccess'), { variant: 'success' })
      void queryClient.invalidateQueries({ queryKey: ['loan-settings', cooperativeId] })
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

  return (
    <Box
      component="form"
      onSubmit={handleSubmit((values) => mutation.mutate(values))}
      sx={{
        maxWidth: 640,
        p: { xs: 2, sm: 2.5 },
        border: '1px solid',
        borderColor: 'divider',
        borderRadius: 1,
      }}
    >
      <Typography variant="h6" gutterBottom>
        {t('loans.settings.title')}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {t('loans.settings.description')}
      </Typography>

      <Alert severity="info" sx={{ mb: 2 }}>
        {t('loans.settings.snapshotHint')}
      </Alert>

      <Stack spacing={2}>
        <TextField
          label={t('loans.settings.interestRatePercent')}
          error={Boolean(errors.interestRatePercent)}
          helperText={errors.interestRatePercent?.message}
          {...register('interestRatePercent')}
          fullWidth
        />
        <TextField
          select
          label={t('loans.settings.interestType')}
          error={Boolean(errors.interestType)}
          helperText={
            errors.interestType?.message ||
            t('loans.settings.reducingUnavailable')
          }
          {...register('interestType')}
          fullWidth
        >
          {INTEREST_TYPES.map((type) => (
            <MenuItem key={type} value={type}>
              {t(`loans.interestType.${type}`)}
            </MenuItem>
          ))}
        </TextField>
        <TextField
          label={t('loans.settings.maxLoanAmount')}
          error={Boolean(errors.maxLoanAmount)}
          helperText={errors.maxLoanAmount?.message}
          {...register('maxLoanAmount')}
          fullWidth
        />
        <TextField
          label={t('loans.settings.maxTermMonths')}
          error={Boolean(errors.maxTermMonths)}
          helperText={errors.maxTermMonths?.message}
          {...register('maxTermMonths')}
          fullWidth
        />
        <TextField
          label={t('loans.settings.minMembershipMonths')}
          error={Boolean(errors.minMembershipMonths)}
          helperText={errors.minMembershipMonths?.message}
          {...register('minMembershipMonths')}
          fullWidth
        />
        <Controller
          name="allowMemberRequests"
          control={control}
          render={({ field }) => (
            <FormControlLabel
              control={
                <Switch
                  checked={field.value}
                  onChange={(_, checked) => field.onChange(checked)}
                />
              }
              label={t('loans.settings.allowMemberRequests')}
            />
          )}
        />
        <Box>
          <Typography variant="subtitle1" sx={{ mb: 0.5 }}>
            {t('loans.settings.repaymentSection')}
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
            {t('loans.settings.repaymentSectionHint')}
          </Typography>
          <Controller
            name="repaymentDateModel"
            control={control}
            render={({ field }) => (
              <FormControl>
                <FormLabel>{t('loans.settings.repaymentDateModel')}</FormLabel>
                <RadioGroup
                  value={field.value}
                  onChange={(event) => field.onChange(event.target.value)}
                >
                  <FormControlLabel
                    value="SAME_DAY_OF_MONTH"
                    control={<Radio />}
                    label={t('loans.settings.repaymentDateModelSameDay')}
                  />
                  <FormControlLabel
                    value="MONTH_END"
                    control={<Radio />}
                    label={t('loans.settings.repaymentDateModelMonthEnd')}
                  />
                </RadioGroup>
                <FormHelperText>
                  {field.value === 'MONTH_END'
                    ? t('loans.settings.repaymentDateModelMonthEndHelp')
                    : t('loans.settings.repaymentDateModelSameDayHelp')}
                </FormHelperText>
              </FormControl>
            )}
          />
        </Box>
        <Controller
          name="loanPenaltyEnabled"
          control={control}
          render={({ field }) => (
            <FormControlLabel
              control={
                <Switch
                  checked={field.value}
                  onChange={(_, checked) => field.onChange(checked)}
                />
              }
              label={t('loans.settings.penaltyEnabled')}
            />
          )}
        />
        {watch('loanPenaltyEnabled') ? (
          <Stack spacing={2}>
            <TextField
              select
              label={t('loans.settings.penaltyType')}
              error={Boolean(errors.penaltyType)}
              helperText={errors.penaltyType?.message}
              {...register('penaltyType')}
              fullWidth
            >
              {LOAN_PENALTY_TYPES.map((type) => (
                <MenuItem key={type} value={type}>
                  {t(
                    type === 'FIXED_AMOUNT'
                      ? 'loans.settings.penaltyTypeFixed'
                      : type === 'PERCENTAGE_OF_OVERDUE_INSTALLMENT'
                        ? 'loans.settings.penaltyTypeOverdueInstallment'
                        : 'loans.settings.penaltyTypeOutstandingBalance',
                  )}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              label={t('loans.settings.penaltyRateOrAmount')}
              error={Boolean(errors.penaltyRateOrAmount)}
              helperText={errors.penaltyRateOrAmount?.message}
              {...register('penaltyRateOrAmount')}
              fullWidth
            />
            <TextField
              select
              label={t('loans.settings.penaltyFrequency')}
              error={Boolean(errors.penaltyFrequency)}
              helperText={errors.penaltyFrequency?.message}
              {...register('penaltyFrequency')}
              fullWidth
            >
              {LOAN_PENALTY_FREQUENCIES.map((frequency) => (
                <MenuItem key={frequency} value={frequency}>
                  {t(
                    frequency === 'ONE_TIME'
                      ? 'loans.settings.penaltyFrequencyOnce'
                      : frequency === 'DAILY'
                        ? 'loans.settings.penaltyFrequencyDaily'
                        : 'loans.settings.penaltyFrequencyMonthly',
                  )}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              label={t('loans.settings.gracePeriodDays')}
              error={Boolean(errors.gracePeriodDays)}
              helperText={errors.gracePeriodDays?.message}
              {...register('gracePeriodDays')}
              fullWidth
            />
          </Stack>
        ) : null}
        <Box>
          <Typography variant="subtitle2" sx={{ mb: 0.5 }}>
            {t('loans.settings.allocationOrder')}
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
            {t('loans.settings.allocationOrderHelp')}
          </Typography>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
            {([0, 1, 2] as const).map((index) => (
              <TextField
                key={index}
                select
                label={t('loans.settings.allocationPosition', { position: index + 1 })}
                value={watch('allocationOrder')[index]}
                onChange={(event) => {
                  const next = [...watch('allocationOrder')] as LoanRepaymentComponent[]
                  next[index] = event.target.value as LoanRepaymentComponent
                  setValue('allocationOrder', next, { shouldDirty: true, shouldValidate: true })
                }}
                error={Boolean(errors.allocationOrder)}
                helperText={index === 2 ? errors.allocationOrder?.message : undefined}
                fullWidth
              >
                {LOAN_REPAYMENT_COMPONENTS.map((component) => (
                  <MenuItem key={component} value={component}>
                    {t(`loans.schedule.allocation.${component}`)}
                  </MenuItem>
                ))}
              </TextField>
            ))}
          </Stack>
        </Box>

        <Box>
          <Typography variant="subtitle1" sx={{ mb: 0.5 }}>
            {t('loans.settings.shareTiersTitle')}
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
            {t('loans.settings.shareTiersDescription')}
          </Typography>
          {!canManageShareTiers ? (
            <Alert severity="info" sx={{ mb: 1.5 }}>
              {t('loans.settings.shareTiersPresidentOnly')}
            </Alert>
          ) : null}
          <Stack spacing={1.5}>
            {fields.map((field, index) => (
              <Stack
                key={field.id}
                direction={{ xs: 'column', sm: 'row' }}
                spacing={1}
                sx={{ alignItems: 'flex-start' }}
              >
                <TextField
                  label={t('loans.settings.minSharePercent')}
                  error={Boolean(errors.shareTiers?.[index]?.minSharePercent)}
                  helperText={errors.shareTiers?.[index]?.minSharePercent?.message}
                  {...register(`shareTiers.${index}.minSharePercent`)}
                  fullWidth
                  disabled={!canManageShareTiers}
                />
                <TextField
                  label={t('loans.settings.tierMaxLoanAmount')}
                  error={Boolean(errors.shareTiers?.[index]?.maxLoanAmount)}
                  helperText={errors.shareTiers?.[index]?.maxLoanAmount?.message}
                  {...register(`shareTiers.${index}.maxLoanAmount`)}
                  fullWidth
                  disabled={!canManageShareTiers}
                />
                {canManageShareTiers ? (
                  <IconButton
                    aria-label={t('loans.settings.removeShareTier')}
                    onClick={() => remove(index)}
                    sx={{ mt: { sm: 1 } }}
                  >
                    <DeleteIcon />
                  </IconButton>
                ) : null}
              </Stack>
            ))}
          </Stack>
          {canManageShareTiers ? (
            <Button
              type="button"
              startIcon={<AddIcon />}
              onClick={() => append({ minSharePercent: '', maxLoanAmount: '' })}
              sx={{ mt: 1 }}
            >
              {t('loans.settings.addShareTier')}
            </Button>
          ) : null}
        </Box>

        <Button
          type="submit"
          variant="contained"
          disabled={mutation.isPending || !isDirty}
          sx={{ alignSelf: 'flex-start' }}
        >
          {t('common.save')}
        </Button>
      </Stack>
    </Box>
  )
}
