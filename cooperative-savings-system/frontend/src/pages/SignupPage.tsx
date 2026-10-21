import {
  Alert,
  Box,
  Button,
  CircularProgress,
  Link as MuiLink,
  MenuItem,
  Stack,
  Step,
  StepLabel,
  Stepper,
  TextField,
  Typography,
} from '@mui/material'
import { useMutation } from '@tanstack/react-query'
import { useState, type FormEvent, type ReactNode } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Link as RouterLink, useNavigate } from 'react-router-dom'
import { yupResolver } from '@hookform/resolvers/yup'
import { useAppDispatch } from '@/app/store/hooks'
import { setCredentials } from '@/app/store/authSlice'
import { LOGIN_SUCCESS_STATE } from '@/features/branding/loginSuccessSplash'
import { TrialPricingNote } from '@/features/onboarding/TrialPricingNote'
import {
  MAX_CONTRIBUTION_DUE_DAY,
  MIN_CONTRIBUTION_DUE_DAY,
  MIN_REGISTRATION_DATE,
  ONBOARDING_STEP1_FIELDS,
  ONBOARDING_STEP2_FIELDS,
  RWANDA_CURRENCY,
  onboardingDefaults,
  onboardingSchema,
  todayInKigaliIso,
  toOnboardingPayload,
  type OnboardingFormValues,
} from '@/features/onboarding/onboardingSchema'
import { OperatingModelSelect } from '@/features/cooperatives/OperatingModelSelect'
import { onboardCooperative } from '@/shared/api/onboarding'
import { getErrorMessage } from '@/shared/api/client'
import { AuthSplitShell } from '@/layouts/AuthSplitShell'
import { AUTH_ACTION_BUTTON_SX } from '@/layouts/authFormStyles'
import { plainLinkSx } from '@/shared/styles/plainLink'
import { ROUTES } from '@/shared/constants/routes'
import { formatMoney } from '@/shared/utils/formatMoney'

const MONTHS = Array.from({ length: 12 }, (_, i) => i + 1)
const DUE_DAYS = Array.from(
  { length: MAX_CONTRIBUTION_DUE_DAY - MIN_CONTRIBUTION_DUE_DAY + 1 },
  (_, i) => MIN_CONTRIBUTION_DUE_DAY + i,
)

const STEPS = ['signup.steps.scheme', 'signup.steps.account', 'signup.steps.review'] as const

/** Medium wizard buttons sized to their content on every screen size (Back left, Next/Create right). */
const WIZARD_BUTTON_SX = AUTH_ACTION_BUTTON_SX

export function SignupPage() {
  const { t } = useTranslation()
  const dispatch = useAppDispatch()
  const navigate = useNavigate()
  const [step, setStep] = useState(0)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const todayIso = todayInKigaliIso()

  const {
    register,
    control,
    handleSubmit,
    trigger,
    watch,
    formState: { errors },
  } = useForm<OnboardingFormValues>({
    resolver: yupResolver(onboardingSchema),
    defaultValues: onboardingDefaults,
    mode: 'onSubmit',
  })

  const values = watch()

  const mutation = useMutation({
    mutationFn: (formValues: OnboardingFormValues) => onboardCooperative(toOnboardingPayload(formValues)),
    onSuccess: (data) => {
      dispatch(setCredentials({ user: data.user, accessToken: data.accessToken }))
      navigate(ROUTES.loginSuccess, { replace: true, state: LOGIN_SUCCESS_STATE })
    },
    onError: (error) => {
      setErrorMessage(getErrorMessage(error, t('errors.signupFailed')))
    },
  })

  const goNext = async () => {
    const fields = step === 0 ? ONBOARDING_STEP1_FIELDS : ONBOARDING_STEP2_FIELDS
    const valid = await trigger(fields)
    if (valid) {
      setErrorMessage(null)
      setStep((current) => Math.min(current + 1, STEPS.length - 1))
    }
  }

  const goBack = () => {
    setErrorMessage(null)
    setStep((current) => Math.max(current - 1, 0))
  }

  const submitOnboarding = handleSubmit(async (formValues) => {
    setErrorMessage(null)
    try {
      await mutation.mutateAsync(formValues)
    } catch {
      // mutation.onError already recorded the message for the wizard.
    }
  })

  const onFormSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (step < STEPS.length - 1) {
      return goNext()
    }
    return submitOnboarding()
  }

  return (
    <AuthSplitShell supporting={t('signup.shellText')}>
      <Typography variant="h5" component="h1" gutterBottom>
        {t('signup.title')}
      </Typography>
      <Stepper
        activeStep={step}
        alternativeLabel
        sx={{ mt: 1, mb: 2, '& .MuiStepLabel-label': { typography: 'caption' } }}
      >
        {STEPS.map((key) => (
          <Step key={key}>
            <StepLabel>{t(key)}</StepLabel>
          </Step>
        ))}
      </Stepper>

      <Box component="form" onSubmit={onFormSubmit} noValidate>
        <Stack spacing={2.5}>
          {step === 0 ? (
            <>
              <Typography variant="body2" color="text.secondary">
                {t('signup.schemeHint')}
              </Typography>
              <TextField
                label={t('cooperatives.fields.name')}
                required
                fullWidth
                error={Boolean(errors.name)}
                helperText={errors.name?.message}
                {...register('name')}
              />
              <Stack direction="column" spacing={2}>
                <TextField
                  label={t('cooperatives.fields.contactEmail')}
                  required
                  fullWidth
                  type="email"
                  autoComplete="email"
                  error={Boolean(errors.contactEmail)}
                  helperText={errors.contactEmail?.message}
                  {...register('contactEmail')}
                />
                <TextField
                  label={t('cooperatives.fields.contactPhone')}
                  required
                  fullWidth
                  placeholder="07XXXXXXXX"
                  error={Boolean(errors.contactPhone)}
                  helperText={
                    errors.contactPhone?.message ?? t('cooperatives.fields.contactPhoneHint')
                  }
                  slotProps={{ htmlInput: { inputMode: 'numeric', maxLength: 13 } }}
                  {...register('contactPhone')}
                />
              </Stack>
              <TextField
                label={t('cooperatives.fields.address')}
                fullWidth
                multiline
                minRows={2}
                {...register('address')}
              />
              <TextField
                value={RWANDA_CURRENCY}
                label={t('cooperatives.fields.currency')}
                required
                fullWidth
                disabled
                helperText={t('cooperatives.fields.currencyLocked')}
                slotProps={{ input: { readOnly: true } }}
              />
              <Stack direction="column" spacing={2}>
                <TextField
                  label={t('cooperatives.fields.registrationDate')}
                  type="date"
                  required
                  fullWidth
                  error={Boolean(errors.registrationDate)}
                  helperText={
                    errors.registrationDate?.message ?? t('cooperatives.fields.registrationDateHint')
                  }
                  slotProps={{
                    inputLabel: { shrink: true },
                    htmlInput: { min: MIN_REGISTRATION_DATE, max: todayIso },
                  }}
                  {...register('registrationDate')}
                />
                <Controller
                  name="financialYearStartMonth"
                  control={control}
                  render={({ field }) => (
                    <TextField
                      {...field}
                      select
                      label={t('cooperatives.fields.financialYearStartMonth')}
                      fullWidth
                      error={Boolean(errors.financialYearStartMonth)}
                      helperText={errors.financialYearStartMonth?.message}
                      onChange={(e) => field.onChange(Number(e.target.value))}
                    >
                      {MONTHS.map((m) => (
                        <MenuItem key={m} value={m}>
                          {m}
                        </MenuItem>
                      ))}
                    </TextField>
                  )}
                />
              </Stack>
              <Stack direction="column" spacing={2}>
                <TextField
                  label={t('cooperatives.fields.monthlyContributionAmount')}
                  required
                  fullWidth
                  error={Boolean(errors.monthlyContributionAmount)}
                  helperText={errors.monthlyContributionAmount?.message}
                  {...register('monthlyContributionAmount')}
                />
                <Controller
                  name="contributionDueDay"
                  control={control}
                  render={({ field }) => (
                    <TextField
                      {...field}
                      select
                      required
                      label={t('cooperatives.fields.contributionDueDay')}
                      fullWidth
                      error={Boolean(errors.contributionDueDay)}
                      helperText={
                        errors.contributionDueDay?.message ??
                        t('cooperatives.fields.contributionDueDayHint')
                      }
                      onChange={(e) => field.onChange(Number(e.target.value))}
                    >
                      {DUE_DAYS.map((d) => (
                        <MenuItem key={d} value={d}>
                          {d}
                        </MenuItem>
                      ))}
                    </TextField>
                  )}
                />
              </Stack>
              <Controller
                name="operatingModel"
                control={control}
                render={({ field }) => (
                  <OperatingModelSelect
                    value={field.value}
                    onChange={field.onChange}
                    error={errors.operatingModel?.message}
                  />
                )}
              />
            </>
          ) : null}

          {step === 1 ? (
            <>
              <Typography variant="body2" color="text.secondary">
                {t('signup.accountHint')}
              </Typography>
              <Alert severity="info">{t('signup.presidentNote')}</Alert>
              <Stack direction="column" spacing={2}>
                <TextField
                  label={t('signup.firstName')}
                  autoComplete="given-name"
                  required
                  fullWidth
                  error={Boolean(errors.firstName)}
                  helperText={errors.firstName?.message}
                  {...register('firstName')}
                />
                <TextField
                  label={t('signup.lastName')}
                  autoComplete="family-name"
                  required
                  fullWidth
                  error={Boolean(errors.lastName)}
                  helperText={errors.lastName?.message}
                  {...register('lastName')}
                />
              </Stack>
              <TextField
                label={t('signup.username')}
                autoComplete="username"
                required
                fullWidth
                error={Boolean(errors.username)}
                helperText={errors.username?.message}
                {...register('username')}
              />
              <TextField
                label={t('signup.email')}
                type="email"
                autoComplete="email"
                required
                fullWidth
                error={Boolean(errors.email)}
                helperText={errors.email?.message}
                {...register('email')}
              />
              <TextField
                label={t('signup.phone')}
                fullWidth
                placeholder="07XXXXXXXX"
                helperText={errors.phone?.message ?? t('signup.phoneOptional')}
                error={Boolean(errors.phone)}
                slotProps={{ htmlInput: { inputMode: 'numeric', maxLength: 13 } }}
                {...register('phone')}
              />
              <TextField
                label={t('signup.password')}
                type="password"
                autoComplete="new-password"
                required
                fullWidth
                error={Boolean(errors.password)}
                helperText={errors.password?.message}
                {...register('password')}
              />
              <TextField
                label={t('signup.confirmPassword')}
                type="password"
                autoComplete="new-password"
                required
                fullWidth
                error={Boolean(errors.confirmPassword)}
                helperText={errors.confirmPassword?.message}
                {...register('confirmPassword')}
              />
            </>
          ) : null}

          {step === 2 ? (
            <>
              <ReviewSection title={t('signup.reviewScheme')}>
                <ReviewRow label={t('cooperatives.fields.name')} value={values.name} />
                <ReviewRow label={t('cooperatives.fields.contactEmail')} value={values.contactEmail} />
                <ReviewRow label={t('cooperatives.fields.contactPhone')} value={values.contactPhone} />
                {values.address.trim() ? (
                  <ReviewRow label={t('cooperatives.fields.address')} value={values.address} />
                ) : null}
                <ReviewRow label={t('cooperatives.fields.currency')} value={RWANDA_CURRENCY} />
                <ReviewRow
                  label={t('cooperatives.fields.financialYearStartMonth')}
                  value={String(values.financialYearStartMonth)}
                />
                <ReviewRow
                  label={t('cooperatives.fields.monthlyContributionAmount')}
                  value={formatMoney(values.monthlyContributionAmount, { currency: RWANDA_CURRENCY })}
                />
                <ReviewRow
                  label={t('cooperatives.fields.contributionDueDay')}
                  value={String(values.contributionDueDay)}
                />
                <ReviewRow
                  label={t('cooperatives.fields.registrationDate')}
                  value={values.registrationDate}
                />
                <ReviewRow
                  label={t('operatingModel.label')}
                  value={values.operatingModel ? t(`operatingModel.options.${values.operatingModel}.title`) : ''}
                />
              </ReviewSection>
              <ReviewSection title={t('signup.reviewAccount')}>
                <ReviewRow
                  label={t('profile.fullName')}
                  value={`${values.firstName} ${values.lastName}`.trim()}
                />
                <ReviewRow label={t('signup.username')} value={values.username} />
                <ReviewRow label={t('signup.email')} value={values.email} />
                {values.phone.trim() ? (
                  <ReviewRow label={t('signup.phone')} value={values.phone} />
                ) : null}
                <Typography variant="body2" color="text.secondary">
                  {t('signup.presidentNote')}
                </Typography>
              </ReviewSection>
              <ReviewSection title={t('signup.reviewSubscription')}>
                <TrialPricingNote />
              </ReviewSection>
            </>
          ) : null}

          {errorMessage ? <Alert severity="error">{errorMessage}</Alert> : null}

          <Stack
            direction="row"
            spacing={1.5}
            data-testid="signup-actions"
            sx={{ justifyContent: step > 0 ? 'space-between' : 'flex-end', alignItems: 'center' }}
          >
            {step > 0 ? (
              <Button type="button" variant="outlined" onClick={goBack} sx={WIZARD_BUTTON_SX}>
                {t('signup.back')}
              </Button>
            ) : null}
            {step < STEPS.length - 1 ? (
              <Button type="submit" variant="contained" sx={WIZARD_BUTTON_SX}>
                {t('signup.next')}
              </Button>
            ) : (
              <Button
                type="submit"
                variant="contained"
                disabled={mutation.isPending}
                startIcon={
                  mutation.isPending ? <CircularProgress size={18} color="inherit" /> : null
                }
                sx={WIZARD_BUTTON_SX}
              >
                {t('signup.submit')}
              </Button>
            )}
          </Stack>
          <Typography variant="body2" color="text.secondary" sx={{ textAlign: 'center' }}>
            {t('signup.haveAccount')}{' '}
            <MuiLink component={RouterLink} to={ROUTES.login} underline="none" sx={plainLinkSx} data-testid="signup-login-link">
              {t('signup.signIn')}
            </MuiLink>
          </Typography>
        </Stack>
      </Box>
    </AuthSplitShell>
  )
}

function ReviewSection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Stack spacing={1}>
      <Typography variant="subtitle1">{title}</Typography>
      {children}
    </Stack>
  )
}

function ReviewRow({ label, value }: { label: string; value: string }) {
  return (
    <Stack direction="column" spacing={{ xs: 0, sm: 2 }}>
      <Typography variant="body2" color="text.secondary" sx={{ minWidth: { sm: 220 } }}>
        {label}
      </Typography>
      <Typography variant="body2">{value}</Typography>
    </Stack>
  )
}
