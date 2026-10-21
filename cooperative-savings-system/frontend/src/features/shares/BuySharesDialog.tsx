import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { getErrorMessage } from '@/shared/api/client'
import { uploadCooperativeFile } from '@/shared/api/files'
import { fetchShareValuation, submitSharePurchase } from '@/shared/api/shares'
import { LoadingState } from '@/shared/components/LoadingState'
import { formatMoney } from '@/shared/utils/formatMoney'
import { exceedsLength, isFutureIsoDate, isValidIsoDate } from '@/shared/utils/formValidation'
import { todayInKigaliIso } from '@/shared/utils/rwandaCooperative'

interface BuySharesDialogProps {
  open: boolean
  cooperativeId: string
  memberUserId?: string
  onClose: () => void
}

export function BuySharesDialog({
  open,
  cooperativeId,
  memberUserId,
  onClose,
}: BuySharesDialogProps) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const { enqueueSnackbar } = useSnackbar()
  const [quantity, setQuantity] = useState('1')
  const [paymentDate, setPaymentDate] = useState(() => new Date().toISOString().slice(0, 10))
  const [paymentReference, setPaymentReference] = useState('')
  const [evidenceFileKey, setEvidenceFileKey] = useState('')
  const [uploadedName, setUploadedName] = useState<string | null>(null)
  const [notes, setNotes] = useState('')
  const [submittedStatus, setSubmittedStatus] = useState<string | null>(null)

  const valuationQuery = useQuery({
    queryKey: ['shares', 'valuation', cooperativeId],
    queryFn: () => fetchShareValuation(cooperativeId),
    enabled: open && Boolean(cooperativeId),
  })

  const valuation = valuationQuery.data
  const currency = valuation?.currency || 'RWF'
  const price = Number(valuation?.currentShareValue ?? 0)
  const shares = Number(quantity)
  const validQuantity = Number.isInteger(shares) && shares >= 1 && shares <= 1000
  // Backend: paymentDate @NotNull @PastOrPresent, reference @Size(128), notes @Size(2000).
  const dateProblem = !paymentDate.trim()
    ? t('contributions.submit.dateRequired')
    : !isValidIsoDate(paymentDate)
      ? t('contributions.validation.dateInvalid')
      : isFutureIsoDate(paymentDate, todayInKigaliIso())
        ? t('contributions.validation.dateFuture')
        : null
  const validDate = dateProblem === null
  const referenceTooLong = exceedsLength(paymentReference, 128)
  const notesTooLong = exceedsLength(notes, 2000)
  const hasProof = Boolean(evidenceFileKey.trim())
  const total = validQuantity ? price * shares : 0

  const uploadMutation = useMutation({
    mutationFn: (file: File) =>
      uploadCooperativeFile(cooperativeId, file, 'SHARE_PURCHASE_EVIDENCE'),
    onSuccess: (file) => {
      setEvidenceFileKey(file.storageKey)
      setUploadedName(file.originalFilename)
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('shares.buy.uploadFailed')), { variant: 'error' })
    },
  })

  const resetForm = () => {
    setQuantity('1')
    setPaymentDate(todayInKigaliIso())
    setPaymentReference('')
    setEvidenceFileKey('')
    setUploadedName(null)
    setNotes('')
    setSubmittedStatus(null)
  }

  const submitMutation = useMutation({
    mutationFn: () =>
      submitSharePurchase(cooperativeId, {
        numberOfShares: shares,
        memberUserId,
        paymentDate: paymentDate.trim(),
        paymentReference: paymentReference.trim() || undefined,
        evidenceFileKey: evidenceFileKey.trim(),
        notes: notes.trim() || undefined,
      }),
    onSuccess: (purchase) => {
      enqueueSnackbar(t('shares.buy.submitSuccess', { status: purchase.status }), {
        variant: 'success',
      })
      void queryClient.invalidateQueries({ queryKey: ['shares'] })
      void queryClient.invalidateQueries({ queryKey: ['members'] })
      void queryClient.invalidateQueries({ queryKey: ['dashboard'] })
      void queryClient.invalidateQueries({ queryKey: ['notifications-pending-approvals'] })
      setQuantity('1')
      setSubmittedStatus(purchase.status)
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const helper = useMemo(() => {
    if (!quantity.trim()) return t('shares.buy.quantityRequired')
    if (!validQuantity) return t('shares.buy.quantityInvalid')
    return ' '
  }, [quantity, t, validQuantity])

  return (
    <Dialog
      open={open}
      onClose={() => {
        if (uploadMutation.isPending || submitMutation.isPending) return
        resetForm()
        onClose()
      }}
      fullWidth
      maxWidth="sm"
    >
      <DialogTitle>{t('shares.buy.title')}</DialogTitle>
      <DialogContent>
        {valuationQuery.isLoading ? <LoadingState variant="skeleton" rows={3} /> : null}
        {valuationQuery.isError ? (
          <Alert severity="error">{getErrorMessage(valuationQuery.error)}</Alert>
        ) : null}
        {submittedStatus ? (
          <Alert severity="info" sx={{ mt: 1 }}>
            {t('shares.buy.pendingNotice', {
              status: t(`shares.status.${submittedStatus}`, {
                defaultValue: submittedStatus,
              }),
            })}
          </Alert>
        ) : null}
        {valuation ? (
          <Stack spacing={2} sx={{ mt: 1 }}>
            <Typography>
              {t('shares.buy.currentPrice')}:{' '}
              <strong>{formatMoney(valuation.currentShareValue, { currency })}</strong>
            </Typography>
            {!valuation.canPurchase ? (
              <Alert severity="warning">
                {valuation.purchaseBlockedReason || t('shares.buy.cannotPurchase')}
              </Alert>
            ) : null}
            <TextField
              label={t('shares.buy.numberOfShares')}
              value={quantity}
              onChange={(e) => setQuantity(e.target.value)}
              type="number"
              slotProps={{ htmlInput: { min: 1, max: 1000, step: 1 } }}
              error={Boolean(quantity) && !validQuantity}
              helperText={helper}
              fullWidth
            />
            <Typography>
              {t('shares.buy.totalAmount')}:{' '}
              <strong>{formatMoney(total, { currency })}</strong>
            </Typography>
            <TextField
              type="date"
              label={t('contributions.fields.paymentDate')}
              value={paymentDate}
              onChange={(e) => setPaymentDate(e.target.value)}
              slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: todayInKigaliIso() } }}
              error={!validDate}
              helperText={dateProblem ?? undefined}
              required
              fullWidth
            />
            <TextField
              label={t('contributions.fields.reference')}
              value={paymentReference}
              onChange={(e) => setPaymentReference(e.target.value)}
              error={referenceTooLong}
              helperText={referenceTooLong ? t('contributions.validation.referenceTooLong') : undefined}
              fullWidth
            />
            <Stack spacing={1}>
              <Typography variant="subtitle2">
                {t('contributions.submit.proof')} *
              </Typography>
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
              <Typography
                variant="body2"
                color={hasProof ? 'text.secondary' : 'error'}
              >
                {uploadedName || evidenceFileKey || t('shares.buy.proofRequired')}
              </Typography>
            </Stack>
            <TextField
              label={t('contributions.fields.notes')}
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              error={notesTooLong}
              helperText={notesTooLong ? t('contributions.validation.notesTooLong') : undefined}
              fullWidth
              multiline
              minRows={2}
            />
          </Stack>
        ) : null}
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button
          disabled={uploadMutation.isPending || submitMutation.isPending}
          onClick={() => {
            resetForm()
            onClose()
          }}
        >
          {submittedStatus ? t('common.close') : t('common.cancel')}
        </Button>
        <Button
          variant="contained"
          disabled={
            Boolean(submittedStatus) ||
            !valuation?.canPurchase ||
            !validQuantity ||
            !validDate ||
            referenceTooLong ||
            notesTooLong ||
            !hasProof ||
            submitMutation.isPending
          }
          onClick={() => submitMutation.mutate()}
        >
          {t('shares.buy.submit')}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
