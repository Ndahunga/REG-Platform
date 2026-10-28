import { Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle, Stack, TextField } from '@mui/material'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { MoneyField } from '@/shared/components/MoneyField'
import type { LoanRepayment, LoanRepaymentReviseRequest } from '@/shared/types/loan'
import { checkMoney } from '@/shared/utils/formValidation'
import { formatOptionalMoney } from '@/shared/utils/formatMoney'
import { todayInKigaliIso } from '@/shared/utils/rwandaCooperative'

interface ReviseRepaymentDialogProps {
  repayment: LoanRepayment
  loading?: boolean
  onClose: () => void
  onSubmit: (payload: LoanRepaymentReviseRequest) => void
}

/** Corrects a recorded repayment. The wrong one stays in the history, marked revised, with the reason. */
export function ReviseRepaymentDialog({ repayment, loading, onClose, onSubmit }: ReviseRepaymentDialogProps) {
  const { t } = useTranslation()
  const [amount, setAmount] = useState(String(Math.round(Number(repayment.amountTotal) * 100) / 100))
  const [paymentDate, setPaymentDate] = useState(repayment.paymentDate)
  const [reference, setReference] = useState(repayment.paymentReference ?? '')
  const [reason, setReason] = useState('')
  const amountProblem = checkMoney(amount, { min: '0.01' })
  const valid = amountProblem === null && reason.trim().length >= 5 && Boolean(paymentDate)

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{t('loans.repayment.reviseTitle')}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <Alert severity="info">
            {t('loans.repayment.reviseHelp', {
              date: repayment.paymentDate,
              amount: formatOptionalMoney(repayment.amountTotal),
            })}
          </Alert>
          <MoneyField
            label={t('loans.repayment.correctAmount')}
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            error={amount !== '' && amountProblem !== null}
            fullWidth
            autoFocus
          />
          <TextField
            type="date"
            label={t('loans.fields.paymentDate')}
            value={paymentDate}
            onChange={(e) => setPaymentDate(e.target.value)}
            slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: todayInKigaliIso() } }}
            fullWidth
          />
          <TextField
            label={t('loans.fields.reference')}
            value={reference}
            onChange={(e) => setReference(e.target.value)}
            fullWidth
          />
          <TextField
            label={t('loans.repayment.reviseReason')}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            helperText={t('loans.repayment.reviseReasonHelp')}
            multiline
            minRows={2}
            slotProps={{ htmlInput: { maxLength: 500 } }}
            fullWidth
          />
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose} disabled={loading}>
          {t('common.cancel')}
        </Button>
        <Button
          variant="contained"
          disabled={loading || !valid}
          onClick={() =>
            onSubmit({ amount, paymentDate, paymentReference: reference.trim(), reason: reason.trim() })
          }
        >
          {t('loans.repayment.reviseSubmit')}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
