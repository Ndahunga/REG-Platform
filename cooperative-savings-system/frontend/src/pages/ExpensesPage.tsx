import { Box } from '@mui/material'
import { useTranslation } from 'react-i18next'
import { useAppSelector } from '@/app/store/hooks'
import { ExpensesWorkspace } from '@/features/expenses/ExpensesWorkspace'
import { EmptyState } from '@/shared/components/EmptyState'
import { PageHeader } from '@/shared/components/PageHeader'

export function ExpensesPage() {
  const { t } = useTranslation()
  const cooperativeId = useAppSelector((s) => s.auth.selectedCooperativeId)
  return (
    <Box>
      <PageHeader title={t('pages.expenses.title')} description={t('pages.expenses.description')} />
      {cooperativeId ? (
        <ExpensesWorkspace cooperativeId={cooperativeId} />
      ) : (
        <EmptyState title={t('expenses.selectCooperativeTitle')} description={t('expenses.selectCooperativeDescription')} />
      )}
    </Box>
  )
}
