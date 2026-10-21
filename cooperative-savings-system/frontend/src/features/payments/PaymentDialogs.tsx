import {
  Alert,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { useMutation } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  completePaymentEvidence,
  postPayment,
  rejectPayment,
  retryPaymentPosting,
  verifyPayment,
  withdrawPayment,
} from '@/shared/api/memberPayments'
import { FinancialActionButton } from '@/shared/components/FinancialActionButton'
import type {
  MemberPayment,
  PaymentPosting,
  PaymentVerifyRequest,
  VerificationMethod,
} from '@/shared/types/memberPayment'
import { AllocationLines, ErrorNotice } from './PaymentParts'
import { isIndeterminateFailure, verificationMethodsFor, verificationNeedsReference } from './paymentHelpers'
import { formatMoney } from '@/shared/utils/formatMoney'

// ------------------------------------------------------------------ verification

interface VerifyPaymentDialogProps {
  cooperativeId: string
  payment: MemberPayment
  /** 'verify' for a payment under review; 'evidence' completes a legacy VERIFIED payment that has none. */
  mode: 'verify' | 'evidence'
  onClose: () => void
  onDone: () => void
}

/**
 * Records HOW the receipt of funds was confirmed (the exact backend contract: method, written basis, and an external
 * reference for everything except cash). An uploaded receipt or OCR text is never proof, and verifying does not post.
 */
export function VerifyPaymentDialog({ cooperativeId, payment, mode, onClose, onDone }: VerifyPaymentDialogProps) {
  const { t } = useTranslation()
  const methods = verificationMethodsFor(payment.paymentMethod)
  const [method, setMethod] = useState<VerificationMethod>(methods[0])
  const [basis, setBasis] = useState('')
  const [reference, setReference] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [attempted, setAttempted] = useState(false)

  const needsReference = verificationNeedsReference(method)
  const basisMissing = basis.trim() === ''
  const referenceMissing = needsReference && reference.trim() === ''

  const mutation = useMutation({
    mutationFn: (payload: PaymentVerifyRequest) =>
      mode === 'verify'
        ? verifyPayment(cooperativeId, payment.id, payload)
        : completePaymentEvidence(cooperativeId, payment.id, payload),
    onSuccess: () => onDone(),
  })

  const submit = () => {
    setAttempted(true)
    if (basisMissing || referenceMissing || !confirmed || mutation.isPending) return
    mutation.mutate({
      method,
      basis: basis.trim(),
      reference: needsReference ? reference.trim() : reference.trim() || undefined,
    })
  }

  return (
    <Dialog open onClose={mutation.isPending ? undefined : onClose} fullWidth maxWidth="sm" aria-labelledby="verify-title">
      <DialogTitle id="verify-title">
        {mode === 'verify' ? t('payments.verification.title') : t('payments.verification.evidenceTitle')}
      </DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <Alert severity="info">{t('payments.verification.explain')}</Alert>
          <Typography variant="body2">
            {t('payments.verification.summary', {
              amount: formatMoney(payment.amount, { currency: payment.currency }),
              method: t(`payments.method.${payment.paymentMethod}`),
            })}
          </Typography>
          <TextField
            select
            label={t('payments.verification.method')}
            value={method}
            onChange={(e) => setMethod(e.target.value as VerificationMethod)}
          >
            {methods.map((m) => (
              <MenuItem key={m} value={m}>
                {t(`payments.verification.methods.${m}`)}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            label={t('payments.verification.basis')}
            value={basis}
            onChange={(e) => setBasis(e.target.value)}
            multiline
            minRows={2}
            required
            error={attempted && basisMissing}
            helperText={attempted && basisMissing ? t('payments.verification.basisRequired') : t('payments.verification.basisHint')}
            slotProps={{ htmlInput: { maxLength: 1000 } }}
          />
          <TextField
            label={t('payments.verification.reference')}
            value={reference}
            onChange={(e) => setReference(e.target.value)}
            required={needsReference}
            error={attempted && referenceMissing}
            helperText={
              attempted && referenceMissing
                ? t('payments.verification.referenceRequired')
                : needsReference
                  ? t('payments.verification.referenceHint')
                  : t('payments.verification.referenceOptional')
            }
            slotProps={{ htmlInput: { maxLength: 128 } }}
          />
          <FormControlLabel
            control={<Checkbox checked={confirmed} onChange={(_, v) => setConfirmed(v)} />}
            label={t('payments.verification.confirm')}
          />
          {attempted && !confirmed ? (
            <Typography variant="caption" color="error" role="alert">
              {t('payments.verification.confirmRequired')}
            </Typography>
          ) : null}
          {mutation.isError ? <ErrorNotice error={mutation.error} /> : null}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose} disabled={mutation.isPending}>
          {t('common.cancel')}
        </Button>
        <FinancialActionButton variant="contained" onClick={submit} disabled={mutation.isPending} sx={{ minHeight: 44 }}>
          {mutation.isPending
            ? t('payments.verification.verifying')
            : mode === 'verify'
              ? t('payments.verification.submit')
              : t('payments.verification.submitEvidence')}
        </FinancialActionButton>
      </DialogActions>
    </Dialog>
  )
}

// ------------------------------------------------------------------ rejection

interface RejectPaymentDialogProps {
  cooperativeId: string
  payment: MemberPayment
  onClose: () => void
  onDone: () => void
}

export function RejectPaymentDialog({ cooperativeId, payment, onClose, onDone }: RejectPaymentDialogProps) {
  const { t } = useTranslation()
  const [reason, setReason] = useState('')
  const [attempted, setAttempted] = useState(false)
  const mutation = useMutation({
    mutationFn: (value: string) => rejectPayment(cooperativeId, payment.id, value),
    onSuccess: () => onDone(),
  })
  const missing = reason.trim() === ''
  return (
    <Dialog open onClose={mutation.isPending ? undefined : onClose} fullWidth maxWidth="sm" aria-labelledby="reject-title">
      <DialogTitle id="reject-title">{t('payments.reject.title')}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <Alert severity="warning">{t('payments.reject.explain')}</Alert>
          <TextField
            label={t('payments.reject.reason')}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            multiline
            minRows={2}
            required
            error={attempted && missing}
            helperText={attempted && missing ? t('payments.reject.reasonRequired') : undefined}
            slotProps={{ htmlInput: { maxLength: 2000 } }}
          />
          {mutation.isError ? <ErrorNotice error={mutation.error} /> : null}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose} disabled={mutation.isPending}>
          {t('common.cancel')}
        </Button>
        <Button
          color="error"
          variant="contained"
          disabled={mutation.isPending}
          onClick={() => {
            setAttempted(true)
            if (!missing && !mutation.isPending) mutation.mutate(reason.trim())
          }}
          sx={{ minHeight: 44 }}
        >
          {t('payments.reject.submit')}
        </Button>
      </DialogActions>
    </Dialog>
  )
}

// ------------------------------------------------------------------ withdrawal by the payer

interface WithdrawPaymentDialogProps {
  cooperativeId: string
  payment: MemberPayment
  onClose: () => void
  onDone: () => void
}

/** The payer takes back a payment that nobody has started to review. Nothing was posted, so nothing needs undoing. */
export function WithdrawPaymentDialog({ cooperativeId, payment, onClose, onDone }: WithdrawPaymentDialogProps) {
  const { t } = useTranslation()
  const mutation = useMutation({
    mutationFn: () => withdrawPayment(cooperativeId, payment.id),
    onSuccess: () => onDone(),
  })
  return (
    <Dialog open onClose={mutation.isPending ? undefined : onClose} fullWidth maxWidth="sm" aria-labelledby="withdraw-title">
      <DialogTitle id="withdraw-title">{t('payments.withdraw.title')}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <Alert severity="warning">{t('payments.withdraw.explain')}</Alert>
          {mutation.isError ? <ErrorNotice error={mutation.error} /> : null}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose} disabled={mutation.isPending}>
          {t('common.cancel')}
        </Button>
        <Button color="error" variant="contained" disabled={mutation.isPending} onClick={() => mutation.mutate()} sx={{ minHeight: 44 }}>
          {t('payments.withdraw.confirm')}
        </Button>
      </DialogActions>
    </Dialog>
  )
}

// ------------------------------------------------------------------ posting and retry

interface PostPaymentDialogProps {
  cooperativeId: string
  payment: MemberPayment
  posting: PaymentPosting | undefined
  /** 'retry' is the manual retry of a failed posting; it needs a deliberate confirmation of its own. */
  mode: 'post' | 'retry'
  onClose: () => void
  /** Called after any response or a lost connection, so the caller refetches the authoritative state. */
  onSettled: (outcome: 'posted' | 'failed' | 'unknown') => void
}

/**
 * The separate, explicit posting step. Posting changes the member's contribution and loan accounts, so the officer
 * must confirm. The button is disabled while a request is pending (no duplicate clicks), nothing is updated
 * optimistically, and a lost connection is treated as "unknown": the caller refetches before any retry is offered.
 */
export function PostPaymentDialog({ cooperativeId, payment, posting, mode, onClose, onSettled }: PostPaymentDialogProps) {
  const { t } = useTranslation()
  const [confirmed, setConfirmed] = useState(false)
  const [attempted, setAttempted] = useState(false)

  const mutation = useMutation({
    mutationFn: () =>
      mode === 'retry' ? retryPaymentPosting(cooperativeId, payment.id) : postPayment(cooperativeId, payment.id),
    onSuccess: () => onSettled('posted'),
    onError: (error) => onSettled(isIndeterminateFailure(error) ? 'unknown' : 'failed'),
  })

  const submit = () => {
    setAttempted(true)
    if (!confirmed || mutation.isPending) return
    mutation.mutate()
  }

  return (
    <Dialog open onClose={mutation.isPending ? undefined : onClose} fullWidth maxWidth="sm" aria-labelledby="post-title">
      <DialogTitle id="post-title">{mode === 'retry' ? t('payments.posting.retryTitle') : t('payments.posting.title')}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <Alert severity="warning">{t('payments.posting.explain')}</Alert>
          <Typography variant="body2">
            {t('payments.posting.summary', { amount: formatMoney(payment.amount, { currency: payment.currency }) })}
          </Typography>
          <AllocationLines allocations={payment.allocations} currency={payment.currency} />
          {mode === 'retry' && posting?.lastPostingFailureCode ? (
            <ErrorNotice code={posting.lastPostingFailureCode} />
          ) : null}
          <FormControlLabel
            control={<Checkbox checked={confirmed} onChange={(_, v) => setConfirmed(v)} />}
            label={mode === 'retry' ? t('payments.posting.confirmRetry') : t('payments.posting.confirm')}
          />
          {attempted && !confirmed ? (
            <Typography variant="caption" color="error" role="alert">
              {t('payments.posting.confirmRequired')}
            </Typography>
          ) : null}
          {mutation.isError ? <ErrorNotice error={mutation.error} /> : null}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose} disabled={mutation.isPending}>
          {mutation.isError ? t('common.close') : t('common.cancel')}
        </Button>
        {mutation.isError ? null : (
          <FinancialActionButton variant="contained" onClick={submit} disabled={mutation.isPending} sx={{ minHeight: 44 }}>
            {mutation.isPending
              ? t('payments.posting.posting')
              : mode === 'retry'
                ? t('payments.posting.retrySubmit')
                : t('payments.posting.submit')}
          </FinancialActionButton>
        )}
      </DialogActions>
    </Dialog>
  )
}
