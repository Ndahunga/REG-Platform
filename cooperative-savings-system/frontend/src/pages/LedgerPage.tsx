import { Box } from '@mui/material'
import { useTranslation } from 'react-i18next'
import { useAppSelector } from '@/app/store/hooks'
import { selectIsCooperativeAdmin } from '@/app/store/authSlice'
import { LedgerPanel } from '@/features/transactions'
import { EmptyState } from '@/shared/components/EmptyState'
import { PageHeader } from '@/shared/components/PageHeader'

export function LedgerPage() {
  const { t } = useTranslation()
  const cooperativeId = useAppSelector((s) => s.auth.selectedCooperativeId)
  const isAdmin = useAppSelector(selectIsCooperativeAdmin)

  if (!cooperativeId) {
    return (
      <Box>
        <PageHeader
          title={t('pages.ledger.title')}
          description={t('pages.ledger.description')}
        />
        <EmptyState
          title={t('ledger.selectCooperativeTitle')}
          description={t('ledger.selectCooperativeDescription')}
        />
      </Box>
    )
  }

  if (!isAdmin) {
    return (
      <Box>
        <PageHeader
          title={t('pages.ledger.title')}
          description={t('pages.ledger.description')}
        />
        <EmptyState
          title={t('ledger.adminOnlyTitle')}
          description={t('ledger.adminOnlyDescription')}
        />
      </Box>
    )
  }

  return (
    <Box>
      <PageHeader
        title={t('pages.ledger.title')}
        description={t('pages.ledger.description')}
      />
      <LedgerPanel cooperativeId={cooperativeId} />
    </Box>
  )
}
