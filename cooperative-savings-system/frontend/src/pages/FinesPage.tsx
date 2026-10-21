import {
  Alert,
  Box,
  Button,
  Grid,
  Stack,
  Tab,
  Tabs,
} from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link as RouterLink } from 'react-router-dom'
import { useAppSelector } from '@/app/store/hooks'
import { selectCanManageFines, selectIsSuperAdmin } from '@/app/store/authSlice'
import {
  FineGeneratePanel,
  FineIssuePanel,
  FineSettingsPanel,
  FinesListPanel,
} from '@/features/fines'
import { fetchDashboardSummary } from '@/shared/api/dashboard'
import { EmptyState } from '@/shared/components/EmptyState'
import { MetricCard } from '@/shared/components/MetricCard'
import { PageHeader } from '@/shared/components/PageHeader'
import { ROUTES } from '@/shared/constants/routes'

type FineTab = 'mine' | 'all' | 'issue' | 'settings'

function fineTabsForUser(isAdmin: boolean, isSuperAdmin: boolean): FineTab[] {
  if (isSuperAdmin) return ['all', 'issue', 'settings']
  if (isAdmin) return ['mine', 'all', 'issue', 'settings']
  return ['mine']
}

export function FinesPage() {
  const { t } = useTranslation()
  const cooperativeId = useAppSelector((s) => s.auth.selectedCooperativeId)
  const isAdmin = useAppSelector(selectCanManageFines)
  const isSuperAdmin = useAppSelector(selectIsSuperAdmin)
  const tabs = useMemo(
    () => fineTabsForUser(isAdmin, isSuperAdmin),
    [isAdmin, isSuperAdmin],
  )
  const [tab, setTab] = useState(0)
  const active = tabs[tab] ?? tabs[0]

  const summaryQuery = useQuery({
    queryKey: ['dashboard', 'summary', cooperativeId],
    queryFn: () => fetchDashboardSummary(cooperativeId!),
    enabled: Boolean(cooperativeId) && isAdmin,
  })

  const summary = summaryQuery.data

  if (!cooperativeId) {
    return (
      <Box>
        <PageHeader
          title={t('pages.fines.title')}
          description={t('pages.fines.description')}
        />
        <EmptyState
          title={t('fines.selectCooperativeTitle')}
          description={t('fines.selectCooperativeDescription')}
        />
      </Box>
    )
  }

  return (
    <Box>
      <PageHeader
        title={t('pages.fines.title')}
        description={t('pages.fines.description')}
        actions={
          isAdmin ? (
            <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
              <Button
                component={RouterLink}
                to={ROUTES.finePayments}
                variant="outlined"
                size="small"
              >
                {t('fines.openQueue')}
              </Button>
            </Stack>
          ) : null
        }
      />

      {isAdmin ? (
        <>
          <Grid container spacing={2} sx={{ mb: 2 }}>
            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <MetricCard
                label={t('fines.summary.total')}
                value={summary?.totalFines != null ? String(summary.totalFines) : '—'}
                accent="red"
                loading={summaryQuery.isLoading}
              />
            </Grid>
            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <MetricCard
                label={t('fines.summary.unpaid')}
                value={summary?.unpaidFines != null ? String(summary.unpaidFines) : '—'}
                accent="orange"
                loading={summaryQuery.isLoading}
              />
            </Grid>
            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <MetricCard
                label={t('fines.summary.paid')}
                value={summary?.paidFines != null ? String(summary.paidFines) : '—'}
                accent="green"
                loading={summaryQuery.isLoading}
              />
            </Grid>
            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <MetricCard
                label={t('fines.summary.awaitingReview')}
                value={
                  summary?.pendingFinePayments != null
                    ? String(summary.pendingFinePayments)
                    : '—'
                }
                accent="gold"
                loading={summaryQuery.isLoading}
              />
            </Grid>
          </Grid>
          <Alert severity="info" sx={{ mb: 2.5 }}>
            {t('fines.workflowBanner')}
          </Alert>
        </>
      ) : null}

      <Tabs
        value={tab}
        onChange={(_, value: number) => setTab(value)}
        variant="scrollable"
        allowScrollButtonsMobile
        sx={{ mb: 2.5, borderBottom: 1, borderColor: 'divider' }}
      >
        {tabs.map((item) => (
          <Tab key={item} label={t(`fines.tabs.${item}`)} />
        ))}
      </Tabs>

      {active === 'mine' ? (
        <FinesListPanel cooperativeId={cooperativeId} mode="mine" />
      ) : null}

      {active === 'all' ? (
        <Box>
          <FineGeneratePanel cooperativeId={cooperativeId} />
          <FinesListPanel cooperativeId={cooperativeId} mode="all" />
        </Box>
      ) : null}

      {active === 'issue' ? (
        <FineIssuePanel cooperativeId={cooperativeId} />
      ) : null}

      {active === 'settings' ? (
        <FineSettingsPanel cooperativeId={cooperativeId} />
      ) : null}
    </Box>
  )
}
