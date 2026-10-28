import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  Typography,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ErrorNotice } from '@/features/payments/PaymentParts'
import { isPositiveAmount } from '@/features/payments/paymentHelpers'
import {
  fetchEntitlements,
  fetchMyEntitlements,
  fetchPayoutEligibility,
  fetchRecoveries,
  requestRotationPayout,
} from '@/shared/api/rotationPayouts'
import { getErrorMessage } from '@/shared/api/client'
import { ErrorState } from '@/shared/components/ErrorState'
import { FinancialActionButton } from '@/shared/components/FinancialActionButton'
import { LoadingState } from '@/shared/components/LoadingState'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import type { PeriodEntitlement, PeriodPayoutEligibility } from '@/shared/types/rotationPayout'
import { formatMoney } from '@/shared/utils/formatMoney'
import { MoneyField } from '@/shared/components/MoneyField'

interface RotationEntitlementsPanelProps {
  cooperativeId: string
  /** Officers see every period; a member sees only the periods where they are the original scheduled recipient. */
  staff: boolean
}

/** Reasons that are not worth a line under every row (the period is closed, or the viewer is not an officer). */
const QUIET_REASONS = ['NOT_AUTHORIZED', 'PERIOD_NOT_PAYABLE']

const monthLabel = (year: number, month: number) => `${year}-${String(month).padStart(2, '0')}`

function newKey(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `payout-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

/**
 * What each rotation period's recipient is owed, from money that was really collected. Expected contributions are
 * shown but are never cash; arrears (expected minus collected) are a contribution shortfall kept apart from the
 * entitlement. A late recovery raises only the original recipient's entitlement, never another recipient's.
 */
export function RotationEntitlementsPanel({ cooperativeId, staff }: RotationEntitlementsPanelProps) {
  const { t } = useTranslation()
  const [requestFor, setRequestFor] = useState<PeriodEntitlement | null>(null)
  const [recoveriesFor, setRecoveriesFor] = useState<PeriodEntitlement | null>(null)

  const query = useQuery({
    queryKey: ['rotation', cooperativeId, 'entitlements', staff ? 'all' : 'mine'],
    queryFn: () => (staff ? fetchEntitlements(cooperativeId) : fetchMyEntitlements(cooperativeId)),
  })

  // Whether a payout can be requested is the backend's decision (it knows the pool reserve, the funds and the rules).
  const eligibilityQuery = useQuery({
    queryKey: ['rotation', cooperativeId, 'payout-eligibility'],
    queryFn: () => fetchPayoutEligibility(cooperativeId),
    enabled: staff,
  })
  const eligibilityByPeriod = new Map<string, PeriodPayoutEligibility>(
    (eligibilityQuery.data ?? []).map((e) => [e.periodId, e]),
  )

  if (query.isLoading) return <LoadingState variant="skeleton" rows={4} />
  if (query.isError) {
    return <ErrorState message={getErrorMessage(query.error, t('errors.generic'))} onRetry={() => void query.refetch()} />
  }
  const rows = query.data ?? []
  const money = (value: string | number) => formatMoney(value)

  const columns: TableColumn<PeriodEntitlement>[] = [
    { id: 'period', label: t('rotation.entitlements.period'), render: (r) => monthLabel(r.year, r.month) },
    { id: 'recipient', label: t('rotation.entitlements.recipient'), render: (r) => r.recipientName || r.recipientUserId },
    { id: 'expected', label: t('rotation.entitlements.expected'), render: (r) => money(r.expectedRotating), hideOnMobile: true },
    { id: 'collected', label: t('rotation.entitlements.collected'), render: (r) => money(r.collectedRotating) },
    {
      id: 'unreconciled',
      label: t('rotation.entitlements.unreconciled'),
      render: (r) => money(r.unreconciledRotating),
      hideOnMobile: true,
    },
    { id: 'arrears', label: t('rotation.entitlements.arrears'), render: (r) => money(r.rotatingArrears), hideOnMobile: true },
    { id: 'distributed', label: t('rotation.entitlements.distributed'), render: (r) => money(r.distributed), hideOnMobile: true },
    {
      id: 'outstanding',
      label: t('rotation.entitlements.outstanding'),
      render: (r) => (
        <Stack spacing={0.5}>
          <Typography variant="body2" sx={{ fontWeight: 600 }} data-testid="entitlement-outstanding">
            {money(r.outstandingEntitlement)}
          </Typography>
          {r.periodStatus === 'CANCELLED' ? <Chip size="small" color="warning" label={t('rotation.entitlements.cancelled')} /> : null}
        </Stack>
      ),
    },
    {
      id: 'actions',
      label: '',
      render: (r) => (
        <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
          <Button size="small" onClick={() => setRecoveriesFor(r)}>
            {t('rotation.entitlements.recoveries')}
          </Button>
          {eligibilityByPeriod.get(r.periodId)?.canRequestPayout ? (
            <Button size="small" variant="contained" onClick={() => setRequestFor(r)}>
              {t('rotation.entitlements.requestPayout')}
            </Button>
          ) : null}
          {staff && eligibilityByPeriod.get(r.periodId)?.payoutBlockedReason &&
          !QUIET_REASONS.includes(eligibilityByPeriod.get(r.periodId)?.payoutBlockedReason ?? '') ? (
            <Typography variant="caption" color="text.secondary" data-testid="payout-blocked-reason">
              {t(`rotation.entitlements.blocked.${eligibilityByPeriod.get(r.periodId)?.payoutBlockedReason}`)}
            </Typography>
          ) : null}
        </Stack>
      ),
    },
  ]

  return (
    <Stack spacing={2} data-testid="rotation-entitlements">
      <Alert severity="info">{t('rotation.entitlements.explain')}</Alert>
      {rows.some((r) => Number(r.unreconciledRotating) > 0) ? (
        <Alert severity="warning" data-testid="rotation-unreconciled-note">
          {t('rotation.entitlements.unreconciledNote')}
        </Alert>
      ) : null}
      <ResponsiveTable
        columns={columns}
        rows={rows}
        getRowId={(r) => r.periodId}
        emptyTitle={staff ? t('rotation.entitlements.emptyStaff') : t('rotation.entitlements.emptyMine')}
        emptyDescription=""
      />
      {requestFor ? (
        <RequestPayoutDialog
          cooperativeId={cooperativeId}
          period={requestFor}
          maxRequestable={eligibilityByPeriod.get(requestFor.periodId)?.maxRequestable ?? 0}
          onClose={() => setRequestFor(null)}
        />
      ) : null}
      {recoveriesFor ? (
        <RecoveriesDialog cooperativeId={cooperativeId} period={recoveriesFor} onClose={() => setRecoveriesFor(null)} />
      ) : null}
    </Stack>
  )
}

function RequestPayoutDialog({
  cooperativeId,
  period,
  maxRequestable,
  onClose,
}: {
  cooperativeId: string
  period: PeriodEntitlement
  maxRequestable: string | number
  onClose: () => void
}) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const queryClient = useQueryClient()
  const [amount, setAmount] = useState('')
  const [attempted, setAttempted] = useState(false)
  const key = useRef(newKey())
  const invalid = amount.trim() !== '' && !isPositiveAmount(amount.trim())

  const mutation = useMutation({
    mutationFn: () =>
      requestRotationPayout(
        cooperativeId,
        { rotationPeriodId: period.periodId, amount: amount.trim() || undefined },
        key.current,
      ),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['rotation', cooperativeId] })
      enqueueSnackbar(t('rotation.payouts.requested'), { variant: 'success' })
      onClose()
    },
  })

  return (
    <Dialog open onClose={mutation.isPending ? undefined : onClose} fullWidth maxWidth="sm" aria-label={t('rotation.payouts.requestTitle')}>
      <DialogTitle>{t('rotation.payouts.requestTitle')}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <Alert severity="info">{t('rotation.payouts.requestExplain')}</Alert>
          <Typography variant="body2">
            {t('rotation.payouts.requestSummary', {
              period: monthLabel(period.year, period.month),
              recipient: period.recipientName,
              outstanding: formatMoney(maxRequestable),
            })}
          </Typography>
          <MoneyField
            label={t('rotation.payouts.amountOptional')}
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            error={attempted && invalid}
            helperText={attempted && invalid ? t('payments.validation.invalidAllocationAmount') : t('rotation.payouts.amountHint')}
            slotProps={{ htmlInput: { inputMode: 'decimal' } }}
          />
          {mutation.isError ? <ErrorNotice error={mutation.error} /> : null}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose} disabled={mutation.isPending}>
          {t('common.cancel')}
        </Button>
        <FinancialActionButton
          variant="contained"
          disabled={mutation.isPending}
          onClick={() => {
            setAttempted(true)
            if (invalid || mutation.isPending) return
            mutation.mutate()
          }}
          sx={{ minHeight: 44 }}
        >
          {t('rotation.payouts.requestSubmit')}
        </FinancialActionButton>
      </DialogActions>
    </Dialog>
  )
}

function RecoveriesDialog({
  cooperativeId,
  period,
  onClose,
}: {
  cooperativeId: string
  period: PeriodEntitlement
  onClose: () => void
}) {
  const { t } = useTranslation()
  const query = useQuery({
    queryKey: ['rotation', cooperativeId, 'recoveries', period.periodId],
    queryFn: () => fetchRecoveries(cooperativeId, period.periodId),
  })
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm" aria-label={t('rotation.entitlements.recoveries')}>
      <DialogTitle>{t('rotation.entitlements.recoveriesTitle', { period: monthLabel(period.year, period.month) })}</DialogTitle>
      <DialogContent>
        <Stack spacing={1} sx={{ pt: 1 }}>
          <Alert severity="info">{t('rotation.entitlements.recoveriesExplain')}</Alert>
          {query.isLoading ? <LoadingState variant="skeleton" rows={2} /> : null}
          {query.isError ? <ErrorNotice error={query.error} /> : null}
          {(query.data ?? []).length === 0 && query.data ? (
            <Typography variant="body2" color="text.secondary">
              {t('rotation.entitlements.noRecoveries')}
            </Typography>
          ) : null}
          <Box data-testid="rotation-recoveries">
            {(query.data ?? []).map((r) => (
              <Stack key={r.postingId} direction="row" spacing={1} sx={{ alignItems: 'center', py: 0.5 }}>
                <Typography variant="body2">
                  {r.paymentDate} — {formatMoney(r.rotatingAmount ?? 0)}
                </Typography>
                {r.late ? <Chip size="small" color="warning" label={t('rotation.entitlements.late')} /> : null}
                {r.reversed ? <Chip size="small" label={t('rotation.entitlements.reversed')} /> : null}
              </Stack>
            ))}
          </Box>
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose}>{t('common.close')}</Button>
      </DialogActions>
    </Dialog>
  )
}
