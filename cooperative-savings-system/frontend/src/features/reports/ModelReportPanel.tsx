import { Alert, Box, Button, Paper, Stack, TextField, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { getErrorMessage } from '@/shared/api/client'
import { fetchModelReport } from '@/shared/api/historicalTransactions'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import type { ModelReport } from '@/shared/types/historicalTransactions'
import { formatMoney } from '@/shared/utils/formatMoney'

interface ModelReportPanelProps {
  cooperativeId: string
}

function Figure({ label, value, testId, hint, count }: { label: string; value: string | number; testId: string; hint?: string; count?: boolean }) {
  return (
    <Paper elevation={0} sx={{ p: 2, border: '1px solid', borderColor: 'divider', flex: '1 1 200px' }}>
      <Typography variant="caption" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="h6" data-testid={testId}>
        {count ? String(value) : formatMoney(value)}
      </Typography>
      {hint ? (
        <Typography variant="caption" color="text.secondary">
          {hint}
        </Typography>
      ) : null}
    </Paper>
  )
}

function Section({ title, testId, children }: { title: string; testId: string; children: React.ReactNode }) {
  return (
    <Box data-testid={testId}>
      <Typography variant="subtitle1" gutterBottom sx={{ fontWeight: 600 }}>
        {title}
      </Typography>
      <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 2 }}>{children}</Box>
    </Box>
  )
}

/**
 * The financial report of the scheme in the terms of its operating model, from posted records only. Earnings are shown
 * as income less expenses; distributing them is reported unavailable until a policy is approved, and nothing here ever
 * calculates a dividend. Money whose class or original rotation period is unresolved is shown apart, never as capital.
 */
export function ModelReportPanel({ cooperativeId }: ModelReportPanelProps) {
  const { t } = useTranslation()
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [applied, setApplied] = useState<{ from: string; to: string }>({ from: '', to: '' })
  const invalidRange = Boolean(from && to && from > to)
  const query = useQuery({
    queryKey: ['model-report', cooperativeId, applied.from, applied.to],
    queryFn: () => fetchModelReport(cooperativeId, applied.from || undefined, applied.to || undefined),
  })

  const filters = (
    <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ alignItems: { sm: 'center' } }}>
      <TextField size="small" type="date" label={t('modelReport.from')} value={from} onChange={(e) => setFrom(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
      <TextField size="small" type="date" label={t('modelReport.to')} value={to} onChange={(e) => setTo(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
      <Button variant="outlined" disabled={invalidRange} onClick={() => setApplied({ from, to })} sx={{ minHeight: 44 }} data-testid="mr-apply">
        {t('modelReport.apply')}
      </Button>
      {invalidRange ? <Typography variant="caption" color="error">{t('modelReport.invalidRange')}</Typography> : null}
    </Stack>
  )

  if (query.isLoading) return <LoadingState variant="skeleton" rows={4} />
  if (query.isError) {
    return <ErrorState message={getErrorMessage(query.error, t('errors.generic'))} onRetry={() => void query.refetch()} />
  }
  const r: ModelReport | undefined = query.data
  if (!r) return null
  const model = r.operatingModel
  const rotating = model === 'ROTATING' || model === 'HYBRID'
  const unresolved = Number(r.position.unclassifiedCollections) > 0 || Number(r.position.unresolvedRotatingCollections) > 0

  return (
    <Stack spacing={3} data-testid="model-report">
      <Alert severity="info">{t('modelReport.explain')}</Alert>
      {filters}
      <Typography variant="subtitle2" data-testid="mr-model">
        {model ? t(`summary.model.${model}`) : t('summary.model.NONE')} · {r.from} → {r.to}
      </Typography>

      {unresolved ? (
        <Alert severity="warning" data-testid="mr-unresolved">
          {t('modelReport.unresolved', {
            unclassified: formatMoney(r.position.unclassifiedCollections),
            rotating: formatMoney(r.position.unresolvedRotatingCollections),
          })}
        </Alert>
      ) : null}

      <Section title={t('modelReport.sections.contributions')} testId="mr-contributions">
        <Figure testId="mr-expected" label={t('summary.expected')} value={r.position.expectedContributions} hint={t('summary.expectedHint')} />
        <Figure testId="mr-collected" label={t('summary.collected')} value={r.position.postedCollections} />
        <Figure testId="mr-arrears" label={t('summary.arrears')} value={r.position.contributionArrears} />
        <Figure testId="mr-period-collected" label={t('modelReport.periodCollected')} value={r.movements.contributionsCollected} />
      </Section>

      <Section title={t('modelReport.sections.capital')} testId="mr-capital">
        <Figure testId="mr-retained" label={t('summary.retained')} value={r.position.retainedCollections} />
        <Figure testId="mr-shares" label={t('modelReport.totalShares')} value={r.shares.totalShares} count hint={t('modelReport.holders', { count: r.shares.members })} />
        <Figure testId="mr-available" label={t('summary.available')} value={r.position.availableFunds} />
      </Section>

      <Section title={t('modelReport.sections.loans')} testId="mr-loans">
        <Figure testId="mr-disbursed" label={t('modelReport.loansDisbursed')} value={r.movements.loansDisbursed} />
        <Figure testId="mr-repaid" label={t('modelReport.principalRepaid')} value={r.movements.principalRepaid} />
        <Figure testId="mr-outstanding" label={t('modelReport.outstandingPrincipal')} value={r.loans.outstandingPrincipal} />
        <Figure testId="mr-interest" label={t('modelReport.interestIncome')} value={r.earnings.interestIncome} />
      </Section>

      <Section title={t('modelReport.sections.earnings')} testId="mr-earnings">
        <Figure testId="mr-income" label={t('modelReport.totalIncome')} value={r.earnings.totalIncome} />
        <Figure testId="mr-expenses" label={t('modelReport.totalExpenses')} value={r.earnings.totalExpenses} />
        <Figure testId="mr-net" label={t('modelReport.netEarnings')} value={r.earnings.netEarnings} />
      </Section>
      <Alert severity="info" data-testid="mr-distribution">
        {t('modelReport.distributionUnavailable')}
      </Alert>

      {rotating && r.rotation ? (
        <Section title={t('modelReport.sections.rotation')} testId="mr-rotation">
          <Figure testId="mr-rot-verified" label={t('summary.rotatingCollected')} value={r.rotation.verifiedCollected} hint={t('summary.rotatingCollectedHint')} />
          <Figure testId="mr-rot-unreconciled" label={t('summary.reconciliation')} value={r.rotation.unreconciled} hint={t('summary.reconciliationHint')} />
          <Figure testId="mr-rot-unresolved" label={t('summary.unresolvedRotating')} value={r.rotation.unresolvedRotating} hint={t('summary.unresolvedRotatingHint')} />
          <Figure testId="mr-rot-unpaid" label={t('summary.owed')} value={r.rotation.unpaidEntitlements} hint={t('summary.owedHint')} />
          <Figure testId="mr-rot-paid" label={t('summary.paidOut')} value={r.rotation.paidOut} />
        </Section>
      ) : null}
      {rotating && r.rotation && r.rotation.periods.length > 0 ? (
        <Box sx={{ overflowX: 'auto' }} data-testid="mr-recipients">
          <Typography variant="subtitle2" gutterBottom>
            {t('modelReport.recipients')}
          </Typography>
          <table style={{ borderCollapse: 'collapse', minWidth: 520 }}>
            <thead>
              <tr>
                {['period', 'recipient', 'collected', 'unreconciled', 'distributed', 'outstanding'].map((h) => (
                  <th key={h} style={{ textAlign: 'left', padding: '4px 12px 4px 0' }}>
                    {t(`modelReport.recipientColumns.${h}`)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {r.rotation.periods.map((p) => (
                <tr key={p.periodId}>
                  <td style={{ padding: '2px 12px 2px 0' }}>{`${p.year}-${String(p.month).padStart(2, '0')}`}</td>
                  <td style={{ padding: '2px 12px 2px 0' }}>{p.recipientName}</td>
                  <td style={{ padding: '2px 12px 2px 0' }}>{formatMoney(p.collectedRotating)}</td>
                  <td style={{ padding: '2px 12px 2px 0' }}>{formatMoney(p.unreconciledRotating)}</td>
                  <td style={{ padding: '2px 12px 2px 0' }}>{formatMoney(p.distributed)}</td>
                  <td style={{ padding: '2px 12px 2px 0' }}>{formatMoney(p.outstandingEntitlement)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Box>
      ) : null}

      {model === 'HYBRID' && r.hybrid ? (
        <Section title={t('modelReport.sections.hybrid')} testId="mr-hybrid">
          <Figure testId="mr-hyb-retained" label={t('modelReport.retainedCapital')} value={r.hybrid.retainedCapital} />
          <Figure testId="mr-hyb-held" label={t('modelReport.rotatingHeld')} value={r.hybrid.rotatingHeld} />
          {r.hybrid.earningsAttributableToRetained !== null ? (
            <Figure
              testId="mr-hyb-earnings"
              label={t('modelReport.earningsRetainedIndicative')}
              value={r.hybrid.earningsAttributableToRetained}
              hint={t('modelReport.indicativeOnly')}
            />
          ) : null}
        </Section>
      ) : null}

      {r.notes.length > 0 ? (
        <Box>
          {r.notes.map((n) => (
            <Typography key={n} variant="caption" color="text.secondary" component="div">
              {n}
            </Typography>
          ))}
        </Box>
      ) : null}
    </Stack>
  )
}
