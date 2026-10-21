import { Alert, Box, Button, Chip, Stack, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { useAppSelector } from '@/app/store/hooks'
import { extractReceipt, fetchReceiptExtractions, reviewReceiptExtraction } from '@/shared/api/paymentVerification'
import { LoadingState } from '@/shared/components/LoadingState'
import type { MemberPayment } from '@/shared/types/memberPayment'
import type { ExtractedField, ReceiptExtraction } from '@/shared/types/paymentVerification'
import { formatMoney } from '@/shared/utils/formatMoney'
import { ErrorNotice } from './PaymentParts'
import { VerificationCapabilitiesNotice } from './VerificationCapabilitiesNotice'

interface ReceiptExtractionSectionProps {
  cooperativeId: string
  payment: MemberPayment
}

const levelColor = (level: ExtractedField['level']) =>
  level === 'HIGH' ? 'success' : level === 'MEDIUM' ? 'warning' : level === 'LOW' ? 'error' : 'default'

const KNOWN_FAILURES = [
  'OCR_ENGINE_UNAVAILABLE',
  'PDF_NOT_SUPPORTED',
  'UNSUPPORTED_FORMAT',
  'CONTENT_TYPE_MISMATCH',
  'NO_TEXT_FOUND',
  'OCR_FAILED',
  'OCR_TIMEOUT',
  'PDF_UNREADABLE',
  'PDF_ENCRYPTED',
  'PDF_TOO_MANY_PAGES',
  'PDF_NO_TEXT',
  'PDF_TIMEOUT',
  'PDF_BUSY',
] as const

export function percent(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—'
  const number = Number(value)
  return Number.isFinite(number) ? `${Math.round(number * 100)}%` : '—'
}

/**
 * What OCR read from the uploaded receipt (officers). It is advisory only: it is never proof of payment, never verifies
 * or posts anything, and every uncertain reading is labelled. Differences from what the payer claimed are listed so an
 * officer can look closer; they can only ever add doubt.
 */
export function ReceiptExtractionSection({ cooperativeId, payment }: ReceiptExtractionSectionProps) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const user = useAppSelector((s) => s.auth.user)
  const key = ['member-payments', cooperativeId, 'receipt', payment.id]
  const hasReceipt = Boolean(payment.evidenceFileKey)
  const query = useQuery({
    queryKey: key,
    queryFn: () => fetchReceiptExtractions(cooperativeId, payment.id),
    enabled: hasReceipt,
  })
  const refresh = async () => {
    await queryClient.invalidateQueries({ queryKey: key })
    await queryClient.invalidateQueries({ queryKey: ['member-payments', cooperativeId, 'verification', payment.id] })
  }
  const extract = useMutation({ mutationFn: () => extractReceipt(cooperativeId, payment.id), onSuccess: refresh })
  const review = useMutation({
    mutationFn: (extractionId: string) => reviewReceiptExtraction(cooperativeId, payment.id, extractionId),
    onSuccess: refresh,
  })
  if (!hasReceipt) return null

  const items = query.data ?? []
  const own = user?.id === payment.memberUserId

  const fieldValue = (f: ExtractedField): string => {
    if (f.name === 'amount') return formatMoney(f.value, { currency: payment.currency })
    if (f.name === 'provider') return t(`payments.method.${f.value}`, { defaultValue: f.value })
    return f.value
  }

  const failureText = (e: ReceiptExtraction): string => {
    const code = e.failureCode ?? ''
    return (KNOWN_FAILURES as readonly string[]).includes(code)
      ? t(`payments.receipt.failure.${code}`)
      : t('payments.receipt.failure.generic')
  }

  return (
    <Box data-testid="receipt-extraction">
      <Typography variant="subtitle2" gutterBottom>
        {t('payments.receipt.title')}
      </Typography>
      <Stack spacing={1}>
        <Alert severity="info" data-testid="receipt-not-proof">
          {t('payments.receipt.notProof')}
        </Alert>
        <VerificationCapabilitiesNotice cooperativeId={cooperativeId} compact />
        {query.isLoading ? <LoadingState variant="skeleton" rows={2} /> : null}
        {query.isError ? <ErrorNotice error={query.error} /> : null}
        {extract.isError ? <ErrorNotice error={extract.error} /> : null}
        {review.isError ? <ErrorNotice error={review.error} /> : null}

        {items.map((e) => (
          <Box key={e.id} sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 1, p: 1 }} data-testid="receipt-extraction-item">
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 0.5 }}>
              <Chip
                size="small"
                color={e.status === 'COMPLETED' ? 'success' : e.status === 'FAILED' ? 'error' : 'default'}
                label={t(`payments.receipt.status.${e.status}`)}
              />
              {e.status === 'COMPLETED' ? (
                <Typography variant="caption" color="text.secondary" data-testid="receipt-mean-confidence">
                  {t('payments.receipt.meanConfidence', { value: percent(e.meanConfidence) })}
                </Typography>
              ) : null}
              <Typography variant="caption" color="text.secondary">
                {t('payments.receipt.attempts', { count: e.attempts })}
              </Typography>
            </Stack>

            {e.status !== 'COMPLETED' ? (
              <Alert severity={e.status === 'FAILED' ? 'warning' : 'info'} sx={{ mt: 1 }} data-testid="receipt-failure">
                {failureText(e)}
              </Alert>
            ) : null}

            {e.status === 'COMPLETED' ? (
              <Stack spacing={0.5} sx={{ mt: 1 }} data-testid="receipt-fields">
                {e.fields.length === 0 ? (
                  <Typography variant="body2" color="text.secondary">
                    {t('payments.receipt.noFields')}
                  </Typography>
                ) : null}
                {e.fields.map((f) => (
                  <Stack key={f.name} direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 0.5 }}>
                    <Typography variant="body2" sx={{ minWidth: 110, fontWeight: 600 }}>
                      {t(`payments.receipt.field.${f.name}`)}
                    </Typography>
                    <Typography variant="body2">{fieldValue(f)}</Typography>
                    <Chip
                      size="small"
                      variant="outlined"
                      color={levelColor(f.level)}
                      label={`${t(`payments.receipt.level.${f.level}`)} ${f.confidence !== null ? percent(f.confidence) : ''}`.trim()}
                    />
                  </Stack>
                ))}
              </Stack>
            ) : null}

            {e.duplicateReceipt ? (
              <Alert severity="warning" sx={{ mt: 1 }} data-testid="receipt-duplicate">
                {t('payments.receipt.duplicate')}
              </Alert>
            ) : null}
            {e.discrepancies.length > 0 ? (
              <Stack direction="row" spacing={0.5} sx={{ mt: 1, flexWrap: 'wrap', rowGap: 0.5 }} data-testid="receipt-discrepancies">
                {e.discrepancies.map((code) => (
                  <Chip key={code} size="small" color="warning" label={t(`payments.receipt.discrepancy.${code}`, { defaultValue: code })} />
                ))}
              </Stack>
            ) : e.status === 'COMPLETED' ? (
              <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 1 }}>
                {t('payments.receipt.noDiscrepancy')}
              </Typography>
            ) : null}

            {e.reviewedBy ? (
              <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 1 }} data-testid="receipt-reviewed">
                {t('payments.receipt.reviewedAt', { when: e.reviewedAt ? new Date(e.reviewedAt).toLocaleString() : '' })}
              </Typography>
            ) : e.status === 'COMPLETED' && !own ? (
              <Box sx={{ mt: 1 }}>
                <Button size="small" variant="outlined" onClick={() => review.mutate(e.id)} disabled={review.isPending} sx={{ minHeight: 44 }}>
                  {t('payments.receipt.markReviewed')}
                </Button>
              </Box>
            ) : null}
          </Box>
        ))}

        <Box>
          <Button
            variant="outlined"
            onClick={() => extract.mutate()}
            disabled={extract.isPending}
            sx={{ minHeight: 44 }}
            data-testid="receipt-read"
          >
            {extract.isPending
              ? t('payments.receipt.reading')
              : items.length > 0
                ? t('payments.receipt.readAgain')
                : t('payments.receipt.read')}
          </Button>
        </Box>
      </Stack>
    </Box>
  )
}
