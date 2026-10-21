import { Box } from '@mui/material'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router-dom'
import { useAppSelector } from '@/app/store/hooks'
import { ReportsExportPanel } from '@/features/reports'
import { EmptyState } from '@/shared/components/EmptyState'
import { PageHeader } from '@/shared/components/PageHeader'

export function ReportsPage() {
  const { t } = useTranslation()
  const [searchParams] = useSearchParams()
  const cooperativeId = useAppSelector((s) => s.auth.selectedCooperativeId)
  const initialReportType = searchParams.get('type') ?? undefined

  if (!cooperativeId) {
    return (
      <Box>
        <PageHeader
          title={t('pages.reports.title')}
          description={t('pages.reports.description')}
        />
        <EmptyState
          title={t('reports.selectCooperativeTitle')}
          description={t('reports.selectCooperativeDescription')}
        />
      </Box>
    )
  }

  return (
    <Box>
      <PageHeader
        title={t('pages.reports.title')}
        description={t('pages.reports.description')}
      />
      <ReportsExportPanel cooperativeId={cooperativeId} initialReportType={initialReportType} />
    </Box>
  )
}
