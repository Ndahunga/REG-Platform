import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  TextField,
} from '@mui/material'
import { yupResolver } from '@hookform/resolvers/yup'
import { useEffect, useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import type { LoanRepaymentCreateRequest, LoanSettlement } from '@/shared/types/loan'
import { formatMoney } from '@/shared/utils/formatMoney'
import { isValidIsoDate } from '@/shared/utils/formValidation'
import { todayInKigaliIso } from '@/shared/utils/rwandaCooperative'
import {
  repaymentDefaults,
  repaymentSchema,
  toRepaymentPayload,
  type RepaymentFormValues,
} from './loanFormSchemas'
import { MoneyField } from '@/shared/components/MoneyField'

interface RepaymentDialogProps {
  open: boolean
  loading?: boolean
  maxHint?: string
  onClose: () => void
  onSubmit: (payload: LoanRepaymentCreateRequest) => void
  /** What repaying in full on a date takes (interest only for the months used). */
  settlementFor?: (date: string) => Promise<LoanSettlement>
}

export function RepaymentDialog({
  open,
  loading,
  maxHint,
  onClose,
  onSubmit,
  settlementFor,
}: RepaymentDialogProps) {
  const { t } = useTranslation()
  const {
    register,
    handleSubmit,
    reset,
    setValue,
    watch,
    formState: { errors },
  } = useForm<RepaymentFormValues>({
    defaultValues: repaymentDefaults,
    resolver: yupResolver(repaymentSchema),
  })

  useEffect(() => {
    if (open) reset({ ...repaymentDefaults, paymentDate: todayInKigaliIso() })
  }, [open, reset])

  const paymentDate = watch('paymentDate')
  const [quote, setQuote] = useState<LoanSettlement | null>(null)
  useEffect(() => {
    if (!open || !settlementFor || !isValidIsoDate(paymentDate)) return
    let current = true
    Promise.resolve()
      .then(() => settlementFor(paymentDate))
      .then((q) => {
        if (current) setQuote(q ?? null)
      })
      .catch(() => {
        if (current) setQuote(null)
      })
    return () => {
      current = false
    }
  }, [open, paymentDate, settlementFor])
  const waived = quote ? Number(quote.interestWaived) : 0

  const handleClose = () => {
    reset(repaymentDefaults)
    onClose()
  }

  return (
    <Dialog open={open} onClose={handleClose} fullWidth maxWidth="sm">
      <DialogTitle>{t('loans.repayment.title')}</DialogTitle>
      <form
        noValidate
        onSubmit={handleSubmit((values) => {
          onSubmit(toRepaymentPayload(values))
        })}
      >
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 1 }}>
            {quote && Number(quote.total) > 0 ? (
              <Alert
                severity="info"
                data-testid="loan-settlement"
                action={
                  <Button
                    size="small"
                    onClick={() =>
                      setValue('amount', String(Number(quote.total)), { shouldValidate: true, shouldDirty: true })
                    }
                  >
                    {t('loans.repayment.useSettlement')}
                  </Button>
                }
              >
                {t('loans.repayment.settlement', {
                  date: quote.asOf,
                  total: formatMoney(quote.total),
                  principal: formatMoney(quote.principal),
                  interest: formatMoney(quote.interestToPay),
                  months: quote.monthsCharged,
                })}
                {waived > 0 ? ` ${t('loans.repayment.settlementWaived', { amount: formatMoney(quote.interestWaived) })}` : ''}
              </Alert>
            ) : null}
            <MoneyField
              label={t('loans.repayment.amount')}
              error={Boolean(errors.amount)}
              helperText={errors.amount?.message || maxHint}
              {...register('amount')}
              fullWidth
              autoFocus
            />
            <TextField
              type="date"
              label={t('loans.fields.paymentDate')}
              slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: todayInKigaliIso() } }}
              error={Boolean(errors.paymentDate)}
              helperText={errors.paymentDate?.message}
              {...register('paymentDate')}
              fullWidth
            />
            <TextField
              label={t('loans.fields.reference')}
              error={Boolean(errors.paymentReference)}
              helperText={errors.paymentReference?.message}
              {...register('paymentReference')}
              fullWidth
            />
            <TextField
              label={t('loans.fields.notes')}
              error={Boolean(errors.notes)}
              helperText={errors.notes?.message}
              {...register('notes')}
              fullWidth
              multiline
              minRows={2}
            />
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={handleClose} disabled={loading}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" variant="contained" disabled={loading}>
            {t('loans.repayment.submit')}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}
