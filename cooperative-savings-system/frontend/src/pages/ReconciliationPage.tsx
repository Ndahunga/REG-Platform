import { Box } from '@mui/material'
import { useTranslation } from 'react-i18next'
import { useAppSelector } from '@/app/store/hooks'
import { ReconciliationWorkspace } from '@/features/reconciliation/ReconciliationWorkspace'
import { EmptyState } from '@/shared/components/EmptyState'
import { PageHeader } from '@/shared/components/PageHeader'

export function ReconciliationPage() {
  const { t } = useTranslation()
  const cooperativeId = useAppSelector((s) => s.auth.selectedCooperativeId)
  return (
    <Box>
      <PageHeader title={t('pages.reconciliation.title')} description={t('pages.reconciliation.description')} />
      {cooperativeId ? (
        <ReconciliationWorkspace cooperativeId={cooperativeId} />
      ) : (
        <EmptyState title={t('reconciliation.selectCooperativeTitle')} description={t('reconciliation.selectCooperativeDescription')} />
      )}
    </Box>
  )
}
