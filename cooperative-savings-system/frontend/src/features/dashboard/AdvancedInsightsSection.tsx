import { Box, MenuItem, Paper, Skeleton, Stack, TextField, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import dayjs from 'dayjs'
import { useMemo, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { useAppSelector } from '@/app/store/hooks'
import { selectCanManageMembers } from '@/app/store/authSlice'
import { fetchDashboardAdvancedInsights } from '@/shared/api/dashboard'
import { getErrorMessage } from '@/shared/api/client'
import { ErrorState } from '@/shared/components/ErrorState'
import { ROUTES } from '@/shared/constants/routes'
import {
  canViewMemberFinanceInsights,
  canViewMemberLoanInsights,
  canViewAnyMemberInsights,
} from '@/shared/types/auth'
import { formatMoney } from '@/shared/utils/formatMoney'
import { CHART_COLORS } from './chartPalette'
import { frequentBorrowerBars, investmentBars, reliabilityBars } from './dashboardVisuals'
import { InvestmentByMonthChart } from './InvestmentByMonthChart'
import { LoansDisbursedByMonthChart } from './LoansDisbursedByMonthChart'
import { RankedBarChart } from './RankedBarChart'
import { StackedShareBarChart } from './StackedShareBarChart'
import { dashboardChartTitleSx, dashboardSectionTitleSx } from './dashboardTypography'

interface AdvancedInsightsSectionProps {
  cooperativeId: string
}

export function AdvancedInsightsSection({ cooperativeId }: AdvancedInsightsSectionProps) {
  const { t } = useTranslation()
  const userRoles = useAppSelector((s) => s.auth.user?.roles ?? [])
  const canManageMembers = useAppSelector(selectCanManageMembers)
  const showLoans = canViewMemberLoanInsights(userRoles)
  const showFinance = canViewMemberFinanceInsights(userRoles)
  const [chartYear, setChartYear] = useState(dayjs().year())

  const query = useQuery({
    queryKey: ['dashboard', 'advanced-insights', cooperativeId],
    queryFn: () => fetchDashboardAdvancedInsights(cooperativeId),
    enabled: Boolean(cooperativeId) && canViewAnyMemberInsights(userRoles),
  })

  const yearOptions = useMemo(() => {
    const current = dayjs().year()
    return Array.from({ length: 6 }, (_, i) => current - 3 + i)
  }, [])

  if (!canViewAnyMemberInsights(userRoles)) return null

  const data = query.data
  const currency = data?.currency || 'RWF'
  const money = (v: string | number | null | undefined) => formatMoney(v ?? 0, { currency })
  const reliability = data?.repaymentReliability
  const hrefFor = canManageMembers ? (memberId: string) => `${ROUTES.members}/${memberId}` : undefined

  const borrowers = frequentBorrowerBars(data?.frequentBorrowers ?? [], hrefFor)
  const investments = investmentBars(data?.largestActiveInvestments ?? [])
  const reliabilityData = reliabilityBars(reliability?.members ?? [], hrefFor)

  return (
    <Box sx={{ mb: 3 }} data-testid="advanced-insights">
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={1.5}
        sx={{ mb: 2, justifyContent: 'space-between', alignItems: { sm: 'flex-start' } }}
      >
        <Box>
          <Typography variant="h5" component="h2" sx={[dashboardSectionTitleSx, { mb: 0.5 }]}>
            {t('dashboard.advancedInsights.title')}
          </Typography>
          <Typography variant="body2" color="text.secondary">
            {t('dashboard.advancedInsights.subtitle')}
          </Typography>
        </Box>
        {showLoans || showFinance ? (
          <TextField
            select
            size="small"
            label={t('contributions.fields.year')}
            value={chartYear}
            onChange={(e) => setChartYear(Number(e.target.value))}
            sx={{ minWidth: 110 }}
            data-testid="advanced-insights-year"
          >
            {yearOptions.map((y) => (
              <MenuItem key={y} value={y}>
                {y}
              </MenuItem>
            ))}
          </TextField>
        ) : null}
      </Stack>

      {query.isError ? (
        <Box sx={{ mb: 2 }}>
          <ErrorState message={getErrorMessage(query.error)} onRetry={() => void query.refetch()} />
        </Box>
      ) : null}

      <Stack
        direction={{ xs: 'column', md: 'row' }}
        spacing={2}
        sx={{ mb: 2, alignItems: 'stretch' }}
        useFlexGap
      >
        {showLoans ? (
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <LoansDisbursedByMonthChart
              cooperativeId={cooperativeId}
              year={chartYear}
              currency={currency}
            />
          </Box>
        ) : null}
        {showFinance ? (
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <InvestmentByMonthChart
              cooperativeId={cooperativeId}
              year={chartYear}
              currency={currency}
            />
          </Box>
        ) : null}
      </Stack>

      <Stack
        direction={{ xs: 'column', md: 'row' }}
        spacing={2}
        sx={{ alignItems: 'stretch' }}
        useFlexGap
      >
        {showLoans ? (
          <InsightCard
            testId="frequent-borrowers-card"
            title={t('dashboard.advancedInsights.frequentBorrowers')}
            hint={t('dashboard.advancedInsights.frequentBorrowersHint')}
            emptyTestId="frequent-borrowers-empty"
            empty={t('dashboard.advancedInsights.frequentBorrowersEmpty')}
            loading={query.isLoading}
            hasRows={borrowers.length > 0}
          >
            <RankedBarChart
              data={borrowers}
              ariaLabel={t('dashboard.advancedInsights.frequentBorrowers')}
              nameLabel={t('dashboard.advancedInsights.member')}
              valueLabel={t('dashboard.advancedInsights.totalBorrowed')}
              formatValue={money}
              color={CHART_COLORS.blue}
              detailColumns={[{ key: 'loans', label: t('dashboard.advancedInsights.loans') }]}
            />
          </InsightCard>
        ) : null}

        {showFinance ? (
          <InsightCard
            testId="largest-active-investments-card"
            title={t('dashboard.advancedInsights.largestInvestments')}
            hint={t('dashboard.advancedInsights.largestInvestmentsHint')}
            emptyTestId="largest-active-investments-empty"
            empty={t('dashboard.advancedInsights.largestInvestmentsEmpty')}
            loading={query.isLoading}
            hasRows={investments.length > 0}
          >
            <RankedBarChart
              data={investments}
              ariaLabel={t('dashboard.advancedInsights.largestInvestments')}
              nameLabel={t('dashboard.advancedInsights.investmentName')}
              valueLabel={t('dashboard.advancedInsights.remainingCapital')}
              formatValue={money}
              color={CHART_COLORS.purple}
              detailColumns={[
                {
                  key: 'originalCapital',
                  label: t('dashboard.advancedInsights.originalCapital'),
                  format: (value) => money(value),
                },
                {
                  key: 'profitReturned',
                  label: t('dashboard.advancedInsights.profitReturned'),
                  format: (value) => money(value),
                },
                { key: 'status', label: t('dashboard.advancedInsights.status') },
              ]}
            />
          </InsightCard>
        ) : null}
      </Stack>

      {showLoans ? (
        <Box sx={{ mt: 2 }}>
          <InsightCard
            testId="repayment-reliability-card"
            title={t('dashboard.advancedInsights.repaymentReliability')}
            hint={t('dashboard.advancedInsights.repaymentReliabilityHint')}
            note={t('dashboard.advancedInsights.repaymentReliabilityMinSample', {
              count: reliability?.minimumSample ?? 3,
            })}
            emptyTestId="repayment-reliability-empty"
            empty={t('dashboard.advancedInsights.repaymentReliabilityEmpty')}
            loading={query.isLoading}
            hasRows={reliabilityData.length > 0}
          >
            <StackedShareBarChart
              data={reliabilityData}
              ariaLabel={t('dashboard.advancedInsights.repaymentReliability')}
            />
          </InsightCard>
        </Box>
      ) : null}
    </Box>
  )
}

function InsightCard({
  title,
  hint,
  note,
  empty,
  emptyTestId,
  loading,
  hasRows,
  testId,
  children,
}: {
  title: string
  hint: string
  note?: string
  empty: string
  emptyTestId: string
  loading?: boolean
  hasRows: boolean
  testId: string
  children: ReactNode
}) {
  const { t } = useTranslation()
  return (
    <Paper
      elevation={0}
      data-testid={testId}
      sx={{
        flex: 1,
        minWidth: 0,
        p: { xs: 2, md: 2.5 },
        border: '1px solid',
        borderColor: 'divider',
        borderRadius: 2,
      }}
    >
      <Typography variant="subtitle1" sx={dashboardChartTitleSx}>
        {title}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: note ? 0.5 : 1.5 }}>
        {hint}
      </Typography>
      {note ? (
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 1.5 }}>
          {note}
        </Typography>
      ) : null}

      {loading ? (
        <Skeleton variant="rounded" height={140} aria-label={t('common.loading')} />
      ) : !hasRows ? (
        <Typography color="text.secondary" data-testid={emptyTestId}>
          {empty}
        </Typography>
      ) : (
        children
      )}
    </Paper>
  )
}
