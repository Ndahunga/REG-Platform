import {
  Alert,
  Box,
  Button,
  FormControl,
  FormControlLabel,
  FormLabel,
  MenuItem,
  Radio,
  RadioGroup,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { yupResolver } from '@hookform/resolvers/yup'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useMemo } from 'react'
import { useForm } from 'react-hook-form'
import { useAppSelector } from '@/app/store/hooks'
import { selectAuthUser } from '@/app/store/authSlice'
import { LoanApplicationFormView } from './LoanApplicationFormView'
import { LoanSchedulePreview } from './LoanSchedulePreview'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { getErrorMessage } from '@/shared/api/client'
import { fetchLoanSettings } from '@/shared/api/loanSettings'
import { fetchLoanEligibility, createLoan, fetchLoanApplicationPreview, previewLoanRepayment } from '@/shared/api/loans'
import { fetchMembers } from '@/shared/api/members'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { ROUTES } from '@/shared/constants/routes'
import { memberDisplayName } from '@/shared/types/member'
import { formatMoney } from '@/shared/utils/formatMoney'
import { checkMoney } from '@/shared/utils/formValidation'
import {
  loanRequestDefaults,
  loanRequestSchema,
  toLoanCreatePayload,
  type LoanRequestFormValues,
} from './loanFormSchemas'

interface LoanRequestPanelProps {
  cooperativeId: string
  isAdmin: boolean
}

export function LoanRequestPanel({ cooperativeId, isAdmin }: LoanRequestPanelProps) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { enqueueSnackbar } = useSnackbar()
  const currentUser = useAppSelector(selectAuthUser)

  const settingsQuery = useQuery({
    queryKey: ['loan-settings', cooperativeId],
    queryFn: () => fetchLoanSettings(cooperativeId),
    enabled: Boolean(cooperativeId),
  })

  const membersQuery = useQuery({
    queryKey: ['members', cooperativeId, 'loan-select'],
    queryFn: () => fetchMembers(cooperativeId, { status: 'ACTIVE', size: 200 }),
    enabled: Boolean(cooperativeId),
  })

  const previewQuery = useQuery({
    queryKey: ['loans', 'application-preview', cooperativeId],
    queryFn: () => fetchLoanApplicationPreview(cooperativeId),
    enabled: Boolean(cooperativeId) && !isAdmin,
  })

  const schema = useMemo(() => loanRequestSchema(isAdmin), [isAdmin])

  const {
    register,
    handleSubmit,
    reset,
    watch,
    setValue,
    formState: { errors },
  } = useForm<LoanRequestFormValues>({
    defaultValues: loanRequestDefaults,
    resolver: yupResolver(schema),
  })

  const mutation = useMutation({
    mutationFn: (values: LoanRequestFormValues) =>
      createLoan(cooperativeId, toLoanCreatePayload(values, isAdmin)),
    onSuccess: (loan) => {
      enqueueSnackbar(
        isAdmin ? t('loans.request.issueSuccess') : t('loans.request.requestSuccess'),
        { variant: 'success' },
      )
      reset(loanRequestDefaults)
      void queryClient.invalidateQueries({ queryKey: ['loans'] })
      navigate(ROUTES.loanDetail(loan.id))
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  // Every hook (including the dependent queries below) must run on every render, in the same order.
  // The loading/error/blocked early returns come only after the last hook; queries that must not run
  // yet are held back with `enabled`, never by skipping the hook.
  const settings = settingsQuery.data
  const memberRequestsBlocked = !isAdmin && settings && !settings.allowMemberRequests
  const amount = watch('amount')
  const purpose = watch('purpose')
  const termMonths = watch('termMonths')
  const selectedMemberId = watch('memberUserId')
  const guaranteeMode = watch('guaranteeMode')
  const eligibilityMemberId = isAdmin ? selectedMemberId : currentUser?.id
  const parsedTerm = termMonths.trim() ? Number(termMonths.trim()) : undefined
  // Text that is not a valid amount ("abc", "1e3", "-5") must not reach the preview/eligibility queries or
  // the money formatter; it is reported by the form validation instead.
  const validAmount = checkMoney(amount, { min: '0.01' }) === null ? amount.trim() : ''
  const canPreview = Boolean(cooperativeId && validAmount)
  // Same condition under which the form (rather than the loading/error state) is shown.
  const formReady = settingsQuery.isSuccess && (isAdmin || !previewQuery.isLoading)

  const scheduleQuery = useQuery({
    queryKey: ['loans', 'repayment-preview', cooperativeId, validAmount, parsedTerm],
    queryFn: () =>
      previewLoanRepayment(cooperativeId, {
        amount: validAmount,
        termMonths: parsedTerm,
      }),
    enabled: formReady && canPreview,
  })

  const eligibilityQuery = useQuery({
    queryKey: ['loans', 'eligibility', cooperativeId, eligibilityMemberId, validAmount],
    queryFn: () => fetchLoanEligibility(cooperativeId, eligibilityMemberId, validAmount),
    enabled: formReady && Boolean(cooperativeId && eligibilityMemberId),
  })

  if (settingsQuery.isLoading || (!isAdmin && previewQuery.isLoading)) {
    return <LoadingState variant="skeleton" rows={3} />
  }
  if (settingsQuery.isError) {
    return (
      <ErrorState
        message={getErrorMessage(settingsQuery.error)}
        onRetry={() => void settingsQuery.refetch()}
      />
    )
  }

  if (memberRequestsBlocked) {
    return (
      <Alert severity="warning">{t('loans.request.memberRequestsDisabled')}</Alert>
    )
  }

  return (
    <Box
      component="form"
      onSubmit={handleSubmit((values) => mutation.mutate(values))}
      sx={{
        maxWidth: 560,
        p: { xs: 2, sm: 2.5 },
        border: '1px solid',
        borderColor: 'divider',
        borderRadius: 1,
      }}
    >
      <Typography variant="h6" gutterBottom>
        {isAdmin ? t('loans.request.issueTitle') : t('loans.request.applyTitle')}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {isAdmin
          ? t('loans.request.issueDescription')
          : t('loans.request.applyDescription')}
      </Typography>

      {!isAdmin && previewQuery.data ? (
        <Box sx={{ mb: 2 }}>
          <LoanApplicationFormView
            form={previewQuery.data}
            amount={validAmount}
            purpose={purpose}
            termMonths={termMonths}
          />
        </Box>
      ) : null}

      {settings ? (
        <Alert severity="info" sx={{ mb: 2 }}>
          {t('loans.request.settingsHint', {
            rate: settings.interestRatePercent,
            type: t(`loans.interestType.${settings.interestType}`, {
              defaultValue: String(settings.interestType),
            }),
            maxAmount:
              settings.maxLoanAmount != null && settings.maxLoanAmount !== ''
                ? String(settings.maxLoanAmount)
                : '—',
            maxTerm: settings.maxTermMonths ?? '—',
          })}
        </Alert>
      ) : null}

      {eligibilityQuery.data ? (
        <Alert
          severity={eligibilityQuery.data.eligible ? 'success' : 'warning'}
          sx={{ mb: 2 }}
        >
          <Stack spacing={0.5}>
            <Typography variant="body2">{eligibilityQuery.data.reason}</Typography>
            <Typography variant="body2">
              {t('loans.eligibility.existingAmount')}:{' '}
              {formatMoney(eligibilityQuery.data.existingLoanAmount ?? 0)}
            </Typography>
            <Typography variant="body2">
              {t('loans.eligibility.repaid')}:{' '}
              {formatMoney(eligibilityQuery.data.amountAlreadyRepaid ?? 0)}
            </Typography>
            <Typography variant="body2">
              {t('loans.eligibility.outstanding')}:{' '}
              {formatMoney(eligibilityQuery.data.outstandingBalance ?? 0)}
            </Typography>
            <Typography variant="body2">
              {t('loans.eligibility.requested')}: {formatMoney(validAmount || 0)}
            </Typography>
            {eligibilityQuery.data.shareCount != null ? (
              <Typography variant="body2">
                {t('loans.eligibility.shares')}: {eligibilityQuery.data.shareCount}
                {eligibilityQuery.data.sharePercent != null
                  ? ` (${eligibilityQuery.data.sharePercent}%)`
                  : ''}
              </Typography>
            ) : null}
            {eligibilityQuery.data.maxLoanByShares != null ? (
              <Typography variant="body2">
                {t('loans.eligibility.maxByShares')}:{' '}
                {formatMoney(eligibilityQuery.data.maxLoanByShares)}
              </Typography>
            ) : null}
            {eligibilityQuery.data.maxEligibleAmount != null ? (
              <Typography variant="body2">
                {t('loans.eligibility.maxEligible')}:{' '}
                {formatMoney(eligibilityQuery.data.maxEligibleAmount)}
              </Typography>
            ) : null}
          </Stack>
        </Alert>
      ) : null}

      <Stack spacing={2}>
        {isAdmin ? (
          <TextField
            select
            label={t('loans.fields.member')}
            error={Boolean(errors.memberUserId)}
            helperText={errors.memberUserId?.message}
            {...register('memberUserId')}
            fullWidth
            disabled={membersQuery.isLoading}
          >
            {(membersQuery.data?.content ?? []).map((member) => (
              <MenuItem key={member.userId} value={member.userId}>
                {memberDisplayName(member)}
              </MenuItem>
            ))}
          </TextField>
        ) : null}
        <TextField
          label={t('loans.fields.amount')}
          error={Boolean(errors.amount)}
          helperText={errors.amount?.message}
          {...register('amount')}
          fullWidth
        />
        <TextField
          label={t('loans.fields.termMonths')}
          error={Boolean(errors.termMonths)}
          helperText={errors.termMonths?.message || t('loans.request.termHint')}
          {...register('termMonths')}
          fullWidth
        />
        {scheduleQuery.data ? (
          <Box
            sx={{
              p: 2,
              border: '1px solid',
              borderColor: 'divider',
              borderRadius: 1,
            }}
          >
            <Typography variant="subtitle2" gutterBottom>
              {t('loans.schedule.previewTitle')}
            </Typography>
            <LoanSchedulePreview schedule={scheduleQuery.data} compact />
          </Box>
        ) : null}
        <TextField
          label={t('loans.fields.purpose')}
          error={Boolean(errors.purpose)}
          helperText={errors.purpose?.message}
          {...register('purpose')}
          fullWidth
          required={!isAdmin}
        />
        <FormControl>
          <FormLabel>{t('loans.request.guaranteeMode')}</FormLabel>
          <RadioGroup
            row
            value={guaranteeMode}
            onChange={(event) =>
              setValue('guaranteeMode', event.target.value as 'SELF' | 'GUARANTOR', {
                shouldValidate: true,
              })
            }
          >
            <FormControlLabel
              value="SELF"
              control={<Radio />}
              label={t('loans.request.guaranteeModeSelf')}
            />
            <FormControlLabel
              value="GUARANTOR"
              control={<Radio />}
              label={t('loans.request.guaranteeModeGuarantor')}
            />
          </RadioGroup>
        </FormControl>
        {guaranteeMode === 'GUARANTOR' ? (
          <>
        <TextField
          select
          label={t('loans.guarantor.guarantor')}
          error={Boolean(errors.guarantorUserId)}
          helperText={errors.guarantorUserId?.message}
          {...register('guarantorUserId')}
          fullWidth
          required
          disabled={membersQuery.isLoading}
        >
          {(membersQuery.data?.content ?? [])
            .filter((member) => member.userId !== currentUser?.id)
            .filter((member) => !isAdmin || member.userId !== selectedMemberId)
            .map((member) => (
              <MenuItem key={member.userId} value={member.userId}>
                {memberDisplayName(member)}
              </MenuItem>
            ))}
        </TextField>
        <TextField
          label={t('loans.guarantor.guaranteedAmount')}
          error={Boolean(errors.guaranteedAmount)}
          helperText={errors.guaranteedAmount?.message}
          {...register('guaranteedAmount')}
          fullWidth
          required
        />
          </>
        ) : null}
        {isAdmin ? (
          <TextField
            label={t('loans.fields.notes')}
            {...register('notes')}
            fullWidth
            multiline
            minRows={2}
          />
        ) : null}
        <Button
          type="submit"
          variant="contained"
          disabled={mutation.isPending || eligibilityQuery.data?.eligible === false}
          sx={{ alignSelf: 'flex-start' }}
        >
          {isAdmin ? t('loans.request.issue') : t('loans.request.apply')}
        </Button>
      </Stack>
    </Box>
  )
}
