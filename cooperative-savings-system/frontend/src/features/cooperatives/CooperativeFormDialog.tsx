import { yupResolver } from '@hookform/resolvers/yup'
import {
  Alert,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
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
import { useEffect } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { OperatingModelSelect } from './OperatingModelSelect'
import type { Cooperative } from '@/shared/types/cooperative'
import {
  MAX_CONTRIBUTION_DUE_DAY,
  MIN_CONTRIBUTION_DUE_DAY,
  MIN_REGISTRATION_DATE,
  RWANDA_CURRENCY,
} from '@/shared/utils/rwandaCooperative'
import {
  cooperativeCreateSchema,
  cooperativeFormDefaults,
  cooperativeFormSchema,
  toCooperativePayload,
  todayInKigaliIso,
  type CooperativeFormValues,
} from './cooperativeFormSchema'
import { MoneyField } from '@/shared/components/MoneyField'

const MONTHS = Array.from({ length: 12 }, (_, i) => i + 1)
const DUE_DAYS = Array.from(
  { length: MAX_CONTRIBUTION_DUE_DAY - MIN_CONTRIBUTION_DUE_DAY + 1 },
  (_, i) => MIN_CONTRIBUTION_DUE_DAY + i,
)

interface CooperativeFormDialogProps {
  open: boolean
  mode: 'create' | 'edit'
  initial?: Cooperative | null
  loading?: boolean
  onClose: () => void
  onSubmit: (payload: ReturnType<typeof toCooperativePayload>) => void
}

function fromCooperative(coop: Cooperative): CooperativeFormValues {
  return {
    name: coop.name ?? '',
    description: coop.description ?? '',
    registrationNumber: coop.registrationNumber ?? '',
    contactEmail: coop.contactEmail ?? '',
    contactPhone: coop.contactPhone ?? '',
    address: coop.address ?? '',
    currency: RWANDA_CURRENCY,
    financialYearStartMonth: coop.financialYearStartMonth ?? 1,
    monthlyContributionAmount: String(coop.monthlyContributionAmount ?? '0'),
    contributionDueDay: coop.contributionDueDay ?? 1,
    registrationDate: coop.registrationDate ?? '',
    operatingModel: coop.operatingModel ?? '',
    startTrial: true,
    assignPresidentNow: false,
    presidentMode: 'new',
    presidentUserId: '',
    presidentUsername: '',
    presidentEmail: '',
    presidentFirstName: '',
    presidentLastName: '',
    presidentPhone: '',
    presidentTemporaryPassword: '',
  }
}

export function CooperativeFormDialog({
  open,
  mode,
  initial,
  loading = false,
  onClose,
  onSubmit,
}: CooperativeFormDialogProps) {
  const { t } = useTranslation()
  const todayIso = todayInKigaliIso()
  const {
    register,
    control,
    handleSubmit,
    reset,
    watch,
    formState: { errors },
  } = useForm<CooperativeFormValues>({
    resolver: yupResolver(mode === 'create' ? cooperativeCreateSchema : cooperativeFormSchema),
    defaultValues: cooperativeFormDefaults,
  })
  const startTrial = watch('startTrial')
  const assignPresidentNow = watch('assignPresidentNow')
  const presidentMode = watch('presidentMode')

  useEffect(() => {
    if (!open) return
    reset(initial ? fromCooperative(initial) : cooperativeFormDefaults)
  }, [open, initial, reset])

  return (
    <Dialog open={open} onClose={loading ? undefined : onClose} fullWidth maxWidth={mode === 'create' ? 'md' : 'sm'}>
      <DialogTitle>
        {mode === 'create' ? t('cooperatives.createTitle') : t('cooperatives.editTitle')}
      </DialogTitle>
      <form
        onSubmit={handleSubmit((values) => onSubmit(toCooperativePayload(values, mode)))}
        noValidate
      >
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 0.5 }}>
            {mode === 'create' ? (
              <Alert severity="info">{t('cooperatives.createWelcome')}</Alert>
            ) : null}
            <TextField
              label={t('cooperatives.fields.name')}
              required
              fullWidth
              error={Boolean(errors.name)}
              helperText={errors.name?.message}
              {...register('name')}
            />
            <TextField
              label={t('cooperatives.fields.description')}
              fullWidth
              multiline
              minRows={2}
              error={Boolean(errors.description)}
              helperText={errors.description?.message}
              {...register('description')}
            />
            <TextField
              label={t('cooperatives.fields.registrationNumber')}
              required
              fullWidth
              placeholder={t('cooperatives.fields.registrationNumberPlaceholder')}
              error={Boolean(errors.registrationNumber)}
              helperText={
                errors.registrationNumber?.message ??
                t('cooperatives.fields.registrationNumberHint')
              }
              {...register('registrationNumber')}
            />
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
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
                slotProps={{
                  htmlInput: { inputMode: 'numeric', maxLength: 13 },
                }}
                {...register('contactPhone')}
              />
            </Stack>
            <TextField
              label={t('cooperatives.fields.address')}
              fullWidth
              multiline
              minRows={2}
              error={Boolean(errors.address)}
              helperText={errors.address?.message}
              {...register('address')}
            />
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <Controller
                name="currency"
                control={control}
                render={({ field }) => (
                  <TextField
                    {...field}
                    value={RWANDA_CURRENCY}
                    label={t('cooperatives.fields.currency')}
                    required
                    fullWidth
                    disabled
                    helperText={t('cooperatives.fields.currencyLocked')}
                    slotProps={{ input: { readOnly: true } }}
                  />
                )}
              />
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
            </Stack>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
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
            <MoneyField
              label={t('cooperatives.fields.monthlyContributionAmount')}
              required
              fullWidth
              error={Boolean(errors.monthlyContributionAmount)}
              helperText={errors.monthlyContributionAmount?.message}
              {...register('monthlyContributionAmount')}
            />
            {mode === 'create' ? (
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
            ) : null}
            {mode === 'create' ? (
              <>
                <Divider />
                <Typography variant="subtitle2">{t('cooperatives.onboarding.subscriptionTitle')}</Typography>
                <Typography variant="body2" color="text.secondary">
                  {t('cooperatives.onboarding.subscriptionHint')}
                </Typography>
                <Controller
                  name="startTrial"
                  control={control}
                  render={({ field }) => (
                    <FormControlLabel
                      control={
                        <Checkbox
                          checked={field.value}
                          onChange={(e) => field.onChange(e.target.checked)}
                        />
                      }
                      label={t('cooperatives.onboarding.startTrial')}
                    />
                  )}
                />
                {!startTrial ? (
                  <Alert severity="info">{t('cooperatives.onboarding.noTrial')}</Alert>
                ) : null}

                <Typography variant="subtitle2">{t('cooperatives.onboarding.presidentTitle')}</Typography>
                <Controller
                  name="assignPresidentNow"
                  control={control}
                  render={({ field }) => (
                    <FormControl>
                      <FormLabel>{t('cooperatives.onboarding.presidentWhen')}</FormLabel>
                      <RadioGroup
                        value={field.value ? 'now' : 'later'}
                        onChange={(e) => field.onChange(e.target.value === 'now')}
                      >
                        <FormControlLabel
                          value="now"
                          control={<Radio />}
                          label={t('cooperatives.onboarding.assignNow')}
                        />
                        <FormControlLabel
                          value="later"
                          control={<Radio />}
                          label={t('cooperatives.onboarding.assignLater')}
                        />
                      </RadioGroup>
                    </FormControl>
                  )}
                />
                {!assignPresidentNow ? (
                  <Alert severity="warning">{t('cooperatives.onboarding.deferWarning')}</Alert>
                ) : (
                  <Stack spacing={2}>
                    <Controller
                      name="presidentMode"
                      control={control}
                      render={({ field }) => (
                        <TextField
                          {...field}
                          select
                          label={t('cooperatives.onboarding.presidentMode')}
                          fullWidth
                        >
                          <MenuItem value="new">{t('cooperatives.onboarding.createPresident')}</MenuItem>
                          <MenuItem value="existing">{t('cooperatives.onboarding.existingPresident')}</MenuItem>
                        </TextField>
                      )}
                    />
                    {presidentMode === 'existing' ? (
                      <TextField
                        label={t('cooperatives.onboarding.presidentUserId')}
                        fullWidth
                        error={Boolean(errors.presidentUserId)}
                        helperText={
                          errors.presidentUserId?.message ?? t('cooperatives.onboarding.presidentUserIdHint')
                        }
                        {...register('presidentUserId')}
                      />
                    ) : (
                      <>
                        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                          <TextField
                            label={t('cooperatives.onboarding.presidentFirstName')}
                            required
                            fullWidth
                            error={Boolean(errors.presidentFirstName)}
                            helperText={errors.presidentFirstName?.message}
                            {...register('presidentFirstName')}
                          />
                          <TextField
                            label={t('cooperatives.onboarding.presidentLastName')}
                            required
                            fullWidth
                            error={Boolean(errors.presidentLastName)}
                            helperText={errors.presidentLastName?.message}
                            {...register('presidentLastName')}
                          />
                        </Stack>
                        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                          <TextField
                            label={t('cooperatives.onboarding.presidentUsername')}
                            required
                            fullWidth
                            error={Boolean(errors.presidentUsername)}
                            helperText={errors.presidentUsername?.message}
                            {...register('presidentUsername')}
                          />
                          <TextField
                            label={t('cooperatives.onboarding.presidentEmail')}
                            required
                            fullWidth
                            type="email"
                            error={Boolean(errors.presidentEmail)}
                            helperText={errors.presidentEmail?.message}
                            {...register('presidentEmail')}
                          />
                        </Stack>
                        <TextField
                          label={t('cooperatives.onboarding.presidentPhone')}
                          fullWidth
                          error={Boolean(errors.presidentPhone)}
                          helperText={errors.presidentPhone?.message}
                          {...register('presidentPhone')}
                        />
                        <TextField
                          label={t('cooperatives.onboarding.presidentTemporaryPassword')}
                          fullWidth
                          type="password"
                          error={Boolean(errors.presidentTemporaryPassword)}
                          helperText={
                            errors.presidentTemporaryPassword?.message ??
                            t('cooperatives.onboarding.presidentTemporaryPasswordHint')
                          }
                          {...register('presidentTemporaryPassword')}
                        />
                      </>
                    )}
                  </Stack>
                )}
              </>
            ) : null}
            {mode === 'create' ? (
              <Typography variant="caption" color="text.secondary">
                {t('cooperatives.createWelcomeFooter')}
              </Typography>
            ) : null}
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={onClose} disabled={loading}>
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
