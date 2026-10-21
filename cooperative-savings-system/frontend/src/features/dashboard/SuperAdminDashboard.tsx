import AccountBalanceIcon from '@mui/icons-material/AccountBalance'
import AddIcon from '@mui/icons-material/Add'
import AdminPanelSettingsIcon from '@mui/icons-material/AdminPanelSettings'
import GroupsIcon from '@mui/icons-material/Groups'
import HealthAndSafetyIcon from '@mui/icons-material/HealthAndSafety'
import HourglassEmptyIcon from '@mui/icons-material/HourglassEmpty'
import PeopleAltIcon from '@mui/icons-material/PeopleAlt'
import WarningAmberIcon from '@mui/icons-material/WarningAmber'
import { Box, Button, Chip, Grid, Paper, Stack, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { Link as RouterLink, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAppSelector } from '@/app/store/hooks'
import { getErrorMessage } from '@/shared/api/client'
import { fetchCooperatives } from '@/shared/api/cooperatives'
import { fetchDashboardSummary, fetchPlatformOverviewIfAvailable } from '@/shared/api/dashboard'
import { ErrorState } from '@/shared/components/ErrorState'
import { MetricCard } from '@/shared/components/MetricCard'
import { SUPER_ADMIN_ROUTES } from '@/shared/constants/routes'
import {
  applyCooperativeSummaries,
  platformOverviewFromCooperatives,
} from './platformOverviewFallback'
import { SuperAdminOverviewCharts } from './SuperAdminOverviewCharts'
import { dashboardPageTitleSx, dashboardSectionTitleSx } from './dashboardTypography'

interface SuperAdminDashboardProps {
  /** The portal home draws its own page header and quick links, so it hides this one. */
  showHeader?: boolean
  /** Number of system super admins, shown as one more platform metric when known. */
  superAdminCount?: number
}

/** Platform-wide overview and Saving Scheme list; picking a scheme opens its support workspace. */
export function SuperAdminDashboard({ showHeader = true, superAdminCount }: SuperAdminDashboardProps = {}) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const user = useAppSelector((s) => s.auth.user)

  const openCooperative = (cooperativeId: string) => {
    navigate(SUPER_ADMIN_ROUTES.workspaceDashboard(cooperativeId))
  }

  const query = useQuery({
    queryKey: ['cooperatives', 'list', 'super-home'],
    queryFn: () => fetchCooperatives({ page: 0, size: 50 }),
  })

  const overviewQuery = useQuery({
    queryKey: ['platform', 'dashboard', 'overview'],
    queryFn: fetchPlatformOverviewIfAvailable,
  })

  const rows = query.data?.content ?? []
  const liveOverview = overviewQuery.data ?? undefined
  const usingDerivedOverview = overviewQuery.isSuccess && overviewQuery.data === null

  const summariesQuery = useQuery({
    queryKey: ['platform', 'dashboard', 'derived-summaries', rows.map((row) => row.id)],
    enabled: usingDerivedOverview && rows.length > 0,
    queryFn: async () => {
      const results = await Promise.allSettled(rows.map((row) => fetchDashboardSummary(row.id)))
      return results.flatMap((result) => (result.status === 'fulfilled' ? [result.value] : []))
    },
  })

  const derivedOverview =
    usingDerivedOverview && query.data
      ? applyCooperativeSummaries(
          platformOverviewFromCooperatives(query.data.content, query.data.totalElements),
          summariesQuery.data ?? [],
        )
      : undefined
  const overview = liveOverview ?? derivedOverview
  const hasLiveOverview = Boolean(liveOverview)
  const summariesReady =
    summariesQuery.isSuccess && (rows.length === 0 || (summariesQuery.data?.length ?? 0) > 0)
  const countsReady = hasLiveOverview || summariesReady
  const total = overview?.totalCooperatives ?? query.data?.totalElements ?? 0
  const derivedLoading = usingDerivedOverview && summariesQuery.isFetching

  return (
    <Box>
      {showHeader ? (
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={2}
        sx={{ mb: 3, alignItems: { sm: 'flex-start' }, justifyContent: 'space-between' }}
      >
        <Box>
          <Stack direction="row" spacing={1} sx={{ mb: 1, alignItems: 'center' }}>
            <Typography variant="h4" component="h1" sx={dashboardPageTitleSx}>
              {t('dashboard.super.title')}
            </Typography>
            <Chip size="small" color="primary" label={t('roles.superAdminBadge')} />
          </Stack>
          <Typography variant="body1" color="text.secondary">
            {t('dashboard.super.welcome', { name: user?.firstName || user?.fullName || '' })}
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5, maxWidth: 640 }}>
            {t('dashboard.super.description')}
          </Typography>
        </Box>
        <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
          <Button
            component={RouterLink}
            to={SUPER_ADMIN_ROUTES.system}
            variant="outlined"
            startIcon={<HealthAndSafetyIcon />}
          >
            {t('nav.system')}
          </Button>
          <Button
            component={RouterLink}
            to={SUPER_ADMIN_ROUTES.cooperatives}
            variant="contained"
            startIcon={<AddIcon />}
          >
            {t('cooperatives.create')}
          </Button>
        </Stack>
      </Stack>
      ) : null}

      {query.isError ? (
        <Box sx={{ mb: 2 }}>
          <ErrorState
            message={getErrorMessage(query.error)}
            onRetry={() => void query.refetch()}
          />
        </Box>
      ) : null}

      {overviewQuery.isError ? (
        <Box sx={{ mb: 2 }}>
          <ErrorState
            message={getErrorMessage(overviewQuery.error)}
            onRetry={() => void overviewQuery.refetch()}
          />
        </Box>
      ) : null}

      <Grid container spacing={2} sx={{ mb: 3 }}>
        <Grid size={{ xs: 12, sm: 6, md: 4, lg: 3 }}>
          <MetricCard
            scale="dashboard"
            label={t('dashboard.super.totalCooperatives')}
            value={overviewQuery.isLoading && !overview && query.isLoading ? undefined : String(total)}
            hint={
              overview
                ? t('dashboard.super.activeHint', { count: overview.activeCooperatives })
                : undefined
            }
            icon={<AccountBalanceIcon fontSize="small" />}
            accent="blue"
            loading={overviewQuery.isLoading && query.isLoading}
          />
        </Grid>
        <Grid size={{ xs: 12, sm: 6, md: 4, lg: 3 }}>
          <MetricCard
            scale="dashboard"
            label={t('dashboard.super.totalMembers')}
            value={countsReady && overview ? String(overview.totalMembers) : undefined}
            hint={
              countsReady && overview
                ? t('dashboard.super.activeMembersHint', { count: overview.activeMembers })
                : undefined
            }
            icon={<GroupsIcon fontSize="small" />}
            accent="green"
            loading={overviewQuery.isLoading || derivedLoading}
          />
        </Grid>
        <Grid size={{ xs: 12, sm: 6, md: 4, lg: 3 }}>
          <MetricCard
            scale="dashboard"
            label={t('dashboard.super.totalUsers')}
            value={hasLiveOverview && overview ? String(overview.totalUsers) : undefined}
            icon={<PeopleAltIcon fontSize="small" />}
            accent="purple"
            loading={overviewQuery.isLoading}
          />
        </Grid>
        <Grid size={{ xs: 12, sm: 6, md: 4, lg: 3 }}>
          <MetricCard
            scale="dashboard"
            label={t('dashboard.super.pendingReviews')}
            value={
              countsReady && overview
                ? String(overview.pendingContributionReviews + overview.pendingSpecialContributions)
                : undefined
            }
            icon={<HourglassEmptyIcon fontSize="small" />}
            accent="gold"
            loading={overviewQuery.isLoading || derivedLoading}
          />
        </Grid>
        <Grid size={{ xs: 12, sm: 6, md: 4, lg: 3 }}>
          <MetricCard
            scale="dashboard"
            label={t('dashboard.super.pendingLoans')}
            value={hasLiveOverview && overview ? String(overview.pendingLoans) : undefined}
            icon={<HourglassEmptyIcon fontSize="small" />}
            accent="orange"
            loading={overviewQuery.isLoading}
          />
        </Grid>
        <Grid size={{ xs: 12, sm: 6, md: 4, lg: 3 }}>
          <MetricCard
            scale="dashboard"
            label={t('dashboard.super.overdueLoans')}
            value={countsReady && overview ? String(overview.overdueLoans) : undefined}
            icon={<WarningAmberIcon fontSize="small" />}
            accent="red"
            loading={overviewQuery.isLoading || derivedLoading}
          />
        </Grid>
        {superAdminCount !== undefined ? (
          <Grid size={{ xs: 12, sm: 6, md: 4, lg: 3 }}>
            <MetricCard
              scale="dashboard"
              label={t('superAdmin.home.totalSuperAdmins')}
              value={String(superAdminCount)}
              icon={<AdminPanelSettingsIcon fontSize="small" />}
              accent="teal"
            />
          </Grid>
        ) : null}
      </Grid>

      {overview ? <SuperAdminOverviewCharts overview={overview} /> : null}

      <Paper elevation={0} sx={{ p: { xs: 2, md: 3 }, border: '1px solid', borderColor: 'divider' }}>
        <Typography variant="h6" component="h2" gutterBottom sx={dashboardSectionTitleSx}>
          {t('dashboard.super.recentTitle')}
        </Typography>
        {rows.length === 0 && !query.isLoading ? (
          <Typography color="text.secondary">{t('cooperatives.emptyDescription')}</Typography>
        ) : (
          <Stack spacing={1.25} sx={{ mt: 1 }}>
            {rows.map((coop) => (
              <Paper
                key={coop.id}
                elevation={0}
                onClick={() => openCooperative(coop.id)}
                sx={{
                  px: 2,
                  py: 1.5,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  gap: 1,
                  border: '1px solid',
                  borderColor: 'divider',
                  borderRadius: 2,
                  cursor: 'pointer',
                  '&:hover': { borderColor: 'primary.main' },
                }}
              >
                <Box>
                  <Typography variant="body1" sx={{ fontWeight: 600 }}>
                    {coop.name}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {coop.registrationNumber || coop.currency}
                  </Typography>
                </Box>
                <Chip
                  size="small"
                  label={t(`status.${coop.status}`, { defaultValue: coop.status })}
                  color={coop.status === 'ACTIVE' ? 'success' : coop.status === 'SUSPENDED' ? 'warning' : 'default'}
                />
              </Paper>
            ))}
          </Stack>
        )}
      </Paper>
    </Box>
  )
}
