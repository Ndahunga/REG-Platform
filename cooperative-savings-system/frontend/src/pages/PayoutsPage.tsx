import { Box, Tab, Tabs } from '@mui/material'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router-dom'
import { useAppSelector } from '@/app/store/hooks'
import { selectCanPreparePayouts, selectIsSuperAdmin } from '@/app/store/authSlice'
import {
  PayoutHistoryPanel,
  PayoutMyPanel,
  PayoutNewPanel,
} from '@/features/payouts'
import { EmptyState } from '@/shared/components/EmptyState'
import { PageHeader } from '@/shared/components/PageHeader'

export function PayoutsPage() {
  const { t } = useTranslation()
  const [searchParams] = useSearchParams()
  const cooperativeId = useAppSelector((s) => s.auth.selectedCooperativeId)
  const isAdmin = useAppSelector(selectCanPreparePayouts)
  const isSuperAdmin = useAppSelector(selectIsSuperAdmin)

  const tabs = useMemo(() => {
    if (isSuperAdmin) {
      return [
        { key: 'new', label: t('payouts.tabs.new') },
        { key: 'history', label: t('payouts.tabs.history') },
      ]
    }
    if (isAdmin) {
      return [
        { key: 'new', label: t('payouts.tabs.new') },
        { key: 'history', label: t('payouts.tabs.history') },
        { key: 'my', label: t('payouts.tabs.my') },
      ]
    }
    return [{ key: 'my', label: t('payouts.tabs.my') }]
  }, [isAdmin, isSuperAdmin, t])

  const [tab, setTab] = useState(() => {
    const requested = searchParams.get('tab')
    const index = tabs.findIndex((item) => item.key === requested)
    return index >= 0 ? index : 0
  })

  if (!cooperativeId) {
    return (
      <Box>
        <PageHeader
          title={t('pages.payouts.title')}
          description={t('pages.payouts.description')}
        />
        <EmptyState
          title={t('payouts.selectCooperativeTitle')}
          description={t('payouts.selectCooperativeDescription')}
        />
      </Box>
    )
  }

  const activeKey = tabs[tab]?.key ?? 'my'

  return (
    <Box>
      <PageHeader
        title={t('pages.payouts.title')}
        description={t('pages.payouts.description')}
      />

      <Tabs
        value={tab}
        onChange={(_, value: number) => setTab(value)}
        variant="scrollable"
        allowScrollButtonsMobile
        sx={{ mb: 2.5, borderBottom: 1, borderColor: 'divider' }}
      >
        {tabs.map((item) => (
          <Tab key={item.key} label={item.label} />
        ))}
      </Tabs>

      {activeKey === 'new' ? <PayoutNewPanel cooperativeId={cooperativeId} /> : null}
      {activeKey === 'history' ? (
        <PayoutHistoryPanel cooperativeId={cooperativeId} />
      ) : null}
      {activeKey === 'my' ? <PayoutMyPanel cooperativeId={cooperativeId} /> : null}
    </Box>
  )
}
