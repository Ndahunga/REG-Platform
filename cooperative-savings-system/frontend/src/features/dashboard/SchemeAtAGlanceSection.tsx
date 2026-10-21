import InsightsIcon from '@mui/icons-material/Insights'
import { Box, Paper, Skeleton, Stack, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { fetchDashboardInsights, fetchDashboardSummary } from '@/shared/api/dashboard'
import { formatMoney } from '@/shared/utils/formatMoney'
import { CHART_COLORS } from './chartPalette'
import {
  buildSchemeSummary,
  contributionMixData,
  memberStatusData,
  toFiniteNumber,
} from './dashboardVisuals'
import { AnalyticsGrid } from './AnalyticsGrid'
import { DonutCard } from './DonutCard'
import { FineActivityCard } from './FineActivityCard'
import { LoansIssuedVsRepaidChart } from './LoansIssuedVsRepaidChart'
import { dashboardSectionTitleSx } from './dashboardTypography'

/** Which at-a-glance visuals (and which sentences) this viewer's role may see. */
export interface GlanceVisibility {
  members: boolean
  contributions: boolean
  loans: boolean
  fines: boolean
}

interface SchemeAtAGlanceSectionProps {
  cooperativeId: string
  show: GlanceVisibility
}

/**
 * A visual snapshot near the top of the officer dashboard: small donuts for genuine compositions, compact
 * bars for flows, and a short factual summary. Only data the viewer's role may already see is used.
 */
export function SchemeAtAGlanceSection({ cooperativeId, show }: SchemeAtAGlanceSectionProps) {
  const { t } = useTranslation()
  const anything = show.members || show.contributions || show.loans || show.fines
  const needsSummary = show.members || show.contributions || show.loans
  const needsInsights = show.contributions || show.loans || show.fines

  // Same query keys as the rest of the dashboard, so these share cached responses.
  const summaryQuery = useQuery({
    queryKey: ['dashboard', 'summary', cooperativeId],
    queryFn: () => fetchDashboardSummary(cooperativeId),
    enabled: Boolean(cooperativeId) && needsSummary,
  })
  const insightsQuery = useQuery({
    queryKey: ['dashboard', 'insights', cooperativeId],
    queryFn: () => fetchDashboardInsights(cooperativeId),
    enabled: Boolean(cooperativeId) && needsInsights,
  })

  const summary = summaryQuery.data
  const insights = insightsQuery.data
  const currency = summary?.currency || insights?.currency || 'RWF'
  const money = (value: number) => formatMoney(toFiniteNumber(value), { currency })

  const summaryLines = useMemo(
    () => buildSchemeSummary({ summary, insights, show }, t, (v) => formatMoney(toFiniteNumber(v), { currency })),
    [summary, insights, show, t, currency],
  )

  if (!anything) return null

  const members = summary ? memberStatusData(summary) : undefined
  const mix = summary ? contributionMixData(summary) : undefined
  const summaryLoading =
    (needsSummary && summaryQuery.isLoading) || (needsInsights && insightsQuery.isLoading)

  return (
    <Box sx={{ mb: 3 }} data-testid="scheme-at-a-glance">
      <Typography variant="h5" component="h2" sx={[dashboardSectionTitleSx, { mb: 0.5 }]}>
        {t('dashboard.glance.title')}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {t('dashboard.glance.subtitle')}
      </Typography>

      <Paper
        elevation={0}
        data-testid="scheme-summary"
        sx={{
          p: { xs: 2, md: 2.5 },
          mb: 2,
          border: '1px solid',
          borderColor: 'divider',
          borderRadius: 2,
          bgcolor: 'rgba(27, 77, 140, 0.04)',
        }}
      >
        <Stack direction="row" spacing={1.5} sx={{ alignItems: 'flex-start' }}>
          <InsightsIcon color="primary" fontSize="small" sx={{ mt: 0.25 }} />
          <Box sx={{ minWidth: 0 }}>
            <Typography variant="subtitle2" sx={{ fontWeight: 600, mb: 0.5 }}>
              {t('dashboard.glance.summaryTitle')}
            </Typography>
            {summaryLoading ? (
              <Skeleton variant="text" width="80%" />
            ) : summaryLines.length === 0 ? (
              <Typography variant="body2" color="text.secondary">
                {t('dashboard.glance.summaryUnavailable')}
              </Typography>
            ) : (
              <Typography variant="body2" component="p" sx={{ m: 0 }}>
                {summaryLines.join(' ')}
              </Typography>
            )}
          </Box>
        </Stack>
      </Paper>

      <AnalyticsGrid>
        {show.members ? (
          <DonutCard
            testId="glance-members-donut"
            title={t('dashboard.glance.membersTitle')}
            loading={summaryQuery.isLoading}
            slices={[
              {
                key: 'active',
                label: t('dashboard.glance.activeMembers'),
                value: members?.active ?? 0,
                color: CHART_COLORS.green,
              },
              {
                key: 'inactive',
                label: t('dashboard.glance.inactiveMembers'),
                value: members?.inactive ?? 0,
                color: CHART_COLORS.neutral,
              },
            ]}
            centerValue={String(members?.total ?? 0)}
            centerLabel={t('dashboard.glance.totalMembers')}
            formatValue={(value) => String(Math.round(value))}
            emptyMessage={t('dashboard.glance.membersEmpty')}
          />
        ) : null}

        {show.contributions ? (
          <DonutCard
            testId="glance-contribution-mix-donut"
            title={t('dashboard.glance.contributionMixTitle')}
            loading={summaryQuery.isLoading}
            slices={[
              {
                key: 'regular',
                label: t('dashboard.glance.regular'),
                value: mix?.regular ?? 0,
                color: CHART_COLORS.blue,
              },
              {
                key: 'special',
                label: t('dashboard.glance.special'),
                value: mix?.special ?? 0,
                color: CHART_COLORS.purple,
              },
            ]}
            centerValue={mix ? money(mix.total) : '—'}
            centerLabel={t('dashboard.glance.totalContributions')}
            formatValue={money}
            emptyMessage={t('dashboard.glance.contributionMixEmpty')}
          />
        ) : null}

        {show.loans ? (
          <LoansIssuedVsRepaidChart
            issuedAmount={insights?.loans.issuedAmountCurrentMonth ?? 0}
            repaidAmount={insights?.loans.repaidCurrentMonth ?? 0}
            outstandingPrincipal={
              summary?.outstandingLoanPrincipal ?? insights?.loans.outstandingPrincipal ?? null
            }
            currency={currency}
            loading={insightsQuery.isLoading}
          />
        ) : null}

        {show.fines ? (
          <FineActivityCard
            fines={insights?.fines}
            currency={currency}
            loading={insightsQuery.isLoading}
          />
        ) : null}
      </AnalyticsGrid>
    </Box>
  )
}
