import AccountBalanceWalletIcon from '@mui/icons-material/AccountBalanceWallet'
import GavelIcon from '@mui/icons-material/Gavel'
import GroupsIcon from '@mui/icons-material/Groups'
import HourglassEmptyIcon from '@mui/icons-material/HourglassEmpty'
import PaymentsIcon from '@mui/icons-material/Payments'
import PercentIcon from '@mui/icons-material/Percent'
import PeopleAltIcon from '@mui/icons-material/PeopleAlt'
import SavingsIcon from '@mui/icons-material/Savings'
import TrendingUpIcon from '@mui/icons-material/TrendingUp'
import VolunteerActivismIcon from '@mui/icons-material/VolunteerActivism'
import WarningAmberIcon from '@mui/icons-material/WarningAmber'
import { Box, Button, Grid, Paper, Stack, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { Link as RouterLink, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAppSelector } from '@/app/store/hooks'
import {
  selectCanManageMembers,
  selectIsSuperAdmin,
} from '@/app/store/authSlice'
import { getErrorMessage } from '@/shared/api/client'
import { fetchDashboardSummary } from '@/shared/api/dashboard'
import { ErrorState } from '@/shared/components/ErrorState'
import { MetricCard } from '@/shared/components/MetricCard'
import { DashboardSubscriptionCard } from '@/features/subscription/DashboardSubscriptionCard'
import { useCooperativeSubscription } from '@/features/subscription/useCooperativeSubscription'
import { ROUTES, SUPER_ADMIN_ROUTES } from '@/shared/constants/routes'
import type { DashboardSummary } from '@/shared/types/dashboard'
import {
  canViewAnyMemberInsights,
  primaryRole,
  ROLE_ACCOUNTANT,
  ROLE_LOAN_OFFICER,
  ROLE_PRESIDENT,
  ROLE_SECRETARY,
  ROLE_SUPER_ADMIN,
  ROLE_VICE_PRESIDENT,
  type AppRole,
} from '@/shared/types/auth'
import { formatMoney } from '@/shared/utils/formatMoney'
import { CurrentSchemeName } from './CurrentSchemeName'
import { MemberFinancialSummarySection } from './MemberFinancialSummarySection'
import { MemberInsightsSection } from './MemberInsightsSection'
import { MemberQuickActionsSection } from './MemberQuickActionsSection'
import { AdvancedInsightsSection } from './AdvancedInsightsSection'
import { MonthlyContributionsChart } from './MonthlyContributionsChart'
import { MyMemberStatusSection } from './MyMemberStatusSection'
import { SchemeAtAGlanceSection, type GlanceVisibility } from './SchemeAtAGlanceSection'
import { ThisMonthInsightsSection } from './ThisMonthInsightsSection'
import {
  dashboardChartTitleSx,
  dashboardHeroAmountSx,
  dashboardKpiLabelSx,
  dashboardPageTitleSx,
  dashboardSectionTitleSx,
} from './dashboardTypography'

const METRIC_COLS = { xs: 12, sm: 6, md: 4, lg: 3 }

type MetricKey =
  | 'totalMembers'
  | 'regularContributions'
  | 'specialContributions'
  | 'actualContributions'
  | 'totalInterest'
  | 'availableInterest'
  | 'outstandingLoans'
  | 'unpaidFines'
  | 'totalFines'
  | 'membersWithFines'
  | 'pendingFinePayments'
  | 'overdueLoans'
  | 'loanInterest'
  | 'activeInvestments'
  | 'pendingPayouts'

/**
 * Headline KPI cards. Metrics that the "Saving Scheme at a glance" section already shows as its primary value
 * (member count, regular/special contribution split) or that the repayments banner already shows (overdue
 * loans) are not repeated as cards. Role visibility is unchanged.
 */
function rolePrimaryMetrics(role: AppRole): MetricKey[] {
  switch (role) {
    case ROLE_SECRETARY:
      return []
    case ROLE_ACCOUNTANT:
      return [
        'actualContributions',
        'totalInterest',
        'availableInterest',
        'activeInvestments',
        'pendingPayouts',
      ]
    case ROLE_LOAN_OFFICER:
      return ['outstandingLoans', 'loanInterest']
    case ROLE_PRESIDENT:
    case ROLE_VICE_PRESIDENT:
    case ROLE_SUPER_ADMIN:
    default:
      return ['actualContributions', 'totalInterest', 'availableInterest']
  }
}

function roleObligationMetrics(role: AppRole): MetricKey[] {
  switch (role) {
    case ROLE_SECRETARY:
      return ['pendingFinePayments']
    case ROLE_ACCOUNTANT:
      return ['outstandingLoans', 'unpaidFines', 'pendingFinePayments']
    case ROLE_LOAN_OFFICER:
      return ['unpaidFines']
    case ROLE_PRESIDENT:
    case ROLE_VICE_PRESIDENT:
    case ROLE_SUPER_ADMIN:
    default:
      return [
        'outstandingLoans',
        'unpaidFines',
        'totalFines',
        'membersWithFines',
        'pendingFinePayments',
      ]
  }
}

function showFundsHero(role: AppRole): boolean {
  return role !== ROLE_SECRETARY && role !== ROLE_LOAN_OFFICER
}

function showLoanRepaymentBanner(role: AppRole): boolean {
  return role !== ROLE_SECRETARY
}

function showMemberTable(role: AppRole, canManageMembers: boolean): boolean {
  if (!canManageMembers) return false
  return (
    role === ROLE_PRESIDENT ||
    role === ROLE_VICE_PRESIDENT ||
    role === ROLE_SECRETARY ||
    role === ROLE_SUPER_ADMIN
  )
}

function showContributionsChart(role: AppRole): boolean {
  return role !== ROLE_LOAN_OFFICER
}

function showThisMonthInsights(role: AppRole): boolean {
  return role !== ROLE_SECRETARY
}

function showFullFinancialInsights(role: AppRole): boolean {
  return (
    role === ROLE_PRESIDENT ||
    role === ROLE_VICE_PRESIDENT ||
    role === ROLE_ACCOUNTANT ||
    role === ROLE_SUPER_ADMIN
  )
}

function showLoanInsights(role: AppRole): boolean {
  return role !== ROLE_SECRETARY
}

/** Member counts were a KPI for leadership and the Secretary only. */
function canSeeMemberCounts(role: AppRole): boolean {
  return role !== ROLE_ACCOUNTANT && role !== ROLE_LOAN_OFFICER
}

/** Regular/special contribution totals were KPIs for leadership and the Accountant only. */
function canSeeContributionTotals(role: AppRole): boolean {
  return role !== ROLE_SECRETARY && role !== ROLE_LOAN_OFFICER
}

interface AdminDashboardProps {
  cooperativeId: string
}

export function AdminDashboard({ cooperativeId }: AdminDashboardProps) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const userRoles = useAppSelector((s) => s.auth.user?.roles ?? [])
  const isSuperAdmin = useAppSelector(selectIsSuperAdmin)
  const canManageMembers = useAppSelector(selectCanManageMembers)
  const { subscription, isSuccess: subscriptionLoaded } = useCooperativeSubscription(cooperativeId)
  const officeRole = primaryRole(userRoles)
  const primaryKeys = rolePrimaryMetrics(officeRole)
  const obligationKeys = roleObligationMetrics(officeRole)
  const glanceVisibility = useMemo<GlanceVisibility>(
    () => ({
      members: canSeeMemberCounts(officeRole),
      contributions: canSeeContributionTotals(officeRole),
      loans: showLoanInsights(officeRole),
      fines: showFullFinancialInsights(officeRole),
    }),
    [officeRole],
  )

  const summaryQuery = useQuery({
    queryKey: ['dashboard', 'summary', cooperativeId],
    queryFn: () => fetchDashboardSummary(cooperativeId),
    enabled: Boolean(cooperativeId),
  })

  const summary = summaryQuery.data
  const currency = summary?.currency || 'RWF'
  const loading = summaryQuery.isLoading

  const money = (value: string | number | null | undefined) =>
    formatMoney(value ?? 0, { currency })

  const totalInterest =
    (Number(summary?.loanInterestEarned) || 0) + (Number(summary?.investmentProfits) || 0)

  const renderMetric = (key: MetricKey) => {
    if (!summary && !loading) return null
    switch (key) {
      case 'totalMembers':
        return (
          <MetricCard
            scale="dashboard"
            label={t('dashboard.metrics.totalMembers')}
            value={summary ? String(summary.totalMembers) : '—'}
            hint={t('dashboard.metrics.activeMembersHint', { count: summary?.activeMembers ?? 0 })}
            icon={<GroupsIcon fontSize="small" />}
            accent="blue"
            loading={loading}
          />
        )
      case 'regularContributions':
        return (
          <MetricCard
            scale="dashboard"
            label={t('dashboard.metrics.regularContributions')}
            value={summary ? money(summary.regularContributionsTotal) : '—'}
            hint={t('dashboard.metrics.regularHint')}
            icon={<SavingsIcon fontSize="small" />}
            accent="green"
            loading={loading}
          />
        )
      case 'specialContributions':
        return (
          <MetricCard
            scale="dashboard"
            label={t('dashboard.metrics.specialContributions')}
            value={summary ? money(summary.specialContributionsTotal) : '—'}
            hint={t('dashboard.metrics.specialHint')}
            icon={<VolunteerActivismIcon fontSize="small" />}
            accent="purple"
            loading={loading}
          />
        )
      case 'actualContributions':
        return (
          <MetricCard
            scale="dashboard"
            label={t('dashboard.metrics.actualContributions')}
            value={summary ? money(summary.actualContributionsTotal) : '—'}
            hint={t('dashboard.metrics.actualHint')}
            icon={<PaymentsIcon fontSize="small" />}
            accent="blue"
            loading={loading}
          />
        )
      case 'totalInterest':
        return (
          <MetricCard
            scale="dashboard"
            label={t('dashboard.metrics.totalInterest')}
            value={summary ? money(totalInterest) : '—'}
            hint={t('dashboard.metrics.totalInterestHint', {
              loan: money(summary?.loanInterestEarned ?? 0),
              investment: money(summary?.investmentProfits ?? 0),
            })}
            icon={<TrendingUpIcon fontSize="small" />}
            accent="gold"
            loading={loading}
          />
        )
      case 'availableInterest':
        return (
          <MetricCard
            scale="dashboard"
            label={t('dashboard.metrics.availableInterest')}
            value={summary?.availableInterest != null ? money(summary.availableInterest) : '—'}
            hint={t('dashboard.metrics.availableInterestHint')}
            icon={<PercentIcon fontSize="small" />}
            accent="blue"
            loading={loading}
          />
        )
      case 'outstandingLoans':
        return (
          <MetricCard
            scale="dashboard"
            label={t('dashboard.metrics.outstandingLoans')}
            value={
              summary?.outstandingLoanPrincipal != null
                ? money(summary.outstandingLoanPrincipal)
                : '—'
            }
            icon={<AccountBalanceWalletIcon fontSize="small" />}
            accent="orange"
            loading={loading}
          />
        )
      case 'unpaidFines':
        return (
          <MetricCard
            scale="dashboard"
            label={t('dashboard.metrics.unpaidFines')}
            value={summary?.unpaidFines != null ? String(summary.unpaidFines) : '—'}
            icon={<GavelIcon fontSize="small" />}
            accent="red"
            loading={loading}
          />
        )
      case 'totalFines':
        return (
          <MetricCard
            scale="dashboard"
            label={t('dashboard.metrics.totalFines')}
            value={summary?.totalFines != null ? String(summary.totalFines) : '—'}
            icon={<GavelIcon fontSize="small" />}
            accent="red"
            loading={loading}
          />
        )
      case 'membersWithFines':
        return (
          <MetricCard
            scale="dashboard"
            label={t('dashboard.metrics.membersWithFines')}
            value={summary?.membersWithFines != null ? String(summary.membersWithFines) : '—'}
            icon={<PeopleAltIcon fontSize="small" />}
            accent="orange"
            loading={loading}
          />
        )
      case 'pendingFinePayments':
        return (
          <MetricCard
            scale="dashboard"
            label={t('dashboard.metrics.pendingFinePayments')}
            value={
              summary?.pendingFinePayments != null ? String(summary.pendingFinePayments) : '—'
            }
            icon={<HourglassEmptyIcon fontSize="small" />}
            accent="gold"
            loading={loading}
          />
        )
      case 'overdueLoans':
        return (
          <MetricCard
            scale="dashboard"
            label={t('dashboard.metrics.overdueLoans')}
            value={summary?.overdueLoansCount != null ? String(summary.overdueLoansCount) : '—'}
            icon={<WarningAmberIcon fontSize="small" />}
            accent="red"
            loading={loading}
          />
        )
      case 'loanInterest':
        return (
          <MetricCard
            scale="dashboard"
            label={t('dashboard.metrics.loanInterestEarned')}
            value={summary ? money(summary.loanInterestEarned) : '—'}
            icon={<TrendingUpIcon fontSize="small" />}
            accent="gold"
            loading={loading}
          />
        )
      case 'activeInvestments':
        return (
          <MetricCard
            scale="dashboard"
            label={t('dashboard.metrics.activeInvestments')}
            value={
              summary?.activeInvestmentsCount != null
                ? String(summary.activeInvestmentsCount)
                : '—'
            }
            hint={
              summary?.investmentCapital != null
                ? money(summary.investmentCapital)
                : undefined
            }
            icon={<TrendingUpIcon fontSize="small" />}
            accent="purple"
            loading={loading}
          />
        )
      case 'pendingPayouts':
        return (
          <MetricCard
            scale="dashboard"
            label={t('dashboard.metrics.pendingPayouts')}
            value={summary?.pendingPayoutsCount != null ? String(summary.pendingPayoutsCount) : '—'}
            icon={<PaymentsIcon fontSize="small" />}
            accent="blue"
            loading={loading}
          />
        )
      default:
        return null
    }
  }

  return (
    <Box>
      <Box
        sx={{
          display: 'flex',
          flexDirection: { xs: 'column', md: 'row' },
          alignItems: { xs: 'stretch', md: 'center' },
          justifyContent: 'space-between',
          gap: 2,
          mb: 3,
        }}
      >
        <Box>
          <Typography variant="h4" component="h1" sx={dashboardPageTitleSx}>
            {t('dashboard.title')}
          </Typography>
          <CurrentSchemeName cooperativeId={cooperativeId} />
          {isSuperAdmin ? (
            <Typography variant="body2" color="text.secondary" sx={{ mt: 0.75, maxWidth: 720 }}>
              {t('dashboard.super.operatingHint')}
            </Typography>
          ) : null}
        </Box>

        {isSuperAdmin ? (
          <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap' }} useFlexGap>
            <Button
              variant="outlined"
              onClick={() => {
                navigate(SUPER_ADMIN_ROUTES.home)
              }}
            >
              {t('dashboard.super.allCooperatives')}
            </Button>
          </Stack>
        ) : null}
      </Box>

      {subscriptionLoaded ? (
        <DashboardSubscriptionCard subscription={subscription} variant="leadership" />
      ) : null}

      {summaryQuery.isError ? (
        <Box sx={{ mb: 2 }}>
          <ErrorState
            message={getErrorMessage(summaryQuery.error)}
            onRetry={() => void summaryQuery.refetch()}
          />
        </Box>
      ) : null}

      <SchemeAtAGlanceSection cooperativeId={cooperativeId} show={glanceVisibility} />

      {showFundsHero(officeRole) ? (
        <FundsHero summary={summary} money={money} t={t} />
      ) : null}

      {primaryKeys.length > 0 ? (
        <Grid container spacing={2} sx={{ mb: 3 }}>
          {primaryKeys.map((key) => (
            <Grid key={key} size={METRIC_COLS}>
              {renderMetric(key)}
            </Grid>
          ))}
        </Grid>
      ) : null}

      {showThisMonthInsights(officeRole) ? (
        <ThisMonthInsightsSection
          cooperativeId={cooperativeId}
          showFullFinancials={showFullFinancialInsights(officeRole)}
          showLoanAnalytics={showLoanInsights(officeRole)}
        />
      ) : null}

      {obligationKeys.length > 0 ? (
        <>
          <Typography variant="subtitle1" component="h2" sx={[dashboardSectionTitleSx, { mb: 1.5 }]}>
            {t('dashboard.admin.obligationsTitle')}
          </Typography>
          <Grid container spacing={2} sx={{ mb: 3 }}>
            {obligationKeys.map((key) => (
              <Grid key={key} size={METRIC_COLS}>
                {renderMetric(key)}
              </Grid>
            ))}
          </Grid>
        </>
      ) : null}

      {showLoanRepaymentBanner(officeRole) ? (
        <OverdueLoansBanner summary={summary} t={t} />
      ) : null}

      {!isSuperAdmin ? (
        <>
          <Box sx={{ mb: 3 }}>
            <MemberQuickActionsSection cooperativeId={cooperativeId} />
          </Box>
          {/* The section draws its own bordered surface, so it is not wrapped in another Paper. */}
          <Box sx={{ mb: 3 }}>
            <MyMemberStatusSection cooperativeId={cooperativeId} compact />
          </Box>
        </>
      ) : null}

      {canViewAnyMemberInsights(userRoles) ? (
        <MemberInsightsSection cooperativeId={cooperativeId} />
      ) : null}

      {canViewAnyMemberInsights(userRoles) ? (
        <AdvancedInsightsSection cooperativeId={cooperativeId} />
      ) : null}

      {showMemberTable(officeRole, canManageMembers) ? (
        <Box sx={{ mb: 3 }}>
          <MemberFinancialSummarySection cooperativeId={cooperativeId} />
        </Box>
      ) : null}

      {showContributionsChart(officeRole) ? (
        <MonthlyContributionsChart cooperativeId={cooperativeId} currency={currency} />
      ) : null}
    </Box>
  )
}

function FundsHero({
  summary,
  money,
  t,
}: {
  summary: DashboardSummary | undefined
  money: (value: string | number | null | undefined) => string
  t: (key: string, options?: Record<string, unknown>) => string
}) {
  return (
    <Paper
      elevation={0}
      sx={{
        p: { xs: 2.5, md: 3.5 },
        mb: 3,
        border: '1px solid',
        borderColor: 'divider',
        borderRadius: 2,
        bgcolor: 'background.paper',
        boxShadow: '0 1px 3px rgba(15, 23, 42, 0.06)',
        borderLeft: '4px solid',
        borderLeftColor: 'primary.main',
      }}
    >
      <Typography variant="overline" color="text.secondary" sx={{ ...dashboardKpiLabelSx, letterSpacing: 0.6 }}>
        {t('dashboard.metrics.availableGroupFunds')}
      </Typography>
      <Typography
        variant="h3"
        sx={{ ...dashboardHeroAmountSx, my: 1, fontVariantNumeric: 'tabular-nums', wordBreak: 'break-word' }}
      >
        {summary ? money(summary.availableGroupFunds) : '—'}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ maxWidth: 680 }}>
        {t('dashboard.admin.availableFundsHint')}
      </Typography>
      {summary?.pendingSpecialApprovals ? (
        <Typography variant="body2" color="warning.main" sx={{ mt: 1, fontWeight: 600 }}>
          {t('dashboard.metrics.pendingApprovals', { count: summary.pendingSpecialApprovals })}
        </Typography>
      ) : null}
    </Paper>
  )
}

function OverdueLoansBanner({
  summary,
  t,
}: {
  summary: DashboardSummary | undefined
  t: (key: string, options?: Record<string, unknown>) => string
}) {
  if (summary?.overdueLoansCount) {
    return (
      <Paper
        elevation={0}
        sx={{
          p: { xs: 2.5, md: 3 },
          mb: 3,
          border: '1px solid',
          borderColor: 'warning.light',
          bgcolor: 'rgba(217, 119, 6, 0.06)',
          display: 'flex',
          flexDirection: { xs: 'column', sm: 'row' },
          alignItems: { xs: 'stretch', sm: 'center' },
          justifyContent: 'space-between',
          gap: 2,
        }}
      >
        <Box>
          <Typography variant="h6" component="h2" gutterBottom sx={dashboardSectionTitleSx}>
            {t('dashboard.admin.pendingRepaymentsTitle')}
          </Typography>
          <Typography variant="body2" color="text.secondary">
            {t('dashboard.admin.pendingRepaymentsDescription', {
              count: summary.overdueLoansCount,
            })}
          </Typography>
        </Box>
        <Button component={RouterLink} to={ROUTES.loans} variant="contained" color="warning">
          {t('dashboard.admin.manageLoans')}
        </Button>
      </Paper>
    )
  }

  return (
    <Paper
      elevation={0}
      sx={{
        p: { xs: 2, md: 2.5 },
        mb: 3,
        border: '1px solid',
        borderColor: 'divider',
        borderRadius: 2,
      }}
    >
      <Typography variant="subtitle1" sx={dashboardChartTitleSx}>
        {t('dashboard.admin.pendingRepaymentsTitle')}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
        {t('dashboard.admin.pendingRepaymentsEmpty', {
          defaultValue: 'No pending repayments.',
        })}
      </Typography>
    </Paper>
  )
}
