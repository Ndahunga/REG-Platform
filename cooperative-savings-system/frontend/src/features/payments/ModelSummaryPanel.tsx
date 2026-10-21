import { Alert, Box, Paper, Stack, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { fetchModelSummary } from '@/shared/api/rotationPayouts'
import { getErrorMessage } from '@/shared/api/client'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { formatMoney } from '@/shared/utils/formatMoney'

interface ModelSummaryPanelProps {
  cooperativeId: string
}

function Figure({ label, value, hint, testId }: { label: string; value: string | number; hint?: string; testId: string }) {
  return (
    <Paper elevation={0} sx={{ p: 2, border: '1px solid', borderColor: 'divider', flex: '1 1 220px' }}>
      <Typography variant="caption" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="h6" data-testid={testId}>
        {formatMoney(value)}
      </Typography>
      {hint ? (
        <Typography variant="caption" color="text.secondary">
          {hint}
        </Typography>
      ) : null}
    </Paper>
  )
}

/**
 * The scheme's money by kind. Expected contributions are expectations, never cash. Rotating collections are shown
 * apart from retained capital; what recipients are still owed is a liability, and available funds are also shown after
 * setting it aside. For an Accumulating scheme every rotating figure is zero and is not shown.
 */
export function ModelSummaryPanel({ cooperativeId }: ModelSummaryPanelProps) {
  const { t } = useTranslation()
  const query = useQuery({
    queryKey: ['model-summary', cooperativeId],
    queryFn: () => fetchModelSummary(cooperativeId),
  })
  if (query.isLoading) return <LoadingState variant="skeleton" rows={4} />
  if (query.isError) {
    return <ErrorState message={getErrorMessage(query.error, t('errors.generic'))} onRetry={() => void query.refetch()} />
  }
  const s = query.data
  if (!s) return null
  const rotating = s.operatingModel === 'ROTATING' || s.operatingModel === 'HYBRID'

  return (
    <Stack spacing={2} data-testid="model-summary">
      <Alert severity="info">{t('summary.explain')}</Alert>
      <Typography variant="subtitle2">
        {s.operatingModel ? t(`summary.model.${s.operatingModel}`) : t('summary.model.NONE')}
      </Typography>
      {rotating && (Number(s.unclassifiedCollections) > 0 || Number(s.unresolvedRotatingCollections) > 0) ? (
        <Alert severity="warning" data-testid="summary-unresolved-alert">
          {t('summary.unresolvedAlert', {
            unclassified: formatMoney(s.unclassifiedCollections),
            rotating: formatMoney(s.unresolvedRotatingCollections),
          })}
        </Alert>
      ) : null}
      <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 2 }}>
        <Figure testId="summary-expected" label={t('summary.expected')} value={s.expectedContributions} hint={t('summary.expectedHint')} />
        <Figure testId="summary-collected" label={t('summary.collected')} value={s.postedCollections} />
        <Figure testId="summary-arrears" label={t('summary.arrears')} value={s.contributionArrears} hint={t('summary.arrearsHint')} />
        <Figure testId="summary-retained" label={t('summary.retained')} value={s.retainedCollections} />
        {rotating ? (
          <>
            <Figure testId="summary-rotating" label={t('summary.rotatingCollected')} value={s.rotatingCollected} hint={t('summary.rotatingCollectedHint')} />
            <Figure
              testId="summary-reconciliation"
              label={t('summary.reconciliation')}
              value={s.rotatingRequiringReconciliation}
              hint={t('summary.reconciliationHint')}
            />
            <Figure testId="summary-rotating-expected" label={t('summary.rotatingExpected')} value={s.rotatingExpected} />
            <Figure testId="summary-rotating-arrears" label={t('summary.rotatingArrears')} value={s.rotatingArrears} />
            <Figure testId="summary-owed" label={t('summary.owed')} value={s.recipientEntitlementsOwed} hint={t('summary.owedHint')} />
            <Figure
              testId="summary-total-liabilities"
              label={t('summary.totalLiabilities')}
              value={s.totalRotatingLiabilities}
              hint={t('summary.totalLiabilitiesHint')}
            />
            <Figure
              testId="summary-unresolved-rotating"
              label={t('summary.unresolvedRotating')}
              value={s.unresolvedRotatingCollections}
              hint={t('summary.unresolvedRotatingHint')}
            />
            <Figure
              testId="summary-unclassified"
              label={t('summary.unclassified')}
              value={s.unclassifiedCollections}
              hint={t('summary.unclassifiedHint')}
            />
            <Figure testId="summary-paid-out" label={t('summary.paidOut')} value={s.payoutsDisbursed} />
            <Figure testId="summary-open-payouts" label={t('summary.openPayouts')} value={s.payoutsOpen} />
          </>
        ) : null}
        <Figure testId="summary-available" label={t('summary.available')} value={s.availableFunds} />
        {rotating ? (
          <Figure testId="summary-available-after" label={t('summary.availableAfter')} value={s.availableAfterEntitlements} />
        ) : null}
      </Box>
    </Stack>
  )
}
