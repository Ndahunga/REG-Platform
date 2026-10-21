import { Alert, Box, Tab, Tabs } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { selectIsLeadership, selectIsSuperAdmin } from '@/app/store/authSlice'
import { useAppSelector } from '@/app/store/hooks'
import {
  RotationConfigPanel,
  RotationEntitlementsPanel,
  RotationObligationsPanel,
  RotationPoolPanel,
  RotationPayoutsPanel,
  RotationSchedulePanel,
} from '@/features/rotation'
import { fetchCooperative } from '@/shared/api/cooperatives'
import { getErrorMessage } from '@/shared/api/client'
import { EmptyState } from '@/shared/components/EmptyState'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { PageHeader } from '@/shared/components/PageHeader'
import { hasPermission } from '@/shared/types/auth'

/**
 * Rotation schedule and configuration. Visible to every member of the scheme; management actions need Super Admin or
 * the President / Vice President with COOPERATIVE_WRITE (the server enforces the same rule). Rotation exists only for
 * Rotating and Hybrid schemes; other schemes get an explanation instead.
 */
export function RotationPage() {
  const { t } = useTranslation()
  const cooperativeId = useAppSelector((s) => s.auth.selectedCooperativeId)
  const user = useAppSelector((s) => s.auth.user)
  const isLeadership = useAppSelector(selectIsLeadership)
  const isSuperAdmin = useAppSelector(selectIsSuperAdmin)
  const [tab, setTab] = useState(0)

  const schemeQuery = useQuery({
    queryKey: ['cooperatives', cooperativeId],
    queryFn: () => fetchCooperative(cooperativeId as string),
    enabled: Boolean(cooperativeId),
  })

  const header = <PageHeader title={t('pages.rotation.title')} description={t('pages.rotation.description')} />

  if (!cooperativeId) {
    return (
      <Box>
        {header}
        <EmptyState title={t('rotation.selectSchemeTitle')} description={t('rotation.selectSchemeDescription')} />
      </Box>
    )
  }
  if (schemeQuery.isLoading) {
    return (
      <Box>
        {header}
        <LoadingState />
      </Box>
    )
  }
  if (schemeQuery.isError || !schemeQuery.data) {
    return (
      <Box>
        {header}
        <ErrorState message={getErrorMessage(schemeQuery.error)} onRetry={() => void schemeQuery.refetch()} />
      </Box>
    )
  }

  const model = schemeQuery.data.operatingModel
  if (!model) {
    return (
      <Box>
        {header}
        <Alert severity="warning" data-testid="rotation-model-missing">
          {t('rotation.modelMissing')}
        </Alert>
      </Box>
    )
  }
  if (model === 'ACCUMULATING') {
    return (
      <Box>
        {header}
        <Alert severity="info" data-testid="rotation-not-applicable">
          {t('rotation.notApplicable')}
        </Alert>
      </Box>
    )
  }

  const canManage = (isSuperAdmin || isLeadership) && hasPermission(user, 'COOPERATIVE_WRITE')
  // Officers who handle payouts see every period and payout; a member sees only their own.
  const payoutStaff = hasPermission(user, 'PAYOUT_WRITE')

  return (
    <Box>
      {header}
      <Tabs value={tab} onChange={(_, value: number) => setTab(value)} sx={{ mb: 2 }} variant="scrollable">
        <Tab label={t('rotation.tabs.schedule')} />
        <Tab label={t('rotation.tabs.configuration')} />
        <Tab label={t('rotation.tabs.entitlements')} />
        <Tab label={t('rotation.tabs.payouts')} />
        <Tab label={t('rotation.tabs.obligations')} />
        {payoutStaff ? <Tab label={t('rotation.tabs.pool')} /> : null}
      </Tabs>
      {tab === 0 ? <RotationSchedulePanel cooperativeId={cooperativeId} canManage={canManage} /> : null}
      {tab === 1 ? <RotationConfigPanel cooperativeId={cooperativeId} model={model} canManage={canManage} /> : null}
      {tab === 2 ? <RotationEntitlementsPanel cooperativeId={cooperativeId} staff={payoutStaff} /> : null}
      {tab === 3 ? <RotationPayoutsPanel cooperativeId={cooperativeId} staff={payoutStaff} /> : null}
      {tab === 4 ? <RotationObligationsPanel cooperativeId={cooperativeId} staff={payoutStaff} /> : null}
      {tab === 5 && payoutStaff ? <RotationPoolPanel cooperativeId={cooperativeId} /> : null}
    </Box>
  )
}
