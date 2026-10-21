import AccountBalanceWalletIcon from '@mui/icons-material/AccountBalanceWallet'
import PaymentsIcon from '@mui/icons-material/Payments'
import SavingsIcon from '@mui/icons-material/Savings'
import { Box, Grid, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { fetchDashboardInsights } from '@/shared/api/dashboard'
import { getErrorMessage } from '@/shared/api/client'
import { ErrorState } from '@/shared/components/ErrorState'
import { formatMoney } from '@/shared/utils/formatMoney'
import { InsightKpiCard } from './InsightKpiCard'
import { dashboardSectionTitleSx } from './dashboardTypography'

const KPI_COLS = { xs: 12, sm: 6, md: 4, lg: 4 }

interface ThisMonthInsightsSectionProps {
  cooperativeId: string
  /** When false, only loan-focused KPIs/charts are shown (loan officer). */
  showFullFinancials?: boolean
  showLoanAnalytics?: boolean
}

export function ThisMonthInsightsSection({
  cooperativeId,
  showFullFinancials = true,
  showLoanAnalytics = true,
}: ThisMonthInsightsSectionProps) {
  const { t } = useTranslation()

  const insightsQuery = useQuery({
    queryKey: ['dashboard', 'insights', cooperativeId],
    queryFn: () => fetchDashboardInsights(cooperativeId),
    enabled: Boolean(cooperativeId),
  })

  const insights = insightsQuery.data
  const currency = insights?.currency || 'RWF'
  const loading = insightsQuery.isLoading
  const money = (v: string | number | null | undefined) => formatMoney(v ?? 0, { currency })

  if (!showFullFinancials && !showLoanAnalytics) return null

  return (
    <Box sx={{ mb: 3 }} data-testid="this-month-insights">
      <Typography variant="h5" component="h2" sx={[dashboardSectionTitleSx, { mb: 0.5 }]}>
        {t('dashboard.insights.thisMonthTitle')}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {insights
          ? t('dashboard.insights.thisMonthSubtitle', {
              month: insights.period.month,
              year: insights.period.year,
            })
          : t('dashboard.insights.thisMonthHint')}
      </Typography>

      {insightsQuery.isError ? (
        <Box sx={{ mb: 2 }}>
          <ErrorState
            message={getErrorMessage(insightsQuery.error)}
            onRetry={() => void insightsQuery.refetch()}
          />
        </Box>
      ) : null}

      <Grid container spacing={2}>
        {showFullFinancials ? (
          <Grid size={KPI_COLS}>
            <InsightKpiCard
              data-testid="insight-contributions"
              label={t('dashboard.insights.contributions')}
              value={insights ? money(insights.contributions.currentMonth) : '—'}
              icon={<SavingsIcon fontSize="small" />}
              loading={loading}
              changePercent={insights?.contributions.changePercent}
              changeState={insights?.contributions.changeState}
              momSemantics="favorable-up"
            />
          </Grid>
        ) : null}

        {showLoanAnalytics ? (
          <>
            <Grid size={KPI_COLS}>
              <InsightKpiCard
                data-testid="insight-loans-issued"
                label={t('dashboard.insights.loansIssued')}
                value={insights ? money(insights.loans.issuedAmountCurrentMonth) : '—'}
                hint={
                  insights
                    ? t('dashboard.insights.loansIssuedCount', {
                        count: insights.loans.issuedCountCurrentMonth,
                      })
                    : undefined
                }
                icon={<AccountBalanceWalletIcon fontSize="small" />}
                loading={loading}
                changePercent={insights?.loans.issuedAmountChangePercent}
                changeState={insights?.loans.issuedAmountChangeState}
                momSemantics="neutral"
              />
            </Grid>
            <Grid size={KPI_COLS}>
              <InsightKpiCard
                data-testid="insight-loan-repayments"
                label={t('dashboard.insights.loanRepayments')}
                value={insights ? money(insights.loans.repaidCurrentMonth) : '—'}
                icon={<PaymentsIcon fontSize="small" />}
                loading={loading}
                changePercent={insights?.loans.repaidChangePercent}
                changeState={insights?.loans.repaidChangeState}
                momSemantics="favorable-up"
              />
            </Grid>
          </>
        ) : null}

        {showFullFinancials ? (
          <Grid size={KPI_COLS}>
            <InsightKpiCard
              data-testid="insight-fines-collected"
              label={t('dashboard.insights.finesCollected')}
              value={insights ? money(insights.fines.collectedCurrentMonth) : '—'}
              icon={<PaymentsIcon fontSize="small" />}
              loading={loading}
              changePercent={insights?.fines.collectedChangePercent}
              changeState={insights?.fines.collectedChangeState}
              momSemantics="neutral"
            />
          </Grid>
        ) : null}
      </Grid>
    </Box>
  )
}
