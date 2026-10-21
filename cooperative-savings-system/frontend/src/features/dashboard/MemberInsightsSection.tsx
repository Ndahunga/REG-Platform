import { Box, Paper, Skeleton, Stack, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { useAppSelector } from '@/app/store/hooks'
import { selectCanManageMembers } from '@/app/store/authSlice'
import { fetchDashboardMemberInsights } from '@/shared/api/dashboard'
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
import { fineFollowUpBars, overdueLoanBars, topContributorBars } from './dashboardVisuals'
import { RankedBarChart } from './RankedBarChart'
import { dashboardChartTitleSx, dashboardSectionTitleSx } from './dashboardTypography'

interface MemberInsightsSectionProps {
  cooperativeId: string
}

export function MemberInsightsSection({ cooperativeId }: MemberInsightsSectionProps) {
  const { t } = useTranslation()
  const userRoles = useAppSelector((s) => s.auth.user?.roles ?? [])
  const canManageMembers = useAppSelector(selectCanManageMembers)
  const showFinance = canViewMemberFinanceInsights(userRoles)
  const showLoans = canViewMemberLoanInsights(userRoles)

  const query = useQuery({
    queryKey: ['dashboard', 'member-insights', cooperativeId],
    queryFn: () => fetchDashboardMemberInsights(cooperativeId),
    enabled: Boolean(cooperativeId) && canViewAnyMemberInsights(userRoles),
  })

  const data = query.data
  const currency = data?.currency || 'RWF'
  const money = (v: string | number | null | undefined) => formatMoney(v ?? 0, { currency })
  const hrefFor = canManageMembers ? (memberId: string) => `${ROUTES.members}/${memberId}` : undefined

  const contributors = topContributorBars(data?.topContributors ?? [], hrefFor)
  const fines = fineFollowUpBars(data?.fineFollowUp ?? [], hrefFor)
  const overdue = overdueLoanBars(data?.overdueLoans ?? [], hrefFor)

  if (!canViewAnyMemberInsights(userRoles)) return null

  return (
    <Box sx={{ mb: 3 }} data-testid="member-insights">
      <Typography variant="h5" component="h2" sx={[dashboardSectionTitleSx, { mb: 0.5 }]}>
        {t('dashboard.memberInsights.title')}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {t('dashboard.memberInsights.subtitle')}
      </Typography>

      {query.isError ? (
        <Box sx={{ mb: 2 }}>
          <ErrorState message={getErrorMessage(query.error)} onRetry={() => void query.refetch()} />
        </Box>
      ) : null}

      <Stack
        direction={{ xs: 'column', md: 'row' }}
        spacing={2}
        sx={{ alignItems: 'stretch' }}
        useFlexGap
      >
        {showFinance ? (
          <InsightChartCard
            testId="top-contributors-card"
            title={t('dashboard.memberInsights.topContributors')}
            hint={t('dashboard.memberInsights.topContributorsHint')}
            empty={t('dashboard.memberInsights.topContributorsEmpty')}
            loading={query.isLoading}
            hasRows={contributors.length > 0}
          >
            <RankedBarChart
              data={contributors}
              ariaLabel={t('dashboard.memberInsights.topContributors')}
              nameLabel={t('dashboard.advancedInsights.member')}
              valueLabel={t('dashboard.memberInsights.contributionAmount')}
              formatValue={money}
              color={CHART_COLORS.blue}
            />
          </InsightChartCard>
        ) : null}

        {showFinance ? (
          <InsightChartCard
            testId="fine-follow-up-card"
            title={t('dashboard.memberInsights.fineFollowUp')}
            hint={t('dashboard.memberInsights.fineFollowUpHint')}
            empty={t('dashboard.memberInsights.fineFollowUpEmpty')}
            loading={query.isLoading}
            hasRows={fines.length > 0}
          >
            <RankedBarChart
              data={fines}
              ariaLabel={t('dashboard.memberInsights.fineFollowUp')}
              nameLabel={t('dashboard.advancedInsights.member')}
              valueLabel={t('dashboard.memberInsights.outstandingAmount')}
              formatValue={money}
              color={CHART_COLORS.red}
              detailColumns={[
                { key: 'fineCount', label: t('dashboard.memberInsights.fineCount') },
                {
                  key: 'issuedAmount',
                  label: t('dashboard.memberInsights.issuedAmount'),
                  format: (value) => money(value),
                },
              ]}
            />
          </InsightChartCard>
        ) : null}

        {showLoans ? (
          <InsightChartCard
            testId="overdue-loans-card"
            title={t('dashboard.memberInsights.overdueLoans')}
            hint={t('dashboard.memberInsights.overdueLoansHint')}
            empty={t('dashboard.memberInsights.overdueLoansEmpty')}
            loading={query.isLoading}
            hasRows={overdue.length > 0}
          >
            <RankedBarChart
              data={overdue}
              ariaLabel={t('dashboard.memberInsights.overdueLoans')}
              nameLabel={t('dashboard.advancedInsights.member')}
              valueLabel={t('dashboard.memberInsights.overduePrincipal')}
              formatValue={money}
              color={CHART_COLORS.orange}
              detailColumns={[
                { key: 'overdueLoanCount', label: t('dashboard.memberInsights.overdueCount') },
              ]}
            />
          </InsightChartCard>
        ) : null}
      </Stack>
    </Box>
  )
}

function InsightChartCard({
  title,
  hint,
  empty,
  loading,
  hasRows,
  testId,
  children,
}: {
  title: string
  hint: string
  empty: string
  loading?: boolean
  hasRows: boolean
  testId: string
  children: ReactNode
}) {
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
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 1.5 }}>
        {hint}
      </Typography>

      {loading ? (
        <Skeleton variant="rounded" height={140} />
      ) : !hasRows ? (
        <Typography color="text.secondary" data-testid={`${testId}-empty`}>
          {empty}
        </Typography>
      ) : (
        children
      )}
    </Paper>
  )
}
