import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { yupResolver } from '@hookform/resolvers/yup'
import { useMutation } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { todayInKigaliIso } from '@/shared/utils/rwandaCooperative'
import { useAppSelector } from '@/app/store/hooks'
import { uploadCooperativeFile } from '@/shared/api/files'
import { getErrorMessage } from '@/shared/api/client'
import {
  FINE_PAYMENT_METHODS,
  type FinePaymentCreateRequest,
  type FinePaymentMethod,
} from '@/shared/types/fine'
import {
  finePaymentDefaults,
  finePaymentSchema,
  toFinePaymentPayload,
  type FinePaymentFormValues,
} from './fineFormSchemas'

interface FinePaymentDialogProps {
  open: boolean
  loading?: boolean
  outstanding?: number | null
  maxHint?: string
  onClose: () => void
  onSubmit: (payload: FinePaymentCreateRequest) => void
}

export function FinePaymentDialog({
  open,
  loading,
  outstanding,
  maxHint,
  onClose,
  onSubmit,
}: FinePaymentDialogProps) {
  const { t } = useTranslation()
  const cooperativeId = useAppSelector((s) => s.auth.selectedCooperativeId)
  const schema = useMemo(() => finePaymentSchema(outstanding), [outstanding])
  const [uploadError, setUploadError] = useState<string | null>(null)
  const [uploadedName, setUploadedName] = useState<string | null>(null)

  const {
    register,
    control,
    handleSubmit,
    reset,
    setValue,
    watch,
    formState: { errors },
  } = useForm<FinePaymentFormValues>({
    defaultValues: finePaymentDefaults,
    resolver: yupResolver(schema),
  })

  const paymentMethod = watch('paymentMethod')
  const evidenceKey = watch('evidenceFileKey')

  useEffect(() => {
    if (open) {
      reset(finePaymentDefaults)
      setUploadError(null)
      setUploadedName(null)
    }
  }, [open, reset])

  const uploadMutation = useMutation({
    mutationFn: (file: File) => {
      if (!cooperativeId) throw new Error(t('cooperatives.noneSelected'))
      return uploadCooperativeFile(cooperativeId, file, 'FINE_PAYMENT_EVIDENCE')
    },
    onSuccess: (file) => {
      setValue('evidenceFileKey', file.storageKey)
      setUploadedName(file.originalFilename)
      setUploadError(null)
    },
    onError: (error) => {
      setUploadError(getErrorMessage(error, t('fines.payment.uploadFailed')))
    },
  })

  const handleClose = () => {
    reset(finePaymentDefaults)
    onClose()
  }

  return (
    <Dialog open={open} onClose={handleClose} fullWidth maxWidth="sm">
      <DialogTitle>{t('fines.payment.title')}</DialogTitle>
      <form
        noValidate
        onSubmit={handleSubmit((values) => {
          onSubmit(toFinePaymentPayload(values))
        })}
      >
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 1 }}>
            <TextField
              label={t('fines.payment.amount')}
              error={Boolean(errors.amount)}
              helperText={errors.amount?.message || maxHint}
              {...register('amount')}
              fullWidth
              autoFocus
            />
            <TextField
              type="date"
              label={t('fines.fields.paymentDate')}
              error={Boolean(errors.paymentDate)}
              helperText={errors.paymentDate?.message}
              slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: todayInKigaliIso() } }}
              {...register('paymentDate')}
              fullWidth
            />
            <Controller
              name="paymentMethod"
              control={control}
              render={({ field }) => (
                <TextField
                  {...field}
                  select
                  label={t('fines.payment.method')}
                  error={Boolean(errors.paymentMethod)}
                  helperText={errors.paymentMethod?.message}
                  fullWidth
                >
                  {FINE_PAYMENT_METHODS.map((method) => (
                    <MenuItem key={method} value={method}>
                      {t(`fines.payment.methods.${method}`)}
                    </MenuItem>
                  ))}
                </TextField>
              )}
            />
            {paymentMethod === 'OTHER' ? (
              <TextField
                label={t('fines.payment.methodDetail')}
                {...register('paymentMethodDetail')}
                fullWidth
              />
            ) : null}
            <TextField
              label={t('fines.fields.reference')}
              {...register('paymentReference')}
              fullWidth
            />
            <Stack spacing={1}>
              <Typography variant="subtitle2">{t('fines.payment.evidence')}</Typography>
              <Button
                variant="outlined"
                component="label"
                disabled={!cooperativeId || uploadMutation.isPending}
              >
                {uploadMutation.isPending
                  ? t('common.loading')
                  : t('fines.payment.uploadEvidence')}
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
              {uploadedName || evidenceKey ? (
                <Typography variant="body2" color="text.secondary">
                  {uploadedName || evidenceKey}
                </Typography>
              ) : (
                <Typography variant="caption" color="text.secondary">
                  {t('fines.payment.evidenceOptional')}
                </Typography>
              )}
              {uploadError ? <Alert severity="error">{uploadError}</Alert> : null}
            </Stack>
            <TextField
              label={t('fines.fields.notes')}
              {...register('notes')}
              fullWidth
              multiline
              minRows={2}
            />
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2, flexWrap: 'wrap', gap: 1 }}>
          <Button onClick={handleClose} disabled={loading}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" variant="contained" disabled={loading}>
            {t('fines.payment.submit')}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}

// keep type export used by schema
export type { FinePaymentMethod }
