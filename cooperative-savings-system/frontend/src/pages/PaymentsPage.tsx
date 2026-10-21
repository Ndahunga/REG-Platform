import { Alert, Box, Stack, Tab, Tabs } from '@mui/material'
import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router-dom'
import { selectIsSuperAdmin } from '@/app/store/authSlice'
import { useAppSelector } from '@/app/store/hooks'
import { LegacyReconciliationPanel } from '@/features/payments/LegacyReconciliationPanel'
import { ModelSummaryPanel } from '@/features/payments/ModelSummaryPanel'
import { ModelReportPanel } from '@/features/reports/ModelReportPanel'
import { TrustedTransactionsPanel } from '@/features/payments/TrustedTransactionsPanel'
import { MemberCreditPanel } from '@/features/payments/MemberCreditPanel'
import { PaymentAccountsPanel } from '@/features/payments/PaymentAccountsPanel'
import { PaymentListPanel, type PaymentListSource } from '@/features/payments/PaymentListPanel'
import { PaymentSubmitPanel } from '@/features/payments/PaymentSubmitPanel'
import { EmptyState } from '@/shared/components/EmptyState'
import { PageHeader } from '@/shared/components/PageHeader'
import {
  PERMISSION_CONTRIBUTION_WRITE,
  PERMISSION_LOAN_WRITE,
  PERMISSION_SETTINGS_MANAGE,
  hasPermission,
} from '@/shared/types/auth'

type PaymentTab = 'submit' | 'summary' | 'report' | 'reconciliation' | PaymentListSource

/**
 * The tabs a user may see: members submit and follow their own payments, officers review, verify and post, and
 * reconcile evidence (statement lines, conflicts, legacy rotating contributions).
 */
export function paymentTabsFor(options: { canSubmit: boolean; isStaff: boolean }): PaymentTab[] {
  const tabs: PaymentTab[] = []
  if (options.canSubmit) tabs.push('submit', 'mine')
  if (options.isStaff) tabs.push('review', 'verified', 'posted', 'all', 'reconciliation', 'summary', 'report')
  return tabs
}

/**
 * Combined payments. A submitted payment is only a claim: nothing changes in contributions or loans until officers
 * verify the funds and a different officer posts the payment. Super Admin supports schemes but is not a member, so it
 * gets the officer views only. The backend enforces every permission again.
 */
export function PaymentsPage() {
  const { t } = useTranslation()
  const cooperativeId = useAppSelector((s) => s.auth.selectedCooperativeId)
  const user = useAppSelector((s) => s.auth.user)
  const isSuperAdmin = useAppSelector(selectIsSuperAdmin)
  const [params, setParams] = useSearchParams()

  const isStaff = hasPermission(user, PERMISSION_CONTRIBUTION_WRITE) || hasPermission(user, PERMISSION_LOAN_WRITE)
  const canSubmit = !isSuperAdmin
  const canManageAccounts =
    isSuperAdmin || hasPermission(user, PERMISSION_SETTINGS_MANAGE) || hasPermission(user, 'COOPERATIVE_WRITE')
  const tabs = useMemo(() => paymentTabsFor({ canSubmit, isStaff }), [canSubmit, isStaff])

  const requested = params.get('tab') as PaymentTab | null
  const active: PaymentTab | undefined = requested && tabs.includes(requested) ? requested : tabs[0]

  const header = <PageHeader title={t('pages.payments.title')} description={t('pages.payments.description')} />

  if (!cooperativeId) {
    return (
      <Box>
        {header}
        <EmptyState title={t('payments.selectSchemeTitle')} description={t('payments.selectSchemeDescription')} />
      </Box>
    )
  }
  if (tabs.length === 0 || !active) {
    return (
      <Box>
        {header}
        <Alert severity="info">{t('payments.noAccess')}</Alert>
      </Box>
    )
  }

  const select = (tab: PaymentTab) => {
    setParams((previous) => {
      const next = new URLSearchParams(previous)
      if (tab === tabs[0]) next.delete('tab')
      else next.set('tab', tab)
      return next
    })
  }

  return (
    <Box>
      {header}
      <Tabs
        value={active}
        onChange={(_, value: PaymentTab) => select(value)}
        variant="scrollable"
        scrollButtons="auto"
        aria-label={t('pages.payments.title')}
        sx={{ mb: 2 }}
      >
        {tabs.map((tab) => (
          <Tab key={tab} value={tab} label={t(`payments.tabs.${tab}`)} />
        ))}
      </Tabs>
      {active === 'submit' ? (
        <PaymentSubmitPanel cooperativeId={cooperativeId} onViewPayments={() => select('mine')} />
      ) : active === 'summary' ? (
        <ModelSummaryPanel cooperativeId={cooperativeId} />
      ) : active === 'report' ? (
        <ModelReportPanel cooperativeId={cooperativeId} />
      ) : active === 'reconciliation' ? (
        <Stack spacing={4}>
          {canManageAccounts ? <PaymentAccountsPanel cooperativeId={cooperativeId} /> : null}
          <MemberCreditPanel cooperativeId={cooperativeId} />
          <TrustedTransactionsPanel cooperativeId={cooperativeId} />
          <LegacyReconciliationPanel cooperativeId={cooperativeId} />
        </Stack>
      ) : (
        <PaymentListPanel key={active} cooperativeId={cooperativeId} source={active} />
      )}
    </Box>
  )
}
