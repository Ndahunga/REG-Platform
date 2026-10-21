import { Box, Tab, Tabs } from '@mui/material'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router-dom'
import { useAppSelector } from '@/app/store/hooks'
import { selectIsCooperativeAdmin } from '@/app/store/authSlice'
import {
  InvestmentCreatePanel,
  InvestmentsListPanel,
} from '@/features/investments'
import { EmptyState } from '@/shared/components/EmptyState'
import { PageHeader } from '@/shared/components/PageHeader'

export function InvestmentsPage() {
  const { t } = useTranslation()
  const [searchParams] = useSearchParams()
  const cooperativeId = useAppSelector((s) => s.auth.selectedCooperativeId)
  const isAdmin = useAppSelector(selectIsCooperativeAdmin)
  const [tab, setTab] = useState(() => (searchParams.get('action') === 'create' ? 1 : 0))

  if (!cooperativeId) {
    return (
      <Box>
        <PageHeader
          title={t('pages.investments.title')}
          description={t('pages.investments.description')}
        />
        <EmptyState
          title={t('investments.selectCooperativeTitle')}
          description={t('investments.selectCooperativeDescription')}
        />
      </Box>
    )
  }

  if (!isAdmin) {
    return (
      <Box>
        <PageHeader
          title={t('pages.investments.title')}
          description={t('pages.investments.description')}
        />
        <EmptyState
          title={t('investments.adminOnlyTitle')}
          description={t('investments.adminOnlyDescription')}
        />
      </Box>
    )
  }

  return (
    <Box>
      <PageHeader
        title={t('pages.investments.title')}
        description={t('pages.investments.description')}
      />

      <Tabs
        value={tab}
        onChange={(_, value: number) => setTab(value)}
        variant="scrollable"
        allowScrollButtonsMobile
        sx={{ mb: 2.5, borderBottom: 1, borderColor: 'divider' }}
      >
        <Tab label={t('investments.tabs.list')} />
        <Tab label={t('investments.tabs.create')} />
      </Tabs>

      {tab === 0 ? <InvestmentsListPanel cooperativeId={cooperativeId} /> : null}
      {tab === 1 ? <InvestmentCreatePanel cooperativeId={cooperativeId} /> : null}
    </Box>
  )
}
