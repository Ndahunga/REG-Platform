import { Alert, Stack } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { fetchVerificationCapabilities } from '@/shared/api/paymentVerification'

/**
 * States, before anyone tries, what this deployment can read and confirm: whether receipt reading is installed, that PDF
 * receipts are not read, whether authoritative confirmation is required, and whether automatic provider confirmation
 * exists. Nothing is implied: a missing capability is shown as missing.
 */
export function VerificationCapabilitiesNotice({ cooperativeId, compact }: { cooperativeId: string; compact?: boolean }) {
  const { t } = useTranslation()
  const query = useQuery({
    queryKey: ['verification-capabilities', cooperativeId],
    queryFn: () => fetchVerificationCapabilities(cooperativeId),
    staleTime: 60_000,
  })
  const c = query.data
  if (!c) return null
  return (
    <Stack spacing={1} data-testid="verification-capabilities">
      {c.ocrAvailable ? (
        <Alert severity="info" data-testid="cap-ocr">
          {t('payments.capabilities.ocrAvailable', { types: 'PNG, JPEG', mb: Math.round(c.maxReceiptBytes / 1024 / 1024), seconds: c.ocrTimeoutSeconds })}
        </Alert>
      ) : (
        <Alert severity="warning" data-testid="cap-ocr">
          {t('payments.capabilities.ocrUnavailable')}
        </Alert>
      )}
      {!c.pdfSupported ? (
        <Alert severity="info" data-testid="cap-pdf">
          {t('payments.capabilities.pdfNotSupported')}
        </Alert>
      ) : null}
      {compact ? null : (
        <>
          <Alert severity={c.strictVerification ? 'success' : 'warning'} data-testid="cap-strict">
            {c.strictVerification ? t('payments.capabilities.strictOn') : t('payments.capabilities.strictOff')}
          </Alert>
          <Alert severity={c.providerCallbackConfigured ? 'info' : 'warning'} data-testid="cap-provider">
            {c.providerCallbackConfigured ? t('payments.capabilities.providerCallback') : t('payments.capabilities.providerUnavailable')}
          </Alert>
        </>
      )}
    </Stack>
  )
}
