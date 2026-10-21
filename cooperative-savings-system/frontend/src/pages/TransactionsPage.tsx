import { Box, Tab, Tabs } from '@mui/material'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useAppSelector } from '@/app/store/hooks'
import { selectIsCooperativeAdmin } from '@/app/store/authSlice'
import {
  LedgerPanel,
  TransactionCreatePanel,
  TransactionsListPanel,
} from '@/features/transactions'
import { EmptyState } from '@/shared/components/EmptyState'
import { PageHeader } from '@/shared/components/PageHeader'

export function TransactionsPage() {
  const { t } = useTranslation()
  const cooperativeId = useAppSelector((s) => s.auth.selectedCooperativeId)
  const isAdmin = useAppSelector(selectIsCooperativeAdmin)
  const [tab, setTab] = useState(0)

  if (!cooperativeId) {
    return (
      <Box>
        <PageHeader
          title={t('pages.transactions.title')}
          description={t('pages.transactions.description')}
        />
        <EmptyState
          title={t('transactions.selectCooperativeTitle')}
          description={t('transactions.selectCooperativeDescription')}
        />
      </Box>
    )
  }

  if (!isAdmin) {
    return (
      <Box>
        <PageHeader
          title={t('pages.transactions.title')}
          description={t('pages.transactions.description')}
        />
        <EmptyState
          title={t('transactions.adminOnlyTitle')}
          description={t('transactions.adminOnlyDescription')}
        />
      </Box>
    )
  }

  return (
    <Box>
      <PageHeader
        title={t('pages.transactions.title')}
        description={t('pages.transactions.description')}
      />

      <Tabs
        value={tab}
        onChange={(_, value: number) => setTab(value)}
        variant="scrollable"
        allowScrollButtonsMobile
        sx={{ mb: 2.5, borderBottom: 1, borderColor: 'divider' }}
      >
        <Tab label={t('transactions.tabs.list')} />
        <Tab label={t('transactions.tabs.create')} />
        <Tab label={t('transactions.tabs.ledger')} />
      </Tabs>

      {tab === 0 ? (
        <TransactionsListPanel cooperativeId={cooperativeId} isAdmin={isAdmin} />
      ) : null}
      {tab === 1 ? <TransactionCreatePanel cooperativeId={cooperativeId} /> : null}
      {tab === 2 ? <LedgerPanel cooperativeId={cooperativeId} /> : null}
    </Box>
  )
}
