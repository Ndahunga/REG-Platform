import { Box, Stack, Tab, Tabs } from '@mui/material'
import { useCallback, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { useParams, useSearchParams } from 'react-router-dom'
import { useAppSelector } from '@/app/store/hooks'
import {
  selectCanRecordContributions,
  selectCanReviewSharePurchases,
  selectIsSuperAdmin,
} from '@/app/store/authSlice'
import {
  ContributionApprovalsPanel,
  HistoryPanel,
  MemberContributionSubmitPanel,
  MonthlyEntryPanel,
  SpecialCampaignsPanel,
} from '@/features/contributions'
import { SharePurchaseApprovalsPanel } from '@/features/shares'
import {
  contributionTabsForUser,
  type ContributionTab,
} from '@/features/contributions/contributionHelpers'
import { EmptyState } from '@/shared/components/EmptyState'
import { PageHeader } from '@/shared/components/PageHeader'
import { SubscriptionAwareButton } from '@/shared/components/SubscriptionAwareButton'
import { ROUTES } from '@/shared/constants/routes'

/** An unknown or not-permitted ?tab= value safely falls back to the first tab the user is allowed to see. */
function activeTabFromQuery(tabParam: string | null, tabs: ContributionTab[]): number {
  const requested = tabParam === 'mine' ? 'history' : tabParam
  if (requested && tabs.includes(requested as ContributionTab)) {
    return tabs.indexOf(requested as ContributionTab)
  }
  return 0
}

export function ContributionsPage() {
  const { t } = useTranslation()
  const { campaignId } = useParams()
  const [searchParams, setSearchParams] = useSearchParams()
  const cooperativeId = useAppSelector((s) => s.auth.selectedCooperativeId)
  const canRecord = useAppSelector(selectCanRecordContributions)
  const canReviewShares = useAppSelector(selectCanReviewSharePurchases)
  const isSuperAdmin = useAppSelector(selectIsSuperAdmin)
  const tabs = useMemo(
    () => contributionTabsForUser(canRecord, isSuperAdmin, canReviewShares),
    [canRecord, isSuperAdmin, canReviewShares],
  )
  // The URL is the source of truth, so sidebar links and notification links that only change ?tab= switch
  // the visible tab (and the active sidebar item stays in step with what is shown).
  const tab = activeTabFromQuery(searchParams.get('tab'), tabs)
  const active = tabs[tab] ?? tabs[0]

  const selectTab = useCallback(
    (item: ContributionTab) => {
      setSearchParams((previous) => {
        const next = new URLSearchParams(previous)
        if (item === tabs[0]) {
          next.delete('tab')
        } else {
          next.set('tab', item)
        }
        return next
      })
    },
    [setSearchParams, tabs],
  )

  if (!cooperativeId) {
    return (
      <Box>
        <PageHeader
          title={t('pages.contributions.title')}
          description={t('pages.contributions.description')}
        />
        <EmptyState
          title={t('contributions.selectCooperativeTitle')}
          description={t('contributions.selectCooperativeDescription')}
        />
      </Box>
    )
  }

  if (campaignId) {
    return (
      <Box>
        <PageHeader
          title={t('pages.contributions.title')}
          description={t('contributions.campaigns.detailDescription')}
          backTo={ROUTES.contributions}
        />
        <SpecialCampaignsPanel
          cooperativeId={cooperativeId}
          canWrite={canRecord}
          campaignId={campaignId}
        />
      </Box>
    )
  }

  return (
    <Box>
      <PageHeader
        title={t('pages.contributions.title')}
        description={t('pages.contributions.description')}
        actions={
          <Stack
            direction="row"
            spacing={1}
            useFlexGap
            sx={{ flexWrap: 'wrap' }}
          >
            {!isSuperAdmin ? (
              <SubscriptionAwareButton
                variant="contained"
                size="small"
                onClick={() => selectTab('submit')}
              >
                {t('contributions.submit.action')}
              </SubscriptionAwareButton>
            ) : null}
            {canRecord ? (
              <SubscriptionAwareButton
                variant="outlined"
                size="small"
                onClick={() => selectTab('approvals')}
              >
                {t('contributions.tabs.approvals')}
              </SubscriptionAwareButton>
            ) : null}
            {tabs.includes('share-approvals') ? (
              <SubscriptionAwareButton
                variant="outlined"
                size="small"
                onClick={() => selectTab('share-approvals')}
              >
                {t('contributions.tabs.share-approvals')}
              </SubscriptionAwareButton>
            ) : null}
          </Stack>
        }
      />

      <Tabs
        value={tab}
        onChange={(_, value: number) => selectTab(tabs[value])}
        variant="scrollable"
        allowScrollButtonsMobile
        sx={{ mb: 2.5, borderBottom: 1, borderColor: 'divider' }}
      >
        {tabs.map((item) => (
          <Tab
            key={item}
            label={
              item === 'history' && !canRecord
                ? t('contributions.tabs.mine')
                : t(`contributions.tabs.${item}`)
            }
          />
        ))}
      </Tabs>

      {active === 'monthly' ? (
        <MonthlyEntryPanel cooperativeId={cooperativeId} canWrite={canRecord} />
      ) : null}
      {active === 'submit' ? (
        <MemberContributionSubmitPanel cooperativeId={cooperativeId} />
      ) : null}
      {active === 'approvals' ? (
        <ContributionApprovalsPanel cooperativeId={cooperativeId} />
      ) : null}
      {active === 'share-approvals' ? (
        <SharePurchaseApprovalsPanel cooperativeId={cooperativeId} />
      ) : null}
      {active === 'history' ? (
        <HistoryPanel cooperativeId={cooperativeId} isAdmin={canRecord} />
      ) : null}
      {active === 'special' ? (
        <SpecialCampaignsPanel cooperativeId={cooperativeId} canWrite={canRecord} />
      ) : null}
    </Box>
  )
}
