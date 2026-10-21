import { Box, Button, Tab, Tabs } from '@mui/material'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router-dom'
import { useAppSelector } from '@/app/store/hooks'
import { selectCanWriteSocial, selectIsCooperativeAdmin, selectIsSuperAdmin } from '@/app/store/authSlice'
import {
  SocialContributionsPanel,
  SocialDisbursementsPanel,
  SocialFundOverviewPanel,
  SocialFundReportPanel,
} from '@/features/socialFund'
import { EmptyState } from '@/shared/components/EmptyState'
import { PageHeader } from '@/shared/components/PageHeader'

type SocialTab = 'overview' | 'submit' | 'approvals' | 'list' | 'disbursements' | 'report'

export function SocialFundPage() {
  const { t } = useTranslation()
  const cooperativeId = useAppSelector((s) => s.auth.selectedCooperativeId)
  const isAdmin = useAppSelector(selectIsCooperativeAdmin)
  const canWrite = useAppSelector(selectCanWriteSocial)
  const isSuperAdmin = useAppSelector(selectIsSuperAdmin)

  const tabs = useMemo<SocialTab[]>(() => {
    const items: SocialTab[] = []
    if (isAdmin) items.push('overview')
    if (!isSuperAdmin) items.push('submit')
    if (canWrite) items.push('approvals')
    items.push('list', 'disbursements')
    if (isAdmin) items.push('report')
    return items
  }, [canWrite, isAdmin, isSuperAdmin])

  const [searchParams] = useSearchParams()
  const [tab, setTab] = useState(() => {
    const requested = searchParams.get('tab')
    if (requested === 'approvals') {
      const index = tabs.indexOf('approvals')
      return index >= 0 ? index : 0
    }
    if (isSuperAdmin) return Math.max(0, tabs.indexOf('overview'))
    const submitIndex = tabs.indexOf('submit')
    return submitIndex >= 0 ? submitIndex : 0
  })
  const active = tabs[tab] ?? tabs[0]

  if (!cooperativeId) {
    return (
      <Box>
        <PageHeader
          title={t('pages.socialFund.title')}
          description={t('pages.socialFund.description')}
        />
        <EmptyState
          title={t('socialFund.selectCooperativeTitle')}
          description={t('socialFund.selectCooperativeDescription')}
        />
      </Box>
    )
  }

  return (
    <Box>
      <PageHeader
        title={t('pages.socialFund.title')}
        description={t('pages.socialFund.description')}
        actions={
          isSuperAdmin ? null : (
            <Button
              variant="contained"
              size="small"
              onClick={() => setTab(Math.max(0, tabs.indexOf('submit')))}
            >
              {t('socialFund.contributions.submitAction')}
            </Button>
          )
        }
      />

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
              item === 'submit'
                ? t('socialFund.tabs.submit')
                : item === 'approvals'
                  ? t('socialFund.tabs.approvals')
                  : item === 'list'
                    ? t('socialFund.tabs.contributions')
                    : t(`socialFund.tabs.${item}`)
            }
          />
        ))}
      </Tabs>

      {active === 'overview' ? (
        <SocialFundOverviewPanel cooperativeId={cooperativeId} isAdmin={isAdmin} />
      ) : null}

      {active === 'submit' ? (
        <SocialContributionsPanel
          cooperativeId={cooperativeId}
          isAdmin={isAdmin}
          canWrite={canWrite}
        />
      ) : null}

      {active === 'approvals' ? (
        <SocialContributionsPanel
          cooperativeId={cooperativeId}
          isAdmin
          canWrite={canWrite}
          defaultStatus="PENDING"
          hideSubmit
        />
      ) : null}

      {active === 'list' ? (
        <SocialContributionsPanel
          cooperativeId={cooperativeId}
          isAdmin={isAdmin}
          canWrite={canWrite}
          hideSubmit
        />
      ) : null}

      {active === 'disbursements' ? (
        <SocialDisbursementsPanel cooperativeId={cooperativeId} isAdmin={isAdmin} />
      ) : null}

      {active === 'report' ? <SocialFundReportPanel cooperativeId={cooperativeId} /> : null}
    </Box>
  )
}
