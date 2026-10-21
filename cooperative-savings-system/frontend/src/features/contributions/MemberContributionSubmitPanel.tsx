import {
  Alert,
  Box,
  Button,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { yupResolver } from '@hookform/resolvers/yup'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useEffect, useMemo, useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import * as yup from 'yup'
import { getErrorMessage } from '@/shared/api/client'
import {
  fetchContributionPeriodPreview,
  submitRegularContribution,
} from '@/shared/api/contributions'
import { uploadCooperativeFile } from '@/shared/api/files'
import { ObligationDetails } from './ObligationDetails'
import { formatMoney } from '@/shared/utils/formatMoney'
import { checkMoney, isFutureIsoDate, isValidIsoDate } from '@/shared/utils/formValidation'
import { todayInKigaliIso } from '@/shared/utils/rwandaCooperative'

interface FormValues {
  year: string
  month: string
  amount: string
  paymentDate: string
  paymentReference: string
  evidenceFileKey: string
  notes: string
}

function currentPeriod(): { year: string; month: string } {
  const now = new Date()
  return {
    year: String(now.getFullYear()),
    month: String(now.getMonth() + 1),
  }
}

const period = currentPeriod()

const defaults: FormValues = {
  year: period.year,
  month: period.month,
  amount: '',
  paymentDate: todayInKigaliIso(),
  paymentReference: '',
  evidenceFileKey: '',
  notes: '',
}

interface MemberContributionSubmitPanelProps {
  cooperativeId: string
}

export function MemberContributionSubmitPanel({
  cooperativeId,
}: MemberContributionSubmitPanelProps) {
  const [remainingForValidation, setRemainingForValidation] = useState<string | null>(null)
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const { enqueueSnackbar } = useSnackbar()
  const [uploadedName, setUploadedName] = useState<string | null>(null)
  const [uploadError, setUploadError] = useState<string | null>(null)

  const schema = useMemo(
    () =>
      yup.object({
        year: yup.string().trim().required(),
        month: yup.string().trim().required(),
        // Backend: @DecimalMin("0.01") and the service rejects more than the remaining amount.
        amount: yup
          .string()
          .trim()
          .required(t('contributions.submit.amountRequired'))
          .test('amount', t('contributions.submit.amountInvalid'), (value) => {
            const problem = checkMoney(value, { min: '0.01' })
            return problem === null || problem === 'required'
          })
          .test(
            'amount-remaining',
            t('contributions.submit.amountExceedsRemaining', { remaining: remainingForValidation }),
            (value) => {
              if (remainingForValidation == null || checkMoney(value, { min: '0.01' }) !== null) return true
              return Number(value) <= Number(remainingForValidation)
            },
          ),
        paymentDate: yup
          .string()
          .trim()
          .required(t('contributions.submit.dateRequired'))
          .test('valid-date', t('contributions.validation.dateInvalid'), (value) => !value || isValidIsoDate(value))
          .test(
            'not-future',
            t('contributions.validation.dateFuture'),
            (value) => !value || !isValidIsoDate(value) || !isFutureIsoDate(value, todayInKigaliIso()),
          ),
        paymentReference: yup
          .string()
          .trim()
          .max(128, t('contributions.validation.referenceTooLong'))
          .default(''),
        evidenceFileKey: yup.string().trim().max(512).default(''),
        notes: yup.string().trim().max(2000, t('contributions.validation.notesTooLong')).default(''),
      }),
    [t, remainingForValidation],
  )

  const {
    register,
    handleSubmit,
    reset,
    setValue,
    watch,
    formState: { errors },
  } = useForm<FormValues>({
    defaultValues: defaults,
    resolver: yupResolver(schema),
  })

  const evidenceKey = watch('evidenceFileKey')
  const year = Number(watch('year'))
  const month = Number(watch('month'))
  const amount = watch('amount')

  const previewQuery = useQuery({
    queryKey: ['contributions', 'period-preview', cooperativeId, year, month],
    queryFn: () => fetchContributionPeriodPreview(cooperativeId, year, month),
    enabled: Boolean(cooperativeId && year && month),
  })

  const preview = previewQuery.data
  const previewRemaining =
    preview?.remainingAmount != null && checkMoney(String(preview.remainingAmount), { allowZero: true }) === null
      ? String(preview.remainingAmount)
      : null
  useEffect(() => {
    setRemainingForValidation(previewRemaining)
  }, [previewRemaining])
  const remaining = Number(preview?.remainingAmount ?? 0)
  // Text that is not a valid amount counts as 0 here so the summary keeps rendering while the user types.
  const payingNow = checkMoney(amount, { allowZero: true }) === null ? Number(amount) : 0
  const remainingAfter = Math.max(0, remaining - payingNow)

  const uploadMutation = useMutation({
    mutationFn: (file: File) =>
      uploadCooperativeFile(cooperativeId, file, 'CONTRIBUTION_EVIDENCE'),
    onSuccess: (file) => {
      setValue('evidenceFileKey', file.storageKey)
      setUploadedName(file.originalFilename)
      setUploadError(null)
    },
    onError: (error) => {
      setUploadError(getErrorMessage(error, t('contributions.submit.uploadFailed')))
    },
  })

  const submitMutation = useMutation({
    mutationFn: (values: FormValues) =>
      submitRegularContribution(cooperativeId, {
        year: Number(values.year),
        month: Number(values.month),
        amount: values.amount.trim(),
        paymentDate: values.paymentDate.trim(),
        paymentReference: values.paymentReference.trim() || undefined,
        evidenceFileKey: values.evidenceFileKey.trim() || undefined,
        notes: values.notes.trim() || undefined,
      }),
    onSuccess: () => {
      enqueueSnackbar(t('contributions.submit.success'), { variant: 'success' })
      reset({ ...defaults, year: String(year), month: String(month) })
      setUploadedName(null)
      void queryClient.invalidateQueries({ queryKey: ['contributions'] })
      void queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const years = Array.from({ length: 6 }, (_, i) => new Date().getFullYear() - 2 + i)

  return (
    <Box
      component="form"
      noValidate
      onSubmit={handleSubmit((values) => submitMutation.mutate(values))}
      sx={{
        maxWidth: 560,
        p: { xs: 2, sm: 2.5 },
        border: '1px solid',
        borderColor: 'divider',
        borderRadius: 1,
      }}
    >
      <Typography variant="h6" gutterBottom>
        {t('contributions.submit.title')}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {t('contributions.submit.description')}
      </Typography>
      <Stack spacing={2}>
        <TextField
          select
          label={t('contributions.submit.paymentMonth')}
          error={Boolean(errors.month)}
          {...register('month')}
          fullWidth
        >
          {Array.from({ length: 12 }, (_, i) => i + 1).map((value) => (
            <MenuItem key={value} value={String(value)}>
              {t(`contributions.months.${value}`, {
                defaultValue: new Date(2000, value - 1, 1).toLocaleString(undefined, {
                  month: 'long',
                }),
              })}
            </MenuItem>
          ))}
        </TextField>
        <TextField
          select
          label={t('contributions.fields.year')}
          error={Boolean(errors.year)}
          {...register('year')}
          fullWidth
        >
          {years.map((value) => (
            <MenuItem key={value} value={String(value)}>
              {value}
            </MenuItem>
          ))}
        </TextField>
        {previewQuery.data ? (
          <Alert severity={preview?.canSubmit === false ? 'warning' : 'info'}>
            <Stack spacing={0.5}>
              <Typography variant="body2">
                {t('contributions.submit.shares')}: {preview?.shareCount}
              </Typography>
              <Typography variant="body2">
                {t('contributions.submit.required')}: {formatMoney(preview?.requiredAmount ?? 0)}
              </Typography>
              <Typography variant="body2">
                {t('contributions.submit.alreadyPaid')}: {formatMoney(preview?.paidAmount ?? 0)}
              </Typography>
              <Typography variant="body2">
                {t('contributions.submit.payingNow')}: {formatMoney(payingNow || 0)}
              </Typography>
              <Typography variant="body2">
                {t('contributions.submit.remaining')}: {formatMoney(remainingAfter)}
              </Typography>
              <Typography variant="body2">
                {t('contributions.submit.paymentMonth')}: {preview?.year}-
                {String(preview?.month).padStart(2, '0')}
              </Typography>
              {preview?.dueDate ? (
                <Typography variant="body2" data-testid="contribution-preview-due-date">
                  {t('contributions.obligation.dueDate', { date: preview.dueDate })}
                </Typography>
              ) : null}
              <ObligationDetails fields={preview ?? {}} />
              <Typography variant="body2">
                {t('contributions.fields.status')}:{' '}
                {preview?.reviewStatus
                  ? t(`contributions.reviewStatus.${preview.reviewStatus}`, {
                      defaultValue: String(preview.reviewStatus),
                    })
                  : t(`contributions.status.${preview?.status}`, {
                      defaultValue: String(preview?.status ?? 'PENDING'),
                    })}
              </Typography>
            </Stack>
          </Alert>
        ) : null}
        <TextField
          label={t('contributions.submit.amountPaidNow')}
          error={Boolean(errors.amount)}
          helperText={errors.amount?.message}
          {...register('amount')}
          fullWidth
        />
        <TextField
          type="date"
          label={t('contributions.fields.paymentDate')}
          slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: todayInKigaliIso() } }}
          error={Boolean(errors.paymentDate)}
          helperText={errors.paymentDate?.message}
          {...register('paymentDate')}
          fullWidth
        />
        <TextField
          label={t('contributions.fields.reference')}
          error={Boolean(errors.paymentReference)}
          helperText={errors.paymentReference?.message}
          {...register('paymentReference')}
          fullWidth
        />
        <Stack spacing={1}>
          <Typography variant="subtitle2">{t('contributions.submit.proof')}</Typography>
          <Button
            variant="outlined"
            component="label"
            disabled={uploadMutation.isPending}
          >
            {uploadMutation.isPending
              ? t('common.loading')
              : t('contributions.submit.uploadProof')}
            <input
              hidden
              type="file"
              accept=".pdf,.png,.jpg,.jpeg,.webp,application/pdf,image/*"
              onChange={(e) => {
                const file = e.target.files?.[0]
                if (file) uploadMutation.mutate(file)
                e.target.value = ''
              }}
            />
          </Button>
          {uploadedName || evidenceKey ? (
            <Typography variant="body2" color="text.secondary">
              {uploadedName || evidenceKey}
            </Typography>
          ) : (
            <Typography variant="caption" color="text.secondary">
              {t('contributions.submit.proofHint')}
            </Typography>
          )}
          {uploadError ? <Alert severity="error">{uploadError}</Alert> : null}
        </Stack>
        <TextField
          label={t('contributions.fields.notes')}
          error={Boolean(errors.notes)}
          helperText={errors.notes?.message}
          {...register('notes')}
          fullWidth
          multiline
          minRows={2}
        />
        <Button
          type="submit"
          variant="contained"
          disabled={submitMutation.isPending || preview?.canSubmit === false}
        >
          {t('contributions.submit.action')}
        </Button>
      </Stack>
    </Box>
  )
}
