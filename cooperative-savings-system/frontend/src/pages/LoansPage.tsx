import { Box, Grid, Tab, Tabs } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router-dom'
import { useAppSelector } from '@/app/store/hooks'
import { selectCanManageLoans, selectIsSuperAdmin } from '@/app/store/authSlice'
import {
  LoanRequestPanel,
  LoanSettingsPanel,
  LoansListPanel,
  GuarantorRequestsPanel,
} from '@/features/loans'
import { fetchDashboardSummary } from '@/shared/api/dashboard'
import { fetchLoans } from '@/shared/api/loans'
import { EmptyState } from '@/shared/components/EmptyState'
import { MetricCard } from '@/shared/components/MetricCard'
import { PageHeader } from '@/shared/components/PageHeader'
import { SubscriptionAwareButton } from '@/shared/components/SubscriptionAwareButton'

type LoanTab = 'mine' | 'approvals' | 'all' | 'issue' | 'guarantor' | 'settings'

function loanTabsForUser(isAdmin: boolean, isSuperAdmin: boolean): LoanTab[] {
  if (isSuperAdmin) return ['approvals', 'all', 'issue', 'guarantor', 'settings']
  if (isAdmin) return ['mine', 'approvals', 'all', 'issue', 'guarantor', 'settings']
  return ['mine', 'issue', 'guarantor']
}

export function LoansPage() {
  const { t } = useTranslation()
  const cooperativeId = useAppSelector((s) => s.auth.selectedCooperativeId)
  const isAdmin = useAppSelector(selectCanManageLoans)
  const isSuperAdmin = useAppSelector(selectIsSuperAdmin)
  const tabs = useMemo(
    () => loanTabsForUser(isAdmin, isSuperAdmin),
    [isAdmin, isSuperAdmin],
  )
  const [searchParams] = useSearchParams()
  const [tab, setTab] = useState(() => {
    const requested = searchParams.get('tab')
    if (requested === 'approvals') return Math.max(0, tabs.indexOf('approvals'))
    if (requested === 'request' || requested === 'issue') {
      return Math.max(0, tabs.indexOf('issue'))
    }
    return 0
  })
  const active = tabs[tab] ?? tabs[0]

  const summaryQuery = useQuery({
    queryKey: ['dashboard', 'summary', cooperativeId],
    queryFn: () => fetchDashboardSummary(cooperativeId!),
    enabled: Boolean(cooperativeId) && isAdmin,
  })

  const pendingQuery = useQuery({
    queryKey: ['loans', cooperativeId, 'count-pending'],
    queryFn: () => fetchLoans(cooperativeId!, { pendingApproval: true, size: 1 }),
    enabled: Boolean(cooperativeId) && isAdmin,
  })

  const activeQuery = useQuery({
    queryKey: ['loans', cooperativeId, 'count-active'],
    queryFn: () => fetchLoans(cooperativeId!, { status: 'ACTIVE', size: 1 }),
    enabled: Boolean(cooperativeId) && isAdmin,
  })

  const closedQuery = useQuery({
    queryKey: ['loans', cooperativeId, 'count-closed'],
    queryFn: () => fetchLoans(cooperativeId!, { status: 'CLOSED', size: 1 }),
    enabled: Boolean(cooperativeId) && isAdmin,
  })

  if (!cooperativeId) {
    return (
      <Box>
        <PageHeader
          title={t('pages.loans.title')}
          description={t('pages.loans.description')}
        />
        <EmptyState
          title={t('loans.selectCooperativeTitle')}
          description={t('loans.selectCooperativeDescription')}
        />
      </Box>
    )
  }

  return (
    <Box>
      <PageHeader
        title={t('pages.loans.title')}
        description={t('pages.loans.description')}
        actions={
          isAdmin ? (
            <SubscriptionAwareButton
              variant="outlined"
              size="small"
              onClick={() => setTab(Math.max(0, tabs.indexOf('issue')))}
            >
              {t('loans.tabs.issue')}
            </SubscriptionAwareButton>
          ) : (
            <SubscriptionAwareButton
              variant="contained"
              size="small"
              onClick={() => setTab(Math.max(0, tabs.indexOf('issue')))}
            >
              {t('loans.request.apply')}
            </SubscriptionAwareButton>
          )
        }
      />

      {isAdmin ? (
        <Grid container spacing={2} sx={{ mb: 2.5 }}>
          <Grid size={{ xs: 12, sm: 6, md: 3 }}>
            <MetricCard
              label={t('loans.summary.pending', { defaultValue: 'Pending loans' })}
              value={
                pendingQuery.data?.totalElements != null
                  ? String(pendingQuery.data.totalElements)
                  : '—'
              }
              accent="gold"
              loading={pendingQuery.isLoading}
            />
          </Grid>
          <Grid size={{ xs: 12, sm: 6, md: 3 }}>
            <MetricCard
              label={t('loans.summary.active', { defaultValue: 'Active loans' })}
              value={
                activeQuery.data?.totalElements != null
                  ? String(activeQuery.data.totalElements)
                  : '—'
              }
              accent="blue"
              loading={activeQuery.isLoading}
            />
          </Grid>
          <Grid size={{ xs: 12, sm: 6, md: 3 }}>
            <MetricCard
              label={t('dashboard.metrics.overdueLoans')}
              value={
                summaryQuery.data?.overdueLoansCount != null
                  ? String(summaryQuery.data.overdueLoansCount)
                  : '—'
              }
              accent="red"
              loading={summaryQuery.isLoading}
            />
          </Grid>
          <Grid size={{ xs: 12, sm: 6, md: 3 }}>
            <MetricCard
              label={t('loans.summary.closed', { defaultValue: 'Closed loans' })}
              value={
                closedQuery.data?.totalElements != null
                  ? String(closedQuery.data.totalElements)
                  : '—'
              }
              accent="neutral"
              loading={closedQuery.isLoading}
            />
          </Grid>
        </Grid>
      ) : null}

      <Tabs
        value={tab}
        onChange={(_, value: number) => setTab(value)}
        variant="scrollable"
        allowScrollButtonsMobile
        sx={{ mb: 2.5, borderBottom: 1, borderColor: 'divider' }}
      >
        {tabs.map((item) => (
          <Tab
            key={item}
            label={
              item === 'issue'
                ? t(isAdmin ? 'loans.tabs.issue' : 'loans.tabs.request')
                : t(`loans.tabs.${item}`)
            }
          />
        ))}
      </Tabs>

      {active === 'mine' ? (
        <LoansListPanel cooperativeId={cooperativeId} mode="mine" />
      ) : null}

      {active === 'approvals' ? (
        <LoansListPanel cooperativeId={cooperativeId} mode="approvals" />
      ) : null}

      {active === 'all' ? (
        <LoansListPanel cooperativeId={cooperativeId} mode="all" />
      ) : null}

      {active === 'issue' ? (
        <LoanRequestPanel cooperativeId={cooperativeId} isAdmin={isAdmin} />
      ) : null}

      {active === 'guarantor' ? (
        <GuarantorRequestsPanel cooperativeId={cooperativeId} />
      ) : null}

      {active === 'settings' ? (
        <LoanSettingsPanel cooperativeId={cooperativeId} />
      ) : null}
    </Box>
  )
}
