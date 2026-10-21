import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { fetchContributions } from '@/shared/api/contributions'
import { fetchLoans } from '@/shared/api/loans'
import { applyCredit, fetchCredits } from '@/shared/api/memberPayments'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import type { CreditBucket, CreditPurpose } from '@/shared/types/memberPayment'
import { formatMoney } from '@/shared/utils/formatMoney'
import { ErrorNotice, periodLabel, shortId } from './PaymentParts'
import { isOpenContribution, isPositiveAmount, isRepayableLoan, toUnits } from './paymentHelpers'

interface MemberCreditPanelProps {
  cooperativeId: string
}

type TargetKind = 'CONTRIBUTION' | 'LOAN_REPAYMENT'

function newKey(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `credit-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

/**
 * What the Saving Scheme holds for its members (officers). Held credit is money paid beyond what was due: not income and
 * not group funds until an officer applies it to a contribution or a loan, which this panel does one decision at a time.
 */
export function MemberCreditPanel({ cooperativeId }: MemberCreditPanelProps) {
  const { t } = useTranslation()
  const [applying, setApplying] = useState<CreditBucket | null>(null)
  const credits = useQuery({ queryKey: ['member-credits', cooperativeId], queryFn: () => fetchCredits(cooperativeId) })

  const purposeLabel = (purpose: CreditPurpose, loanId: string | null) =>
    `${t(`payments.credit.purposes.${purpose}`)}${loanId ? ` (${shortId(loanId)})` : ''}`

  return (
    <Box data-testid="member-credit-panel">
      <Typography variant="h6">{t('payments.credit.officerTitle')}</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {t('payments.credit.officerDescription')}
      </Typography>
      {credits.isLoading ? <LoadingState variant="skeleton" rows={2} /> : null}
      {credits.isError ? <ErrorState message={t('payments.errors.generic')} onRetry={() => void credits.refetch()} /> : null}
      {credits.data && credits.data.balances.length === 0 ? <Alert severity="info">{t('payments.credit.officerEmpty')}</Alert> : null}
      <Stack spacing={1}>
        {(credits.data?.balances ?? []).map((bucket) => (
          <Paper
            key={`${bucket.memberUserId}-${bucket.purpose}-${bucket.loanId ?? ''}`}
            elevation={0}
            sx={{ p: 1.5, border: '1px solid', borderColor: 'divider' }}
            data-testid="member-credit-row"
          >
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap' }}>
              <Box>
                <Typography variant="body2" sx={{ fontWeight: 600 }}>
                  {bucket.memberName ?? shortId(bucket.memberUserId)}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  {purposeLabel(bucket.purpose, bucket.loanId)} · {t('payments.credit.balance')}: {formatMoney(String(bucket.balance))}
                </Typography>
              </Box>
              <Button size="small" variant="outlined" onClick={() => setApplying(bucket)}>
                {t('payments.credit.apply')}
              </Button>
            </Stack>
          </Paper>
        ))}
      </Stack>
      {credits.data && credits.data.balances.length > 0 ? (
        <Typography variant="body2" sx={{ mt: 1 }} data-testid="member-credit-total">
          {t('payments.credit.total', { amount: formatMoney(String(credits.data.totalHeld)) })}
        </Typography>
      ) : null}
      {applying ? (
        <ApplyDialog cooperativeId={cooperativeId} bucket={applying} onClose={() => setApplying(null)} />
      ) : null}
    </Box>
  )
}

function ApplyDialog({
  cooperativeId,
  bucket,
  onClose,
}: {
  cooperativeId: string
  bucket: CreditBucket
  onClose: () => void
}) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const queryClient = useQueryClient()
  const canContribution = bucket.purpose !== 'LOAN_PREPAYMENT'
  const canLoan = bucket.purpose !== 'ADVANCE_CONTRIBUTION'
  const [kind, setKind] = useState<TargetKind>(canContribution ? 'CONTRIBUTION' : 'LOAN_REPAYMENT')
  const [contributionKey, setContributionKey] = useState('')
  const [loanId, setLoanId] = useState(bucket.loanId ?? '')
  const [amount, setAmount] = useState('')
  const [attempted, setAttempted] = useState(false)
  const idempotency = useMemo(() => newKey(), [])

  const contributions = useQuery({
    queryKey: ['member-credit-targets', cooperativeId, bucket.memberUserId, 'contributions'],
    queryFn: () => fetchContributions(cooperativeId, { memberUserId: bucket.memberUserId, size: 100 }),
    enabled: canContribution,
  })
  const loans = useQuery({
    queryKey: ['member-credit-targets', cooperativeId, bucket.memberUserId, 'loans'],
    queryFn: () => fetchLoans(cooperativeId, { memberUserId: bucket.memberUserId, size: 100 }),
    enabled: canLoan,
  })
  const openContributions = (contributions.data?.content ?? []).filter(isOpenContribution)
  const repayable = (loans.data?.content ?? []).filter(isRepayableLoan).filter((l) => !bucket.loanId || l.id === bucket.loanId)

  const apply = useMutation({
    mutationFn: () => {
      const chosen = openContributions.find((c) => `${c.year}-${c.month}` === contributionKey)
      return applyCredit(
        cooperativeId,
        {
          memberUserId: bucket.memberUserId,
          purpose: bucket.purpose,
          bucketLoanId: bucket.loanId ?? undefined,
          targetType: kind,
          periodYear: kind === 'CONTRIBUTION' ? chosen?.year : undefined,
          periodMonth: kind === 'CONTRIBUTION' ? chosen?.month : undefined,
          loanId: kind === 'LOAN_REPAYMENT' ? loanId : undefined,
          amount: amount.trim(),
        },
        idempotency,
      )
    },
    onSuccess: () => {
      enqueueSnackbar(t('payments.credit.applied'), { variant: 'success' })
      void queryClient.invalidateQueries({ queryKey: ['member-credits', cooperativeId] })
      void queryClient.invalidateQueries({ queryKey: ['payment-summary', cooperativeId] })
      onClose()
    },
  })

  const amountUnits = toUnits(amount)
  const held = toUnits(String(bucket.balance)) ?? 0
  const targetChosen = kind === 'CONTRIBUTION' ? contributionKey !== '' : loanId !== ''
  const amountInvalid = !isPositiveAmount(amount) || (amountUnits ?? 0) > held
  const nothingOpen =
    (kind === 'CONTRIBUTION' && contributions.isSuccess && openContributions.length === 0) ||
    (kind === 'LOAN_REPAYMENT' && loans.isSuccess && repayable.length === 0)

  const submit = () => {
    setAttempted(true)
    if (amountInvalid || !targetChosen || apply.isPending) return
    apply.mutate()
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{t('payments.credit.applyTitle', { member: bucket.memberName ?? shortId(bucket.memberUserId) })}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <Typography variant="body2" color="text.secondary">
            {t(`payments.credit.purposes.${bucket.purpose}`)} · {t('payments.credit.balance')}: {formatMoney(String(bucket.balance))}
          </Typography>
          <TextField
            select
            size="small"
            label={t('payments.credit.target')}
            value={kind}
            onChange={(e) => setKind(e.target.value as TargetKind)}
          >
            {canContribution ? <MenuItem value="CONTRIBUTION">{t('payments.credit.targetContribution')}</MenuItem> : null}
            {canLoan ? <MenuItem value="LOAN_REPAYMENT">{t('payments.credit.targetLoan')}</MenuItem> : null}
          </TextField>
          {nothingOpen ? <Alert severity="info">{t('payments.credit.noTargets')}</Alert> : null}
          {kind === 'CONTRIBUTION' ? (
            <TextField
              select
              size="small"
              label={t('payments.credit.period')}
              value={contributionKey}
              onChange={(e) => setContributionKey(e.target.value)}
              error={attempted && !targetChosen}
            >
              {openContributions.map((c) => (
                <MenuItem key={c.id} value={`${c.year}-${c.month}`}>
                  {t('payments.allocation.contributionOption', {
                    period: periodLabel(c.year, c.month),
                    outstanding: formatMoney(c.outstandingAmount),
                  })}
                </MenuItem>
              ))}
            </TextField>
          ) : (
            <TextField
              select
              size="small"
              label={t('payments.credit.loanTarget')}
              value={loanId}
              onChange={(e) => setLoanId(e.target.value)}
              error={attempted && !targetChosen}
            >
              {repayable.map((l) => (
                <MenuItem key={l.id} value={l.id}>
                  {shortId(l.id)}
                </MenuItem>
              ))}
            </TextField>
          )}
          <TextField
            size="small"
            label={t('payments.credit.amount')}
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            error={attempted && amountInvalid}
            slotProps={{ htmlInput: { inputMode: 'decimal' } }}
          />
          {apply.isError ? <ErrorNotice error={apply.error} /> : null}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t('payments.credit.cancel')}</Button>
        <Button variant="contained" onClick={submit} disabled={apply.isPending}>
          {apply.isPending ? t('payments.credit.applying') : t('payments.credit.confirm')}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
