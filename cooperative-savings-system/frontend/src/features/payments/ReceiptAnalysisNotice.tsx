import { Alert, Box, Checkbox, Chip, FormControlLabel, Stack, Typography } from '@mui/material'
import { useTranslation } from 'react-i18next'
import type { ReceiptAnalysis } from '@/shared/types/memberPayment'
import { formatMoney } from '@/shared/utils/formatMoney'
import { toUnits } from './paymentHelpers'

/**
 * Reasons a receipt needs a person, beyond what the server reported for the receipt alone: the member's own entries are
 * compared with the receipt as they type, the same comparisons the server makes again at submission.
 */
export function receiptReviewReasons(
  analysis: ReceiptAnalysis | null,
  entered: { amount: string; reference: string },
): string[] {
  if (!analysis || !analysis.canSubmit) return []
  // the entry-dependent reasons are recomputed from what is typed now, never taken from an earlier reading
  const reasons = analysis.reviewReasons.filter((r) => r !== 'AMOUNT_MISMATCH' && r !== 'REFERENCE_MISMATCH')
  if (analysis.reading === 'COMPLETED') {
    if (amountDiffers(analysis, entered.amount)) reasons.push('AMOUNT_MISMATCH')
    if (referenceDiffers(analysis, entered.reference)) reasons.push('REFERENCE_MISMATCH')
  }
  return reasons
}

function amountDiffers(analysis: ReceiptAnalysis, amount: string): boolean {
  if (analysis.amountAmbiguous || analysis.extractedAmount == null) return false
  const typed = toUnits(amount)
  const read = toUnits(String(analysis.extractedAmount))
  return typed !== null && read !== null && typed !== read
}

function referenceDiffers(analysis: ReceiptAnalysis, reference: string): boolean {
  const normalize = (value: string) => value.replace(/[^A-Za-z0-9]/g, '').toUpperCase()
  if (!analysis.extractedReference || analysis.referenceAmbiguous || !normalize(reference)) return false
  return normalize(reference) !== normalize(analysis.extractedReference)
}

interface ReceiptAnalysisNoticeProps {
  analysis: ReceiptAnalysis | null
  loading: boolean
  failed: boolean
  reasons: string[]
  manualReview: boolean
  onManualReviewChange: (value: boolean) => void
  onUseAmount: (amount: string) => void
  enteredAmount: string
}

/**
 * What the member is told about the receipt they uploaded, before they submit. The wording is for people: the technical
 * receipt status is translated here and never shown. Nothing here says the payment succeeded: a receipt is a claim, and
 * the payment still waits for an officer to verify it.
 */
export function ReceiptAnalysisNotice({
  analysis,
  loading,
  failed,
  reasons,
  manualReview,
  onManualReviewChange,
  onUseAmount,
  enteredAmount,
}: ReceiptAnalysisNoticeProps) {
  const { t } = useTranslation()
  if (loading) {
    return (
      <Alert severity="info" data-testid="receipt-analysis-loading">
        {t('payments.receiptAnalysis.analyzing')}
      </Alert>
    )
  }
  if (failed) {
    return (
      <Alert severity="info" data-testid="receipt-analysis-unavailable-check">
        {t('payments.receiptAnalysis.analysisFailed')}
      </Alert>
    )
  }
  if (!analysis) return null

  const money = (value: string | number) => formatMoney(String(value))
  const blockedFailed = analysis.recommendedAction === 'REPLACE_RECEIPT'
  const blockedPending = analysis.recommendedAction === 'WAIT_FOR_FINAL_RECEIPT'
  const needsReview = analysis.canSubmit && reasons.length > 0
  const unavailable = analysis.reading === 'UNAVAILABLE'

  if (blockedFailed || blockedPending) {
    return (
      <Alert severity={blockedFailed ? 'error' : 'warning'} data-testid={blockedFailed ? 'receipt-analysis-failed' : 'receipt-analysis-pending'}>
        <Typography variant="body2">
          {t(blockedFailed ? 'payments.receiptAnalysis.failed' : 'payments.receiptAnalysis.pending')}
        </Typography>
        <Typography variant="caption" component="div" sx={{ mt: 0.5 }}>
          {t('payments.receiptAnalysis.replace')}
        </Typography>
      </Alert>
    )
  }

  const readable = analysis.reading === 'COMPLETED'
  const differs = amountDiffers(analysis, enteredAmount)

  return (
    <Stack spacing={1} data-testid="receipt-analysis">
      {unavailable ? (
        <Alert severity="info" data-testid="receipt-analysis-not-read">
          {t('payments.receiptAnalysis.unavailable')}
        </Alert>
      ) : needsReview ? (
        <Alert severity="warning" data-testid="receipt-analysis-review">
          <Typography variant="body2">
            {t(readable ? 'payments.receiptAnalysis.unclear' : 'payments.receiptAnalysis.unreadable')}
          </Typography>
          <Box component="ul" sx={{ m: 0, mt: 0.5, pl: 2.5 }}>
            {reasons.map((reason) => (
              <li key={reason}>
                <Typography variant="caption">{t(`payments.receiptAnalysis.reasons.${reason}`, { defaultValue: reason })}</Typography>
              </li>
            ))}
          </Box>
        </Alert>
      ) : (
        <Alert severity="success" data-testid="receipt-analysis-success">
          {t('payments.receiptAnalysis.success')}
        </Alert>
      )}

      {readable ? (
        <Stack spacing={0.5} data-testid="receipt-analysis-details">
          {analysis.amountAmbiguous ? (
            <Box data-testid="receipt-analysis-candidates">
              <Typography variant="body2">{t('payments.receiptAnalysis.amountAmbiguous')}</Typography>
              <Stack direction="row" spacing={1} sx={{ mt: 0.5, flexWrap: 'wrap' }}>
                {analysis.amountCandidates.map((candidate) => (
                  <Chip
                    key={String(candidate)}
                    label={money(candidate)}
                    onClick={() => onUseAmount(String(candidate))}
                    clickable
                    variant="outlined"
                  />
                ))}
              </Stack>
            </Box>
          ) : analysis.extractedAmount != null ? (
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
              <Typography variant="body2">
                {t('payments.receiptAnalysis.amountRead', { amount: money(analysis.extractedAmount) })}
              </Typography>
              {differs ? (
                <Chip
                  size="small"
                  color="primary"
                  variant="outlined"
                  clickable
                  label={t('payments.receiptAnalysis.useAmount')}
                  onClick={() => onUseAmount(String(analysis.extractedAmount))}
                />
              ) : null}
            </Stack>
          ) : null}
          {analysis.extractedReference ? (
            <Typography variant="body2">
              {t('payments.receiptAnalysis.referenceRead', { reference: analysis.extractedReference })}
            </Typography>
          ) : null}
          {analysis.extractedDate ? (
            <Typography variant="body2">{t('payments.receiptAnalysis.dateRead', { date: analysis.extractedDate })}</Typography>
          ) : null}
          {analysis.apparentRecipient ? (
            <Typography variant="body2">
              {t('payments.receiptAnalysis.recipientRead', { recipient: analysis.apparentRecipient })}
            </Typography>
          ) : null}
          {analysis.referenceAlreadyUsed ? (
            <Alert severity="error" data-testid="receipt-analysis-reference-used">
              {t('payments.receiptAnalysis.referenceUsed')}
            </Alert>
          ) : null}
          {analysis.recipientMatch === 'MISMATCH' ? (
            <Alert severity="warning" data-testid="receipt-analysis-recipient-mismatch">
              {t('payments.receiptAnalysis.recipientMismatch')}
            </Alert>
          ) : null}
          {analysis.referenceAmbiguous && !analysis.referenceAlreadyUsed ? (
            <Typography variant="caption" color="text.secondary">
              {t('payments.receiptAnalysis.referenceUnclear')}
            </Typography>
          ) : null}
        </Stack>
      ) : null}

      {needsReview ? (
        <Box>
          <FormControlLabel
            control={
              <Checkbox
                checked={manualReview}
                onChange={(event) => onManualReviewChange(event.target.checked)}
                slotProps={{ input: { 'aria-label': t('payments.receiptAnalysis.requestReview') } }}
              />
            }
            label={t('payments.receiptAnalysis.requestReview')}
          />
          <Typography variant="caption" color="text.secondary" component="div">
            {t('payments.receiptAnalysis.reviewHelp')}
          </Typography>
          <Typography variant="caption" color="text.secondary" component="div">
            {t('payments.receiptAnalysis.replace')}
          </Typography>
        </Box>
      ) : null}
    </Stack>
  )
}
