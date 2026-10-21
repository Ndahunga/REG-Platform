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
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useState } from 'react'
import { useWatch, useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { todayInKigaliIso } from '@/shared/utils/rwandaCooperative'
import { getErrorMessage } from '@/shared/api/client'
import { uploadCooperativeFile } from '@/shared/api/files'
import { createTransaction } from '@/shared/api/transactions'
import { INCOME_EXPENSE_CATEGORIES, LEDGER_EFFECTS } from '@/shared/types/incomeExpense'
import {
  toTransactionCreatePayload,
  transactionCreateDefaults,
  transactionCreateSchema,
  type TransactionCreateFormValues,
} from './transactionFormSchemas'

interface TransactionCreatePanelProps {
  cooperativeId: string
}

export function TransactionCreatePanel({ cooperativeId }: TransactionCreatePanelProps) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const { enqueueSnackbar } = useSnackbar()
  const [uploadError, setUploadError] = useState<string | null>(null)
  const [uploadedName, setUploadedName] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    control,
    reset,
    setValue,
    watch,
    formState: { errors },
  } = useForm<TransactionCreateFormValues>({
    defaultValues: transactionCreateDefaults(),
    resolver: yupResolver(transactionCreateSchema),
  })

  const category = useWatch({ control, name: 'category' })
  const supportingKey = watch('supportingFileKey')

  const uploadMutation = useMutation({
    mutationFn: (file: File) =>
      uploadCooperativeFile(cooperativeId, file, 'INCOME_EXPENSE_DOCUMENT'),
    onSuccess: (file) => {
      setValue('supportingFileKey', file.storageKey)
      setUploadedName(file.originalFilename)
      setUploadError(null)
    },
    onError: (error) => {
      setUploadError(getErrorMessage(error, t('fines.payment.uploadFailed')))
    },
  })

  const mutation = useMutation({
    mutationFn: (values: TransactionCreateFormValues) =>
      createTransaction(cooperativeId, toTransactionCreatePayload(values)),
    onSuccess: () => {
      enqueueSnackbar(t('transactions.create.success'), { variant: 'success' })
      reset(transactionCreateDefaults())
      setUploadedName(null)
      setUploadError(null)
      void queryClient.invalidateQueries({ queryKey: ['transactions'] })
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
        {t('transactions.create.title')}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {t('transactions.create.description')}
      </Typography>

      <Alert severity="info" sx={{ mb: 2 }}>
        {t('transactions.create.pendingHint')}
      </Alert>

      <Stack spacing={2}>
        <TextField
          select
          label={t('transactions.fields.category')}
          defaultValue=""
          {...register('category')}
          error={Boolean(errors.category)}
          helperText={errors.category?.message}
          fullWidth
        >
          <MenuItem value="" disabled>
            {t('transactions.create.selectCategory')}
          </MenuItem>
          {INCOME_EXPENSE_CATEGORIES.map((c) => (
            <MenuItem key={c} value={c}>
              {t(`transactions.category.${c}`)}
            </MenuItem>
          ))}
        </TextField>

        {category === 'ADJUSTMENT' ? (
          <TextField
            select
            label={t('transactions.fields.ledgerEffect')}
            defaultValue=""
            {...register('ledgerEffect')}
            error={Boolean(errors.ledgerEffect)}
            helperText={
              errors.ledgerEffect?.message || t('transactions.create.adjustmentHint')
            }
            fullWidth
          >
            <MenuItem value="" disabled>
              {t('transactions.create.selectDirection')}
            </MenuItem>
            {LEDGER_EFFECTS.map((effect) => (
              <MenuItem key={effect} value={effect}>
                {t(`transactions.ledgerEffect.${effect}`)}
              </MenuItem>
            ))}
          </TextField>
        ) : null}

        <TextField
          label={t('transactions.fields.amount')}
          {...register('amount')}
          error={Boolean(errors.amount)}
          helperText={errors.amount?.message}
          fullWidth
        />
        <TextField
          label={t('transactions.fields.transactionDate')}
          type="date"
          slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: todayInKigaliIso() } }}
          {...register('transactionDate')}
          error={Boolean(errors.transactionDate)}
          helperText={errors.transactionDate?.message}
          fullWidth
        />
        <TextField
          label={t('transactions.fields.reference')}
          error={Boolean(errors.reference)}
          helperText={errors.reference?.message}
          {...register('reference')}
          fullWidth
        />
        <TextField
          label={t('transactions.fields.description')}
          error={Boolean(errors.description)}
          helperText={errors.description?.message}
          {...register('description')}
          fullWidth
          multiline
          minRows={2}
        />
        <TextField
          label={t('transactions.fields.notes')}
          error={Boolean(errors.notes)}
          helperText={errors.notes?.message}
          {...register('notes')}
          fullWidth
          multiline
          minRows={2}
        />
        <Stack spacing={1}>
          <Typography variant="subtitle2">{t('transactions.create.supportingFile')}</Typography>
          <Button
            variant="outlined"
            component="label"
            disabled={!cooperativeId || uploadMutation.isPending}
          >
            {uploadMutation.isPending
              ? t('common.loading')
              : t('transactions.create.uploadSupporting')}
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
          {uploadedName || supportingKey ? (
            <Typography variant="body2" color="text.secondary">
              {uploadedName || supportingKey}
            </Typography>
          ) : (
            <Typography variant="caption" color="text.secondary">
              {t('transactions.create.supportingOptional')}
            </Typography>
          )}
          {uploadError ? <Alert severity="error">{uploadError}</Alert> : null}
        </Stack>
        <Button type="submit" variant="contained" disabled={mutation.isPending}>
          {t('transactions.create.submit')}
        </Button>
      </Stack>
    </Box>
  )
}
