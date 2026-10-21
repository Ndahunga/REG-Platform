import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useAppSelector } from '@/app/store/hooks'
import { getErrorMessage } from '@/shared/api/client'
import {
  approveReconciliation,
  declineReconciliation,
  fetchReconciliationCandidates,
  fetchReconciliations,
  requestReconciliation,
  withdrawReconciliation,
} from '@/shared/api/paymentVerification'
import { ErrorState } from '@/shared/components/ErrorState'
import { FinancialActionButton } from '@/shared/components/FinancialActionButton'
import { LoadingState } from '@/shared/components/LoadingState'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import { VERIFICATION_METHODS, type VerificationMethod } from '@/shared/types/memberPayment'
import { PERMISSION_CONTRIBUTION_WRITE, hasPermission } from '@/shared/types/auth'
import type { ReconciliationCandidate, ReconciliationRequest } from '@/shared/types/paymentVerification'
import { formatMoney } from '@/shared/utils/formatMoney'
import { ErrorNotice, periodLabel } from './PaymentParts'
import { isPositiveAmount, verificationNeedsReference } from './paymentHelpers'
import { ReasonConfirmDialog } from './ReasonConfirmDialog'

interface LegacyReconciliationPanelProps {
  cooperativeId: string
}

type Active =
  | { kind: 'request'; candidate: ReconciliationCandidate }
  | { kind: 'decline'; request: ReconciliationRequest }
  | { kind: 'withdraw'; request: ReconciliationRequest }
  | null

const statusColor = (status: ReconciliationRequest['status']) =>
  status === 'APPROVED' ? 'success' : status === 'REQUESTED' ? 'warning' : 'default'

/**
 * Rotating contributions recorded without verification evidence. An officer requests that an amount be treated as
 * really received, with the evidence relied on; a different fund authority approves or declines. Approving creates no
 * ledger entry and changes no balance: the money is already recorded once. It only makes the amount verified, and so
 * eligible for payout. Only obligations that already carry their rotation period and recipient are listed.
 */
export function LegacyReconciliationPanel({ cooperativeId }: LegacyReconciliationPanelProps) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const user = useAppSelector((s) => s.auth.user)
  const [active, setActive] = useState<Active>(null)

  const candidates = useQuery({
    queryKey: ['legacy-reconciliation', cooperativeId, 'candidates'],
    queryFn: () => fetchReconciliationCandidates(cooperativeId),
  })
  const requests = useQuery({
    queryKey: ['legacy-reconciliation', cooperativeId, 'requests'],
    queryFn: () => fetchReconciliations(cooperativeId),
  })
  const refresh = async () => {
    await queryClient.invalidateQueries({ queryKey: ['legacy-reconciliation', cooperativeId] })
    await queryClient.invalidateQueries({ queryKey: ['model-summary', cooperativeId] })
    await queryClient.invalidateQueries({ queryKey: ['rotation', cooperativeId] })
  }
  const approve = useMutation({
    mutationFn: (id: string) => approveReconciliation(cooperativeId, id),
    onSuccess: refresh,
    onError: () => void refresh(),
  })

  const canRequest = hasPermission(user, PERMISSION_CONTRIBUTION_WRITE)
  const canDecide = hasPermission(user, 'FUND_AUTHORIZE')

  const candidateColumns: TableColumn<ReconciliationCandidate>[] = [
    { id: 'member', label: t('payments.columns.member'), render: (r) => r.memberName || r.memberUserId },
    { id: 'period', label: t('payments.reconcile.period'), render: (r) => periodLabel(r.year, r.month) },
    { id: 'recipient', label: t('payments.reconcile.recipient'), render: (r) => r.originalRecipientName || r.originalRecipientUserId, hideOnMobile: true },
    { id: 'paid', label: t('payments.reconcile.paid'), render: (r) => formatMoney(r.paidAmount), hideOnMobile: true },
    { id: 'verified', label: t('payments.reconcile.verified'), render: (r) => formatMoney(r.verifiedAmount), hideOnMobile: true },
    { id: 'unreconciled', label: t('payments.reconcile.unreconciled'), render: (r) => formatMoney(r.unreconciledAmount) },
    {
      id: 'action',
      label: '',
      render: (r) =>
        r.openRequestId ? (
          <Chip size="small" color="warning" label={t('payments.reconcile.openRequest')} />
        ) : canRequest ? (
          <Button
            size="small"
            variant="outlined"
            onClick={(event) => {
              event.stopPropagation()
              setActive({ kind: 'request', candidate: r })
            }}
            sx={{ minHeight: 44 }}
          >
            {t('payments.reconcile.request')}
          </Button>
        ) : null,
    },
  ]

  return (
    <Stack spacing={3} data-testid="legacy-reconciliation-panel">
      <Box>
        <Typography variant="h6" gutterBottom>
          {t('payments.reconcile.legacyTitle')}
        </Typography>
        <Alert severity="info" sx={{ mb: 1 }} data-testid="legacy-explain">
          {t('payments.reconcile.legacyHelp')}
        </Alert>
        {candidates.isLoading ? <LoadingState variant="skeleton" rows={3} /> : null}
        {candidates.isError ? (
          <ErrorState message={getErrorMessage(candidates.error, t('errors.generic'))} onRetry={() => void candidates.refetch()} />
        ) : null}
        {candidates.data ? (
          <ResponsiveTable
            columns={candidateColumns}
            rows={candidates.data}
            getRowId={(r) => r.contributionId}
            emptyTitle={t('payments.reconcile.candidatesEmpty')}
            emptyDescription=""
          />
        ) : null}
      </Box>

      <Box>
        <Typography variant="h6" gutterBottom>
          {t('payments.reconcile.requestsTitle')}
        </Typography>
        {requests.isLoading ? <LoadingState variant="skeleton" rows={2} /> : null}
        {requests.isError ? (
          <ErrorState message={getErrorMessage(requests.error, t('errors.generic'))} onRetry={() => void requests.refetch()} />
        ) : null}
        {approve.isError ? <ErrorNotice error={approve.error} /> : null}
        <Stack spacing={1}>
          {(requests.data ?? []).map((r) => {
            const blocked = r.requestedBy === user?.id || r.memberUserId === user?.id
            return (
              <Box key={r.id} sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 1, p: 1.5 }} data-testid="legacy-reconciliation-request">
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 0.5 }}>
                  <Chip size="small" color={statusColor(r.status)} label={t(`payments.reconcile.status.${r.status}`)} />
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>
                    {periodLabel(r.periodYear, r.periodMonth)} · {formatMoney(r.amount)}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {t(`payments.verification.methods.${r.method}`)}
                    {r.reference ? ` · ${r.reference}` : ''}
                  </Typography>
                </Stack>
                <Typography variant="caption" color="text.secondary" component="div">
                  {r.basis}
                </Typography>
                {r.decisionNote ? (
                  <Typography variant="caption" color="text.secondary" component="div">
                    {t('payments.reversal.decisionNote', { note: r.decisionNote })}
                  </Typography>
                ) : null}
                {r.status === 'REQUESTED' ? (
                  <Stack direction="row" spacing={1} sx={{ mt: 1, flexWrap: 'wrap', rowGap: 1 }}>
                    {r.requestedBy === user?.id && canRequest ? (
                      <Button variant="outlined" onClick={() => setActive({ kind: 'withdraw', request: r })} sx={{ minHeight: 44 }}>
                        {t('payments.reconcile.withdraw')}
                      </Button>
                    ) : null}
                    {canDecide && !blocked ? (
                      <>
                        <FinancialActionButton
                          variant="contained"
                          onClick={() => approve.mutate(r.id)}
                          disabled={approve.isPending}
                          sx={{ minHeight: 44 }}
                        >
                          {approve.isPending ? t('payments.reconcile.approving') : t('payments.reconcile.approve')}
                        </FinancialActionButton>
                        <Button variant="outlined" color="error" onClick={() => setActive({ kind: 'decline', request: r })} sx={{ minHeight: 44 }}>
                          {t('payments.reconcile.decline')}
                        </Button>
                      </>
                    ) : null}
                  </Stack>
                ) : null}
                {r.status === 'REQUESTED' && canDecide && blocked ? (
                  <Alert severity="info" sx={{ mt: 1 }} data-testid="legacy-independence">
                    {t('payments.reconcile.needsAnotherOfficer')}
                  </Alert>
                ) : null}
                {r.status === 'REQUESTED' && !canDecide && r.requestedBy !== user?.id ? (
                  <Alert severity="info" sx={{ mt: 1 }}>
                    {t('payments.reconcile.needsFundAuthority')}
                  </Alert>
                ) : null}
              </Box>
            )
          })}
        </Stack>
      </Box>

      {active?.kind === 'request' ? (
        <RequestDialog
          cooperativeId={cooperativeId}
          candidate={active.candidate}
          onClose={() => setActive(null)}
          onDone={() => {
            setActive(null)
            void refresh()
          }}
        />
      ) : null}
      {active?.kind === 'decline' ? (
        <ReasonConfirmDialog
          danger
          title={t('payments.reconcile.declineTitle')}
          explain={t('payments.reconcile.declineExplain')}
          reasonLabel={t('payments.reconcile.declineReason')}
          submitLabel={t('payments.reconcile.decline')}
          onSubmit={(note) => declineReconciliation(cooperativeId, active.request.id, note)}
          onClose={() => setActive(null)}
          onDone={() => {
            setActive(null)
            void refresh()
          }}
        />
      ) : null}
      {active?.kind === 'withdraw' ? (
        <ReasonConfirmDialog
          title={t('payments.reconcile.withdrawTitle')}
          explain={t('payments.reconcile.withdrawExplain')}
          reasonLabel={t('payments.reconcile.withdrawReason')}
          submitLabel={t('payments.reconcile.withdraw')}
          onSubmit={(note) => withdrawReconciliation(cooperativeId, active.request.id, note)}
          onClose={() => setActive(null)}
          onDone={() => {
            setActive(null)
            void refresh()
          }}
        />
      ) : null}
    </Stack>
  )
}

interface RequestDialogProps {
  cooperativeId: string
  candidate: ReconciliationCandidate
  onClose: () => void
  onDone: () => void
}

function RequestDialog({ cooperativeId, candidate, onClose, onDone }: RequestDialogProps) {
  const { t } = useTranslation()
  const [amount, setAmount] = useState(String(candidate.unreconciledAmount))
  const [method, setMethod] = useState<VerificationMethod>('BANK_RECONCILED')
  const [basis, setBasis] = useState('')
  const [reference, setReference] = useState('')
  const [attempted, setAttempted] = useState(false)
  const mutation = useMutation({
    mutationFn: () =>
      requestReconciliation(cooperativeId, {
        contributionId: candidate.contributionId,
        amount: Number(amount.trim()),
        method,
        basis: basis.trim(),
        ...(reference.trim() ? { reference: reference.trim() } : {}),
      }),
    onSuccess: () => onDone(),
  })
  const needsReference = verificationNeedsReference(method)
  const amountInvalid = !isPositiveAmount(amount.trim()) || Number(amount) > Number(candidate.unreconciledAmount)
  const basisMissing = basis.trim() === ''
  const referenceMissing = needsReference && reference.trim() === ''

  const submit = () => {
    setAttempted(true)
    if (amountInvalid || basisMissing || referenceMissing || mutation.isPending) return
    mutation.mutate()
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm" aria-labelledby="reconcile-request-title">
      <DialogTitle id="reconcile-request-title">{t('payments.reconcile.requestTitle')}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <Typography variant="body2">
            {t('payments.reconcile.requestSummary', {
              member: candidate.memberName || candidate.memberUserId,
              period: periodLabel(candidate.year, candidate.month),
              max: formatMoney(candidate.unreconciledAmount),
            })}
          </Typography>
          <TextField
            label={t('payments.columns.amount')}
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            error={attempted && amountInvalid}
            helperText={attempted && amountInvalid ? t('payments.reconcile.amountTooHigh') : undefined}
            slotProps={{ htmlInput: { inputMode: 'decimal' } }}
          />
          <TextField select label={t('payments.verification.method')} value={method} onChange={(e) => setMethod(e.target.value as VerificationMethod)}>
            {VERIFICATION_METHODS.map((m) => (
              <MenuItem key={m} value={m}>
                {t(`payments.verification.methods.${m}`)}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            label={t('payments.verification.basis')}
            value={basis}
            onChange={(e) => setBasis(e.target.value)}
            multiline
            minRows={2}
            error={attempted && basisMissing}
            helperText={attempted && basisMissing ? t('payments.reconcile.basisRequired') : undefined}
          />
          {needsReference ? (
            <TextField
              label={t('payments.columns.reference')}
              value={reference}
              onChange={(e) => setReference(e.target.value)}
              error={attempted && referenceMissing}
              helperText={attempted && referenceMissing ? t('payments.reconcile.referenceRequired') : undefined}
            />
          ) : null}
          {mutation.isError ? <ErrorNotice error={mutation.error} /> : null}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose}>{t('common.cancel')}</Button>
        <FinancialActionButton variant="contained" onClick={submit} disabled={mutation.isPending} sx={{ minHeight: 44 }}>
          {mutation.isPending ? t('payments.reconcile.requesting') : t('payments.reconcile.request')}
        </FinancialActionButton>
      </DialogActions>
    </Dialog>
  )
}
