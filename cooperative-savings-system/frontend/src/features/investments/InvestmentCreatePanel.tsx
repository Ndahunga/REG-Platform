import { Alert, Box, Button, Stack, TextField, Typography } from '@mui/material'
import { yupResolver } from '@hookform/resolvers/yup'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { todayInKigaliIso } from '@/shared/utils/rwandaCooperative'
import { useNavigate } from 'react-router-dom'
import { getErrorMessage } from '@/shared/api/client'
import { uploadCooperativeFile } from '@/shared/api/files'
import { createInvestment } from '@/shared/api/investments'
import { ROUTES } from '@/shared/constants/routes'
import {
  investmentCreateDefaults,
  investmentCreateSchema,
  toInvestmentCreatePayload,
  type InvestmentCreateFormValues,
} from './investmentFormSchemas'

interface InvestmentCreatePanelProps {
  cooperativeId: string
}

export function InvestmentCreatePanel({ cooperativeId }: InvestmentCreatePanelProps) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { enqueueSnackbar } = useSnackbar()
  const [uploadError, setUploadError] = useState<string | null>(null)
  const [uploadedName, setUploadedName] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    reset,
    setValue,
    watch,
    formState: { errors },
  } = useForm<InvestmentCreateFormValues>({
    defaultValues: investmentCreateDefaults,
    resolver: yupResolver(investmentCreateSchema),
  })

  const documentKey = watch('documentFileKey')

  const uploadMutation = useMutation({
    mutationFn: (file: File) =>
      uploadCooperativeFile(cooperativeId, file, 'INVESTMENT_DOCUMENT'),
    onSuccess: (file) => {
      setValue('documentFileKey', file.storageKey)
      setUploadedName(file.originalFilename)
      setUploadError(null)
    },
    onError: (error) => {
      setUploadError(getErrorMessage(error, t('fines.payment.uploadFailed')))
    },
  })

  const mutation = useMutation({
    mutationFn: (values: InvestmentCreateFormValues) =>
      createInvestment(cooperativeId, toInvestmentCreatePayload(values)),
    onSuccess: (investment) => {
      enqueueSnackbar(t('investments.create.success'), { variant: 'success' })
      reset(investmentCreateDefaults)
      setUploadedName(null)
      setUploadError(null)
      void queryClient.invalidateQueries({ queryKey: ['investments'] })
      navigate(ROUTES.investmentDetail(investment.id))
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  return (
    <Box
      component="form"
      noValidate
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
        {t('investments.create.title')}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {t('investments.create.description')}
      </Typography>

      <Stack spacing={2}>
        <TextField
          label={t('investments.fields.name')}
          {...register('name')}
          error={Boolean(errors.name)}
          helperText={errors.name?.message}
          fullWidth
        />
        <TextField
          label={t('investments.fields.amount')}
          {...register('amount')}
          error={Boolean(errors.amount)}
          helperText={errors.amount?.message}
          fullWidth
        />
        <TextField
          label={t('investments.fields.expectedReturnAmount')}
          {...register('expectedReturnAmount')}
          error={Boolean(errors.expectedReturnAmount)}
          helperText={errors.expectedReturnAmount?.message}
          fullWidth
        />
        <TextField
          label={t('investments.fields.expectedReturnDate')}
          type="date"
          slotProps={{ inputLabel: { shrink: true }, htmlInput: { min: todayInKigaliIso() } }}
          {...register('expectedReturnDate')}
          error={Boolean(errors.expectedReturnDate)}
          helperText={errors.expectedReturnDate?.message}
          fullWidth
        />
        <TextField
          label={t('investments.fields.description')}
          {...register('description')}
          error={Boolean(errors.description)}
          helperText={errors.description?.message}
          fullWidth
          multiline
          minRows={3}
        />
        <Stack spacing={1}>
          <Typography variant="subtitle2">{t('investments.create.document')}</Typography>
          <Button
            variant="outlined"
            component="label"
            disabled={!cooperativeId || uploadMutation.isPending}
          >
            {uploadMutation.isPending
              ? t('common.loading')
              : t('investments.create.uploadDocument')}
            <input
              hidden
              type="file"
              accept=".pdf,.png,.jpg,.jpeg,.webp,.xlsx,application/pdf,image/*"
              onChange={(e) => {
                const file = e.target.files?.[0]
                if (file) uploadMutation.mutate(file)
                e.target.value = ''
              }}
            />
          </Button>
          {uploadedName || documentKey ? (
            <Typography variant="body2" color="text.secondary">
              {uploadedName || documentKey}
            </Typography>
          ) : (
            <Typography variant="caption" color="text.secondary">
              {t('investments.create.documentOptional')}
            </Typography>
          )}
          {uploadError ? <Alert severity="error">{uploadError}</Alert> : null}
        </Stack>
        <Button type="submit" variant="contained" disabled={mutation.isPending}>
          {t('investments.create.submit')}
        </Button>
      </Stack>
    </Box>
  )
}
