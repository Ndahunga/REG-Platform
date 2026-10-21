import DeleteOutlinedIcon from '@mui/icons-material/DeleteOutlined'
import {
  Alert,
  Box,
  Button,
  Chip,
  IconButton,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { getErrorMessage } from '@/shared/api/client'
import { fetchMyContributions } from '@/shared/api/contributions'
import { uploadCooperativeFile } from '@/shared/api/files'
import { fetchMyLoans } from '@/shared/api/loans'
import { analyzeReceipt, fetchCredits, fetchPaymentAccounts, submitPayment } from '@/shared/api/memberPayments'
import { ErrorState } from '@/shared/components/ErrorState'
import { FinancialActionButton } from '@/shared/components/FinancialActionButton'
import { LoadingState } from '@/shared/components/LoadingState'
import type { Contribution } from '@/shared/types/contribution'
import type { Loan } from '@/shared/types/loan'
import {
  CREDIT_PURPOSES,
  PAYMENT_METHODS,
  type CreditPurpose,
  type MemberPayment,
  type PaymentMethod,
  type PaymentSubmitRequest,
  type ReceiptAnalysis,
} from '@/shared/types/memberPayment'
import { formatMoney } from '@/shared/utils/formatMoney'
import { isFutureIsoDate, isValidIsoDate } from '@/shared/utils/formValidation'
import { todayInKigaliIso } from '@/shared/utils/rwandaCooperative'
import { ObligationDetails } from '@/features/contributions/ObligationDetails'
import { ErrorNotice, PaymentStatusChip, PaymentStatusNotice, periodLabel, shortId } from './PaymentParts'
import { ReceiptAnalysisNotice, receiptReviewReasons } from './ReceiptAnalysisNotice'
import {
  allocatedUnits,
  contributionOutstandingUnits,
  fromUnits,
  isOpenContribution,
  isRepayableLoan,
  loanOutstandingUnits,
  toUnits,
  validateSubmission,
  type AllocationDraft,
  type SubmissionProblem,
} from './paymentHelpers'

interface PaymentSubmitPanelProps {
  cooperativeId: string
  /** Opens the member's payment list (offered after a payment was accepted). */
  onViewPayments?: () => void
}

function newKey(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `pay-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

/**
 * Combined payment submission: one transfer covering contribution periods and/or loan repayments. Submitting only
 * records a claim for review; no contribution or loan balance changes until officers verify and post it. All checks
 * here are advisory, the backend validates everything again. The loan engine, not this form, decides how a loan
 * repayment splits into principal, interest and penalty.
 */
export function PaymentSubmitPanel({ cooperativeId, onViewPayments }: PaymentSubmitPanelProps) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const queryClient = useQueryClient()

  const [amount, setAmount] = useState('')
  const [method, setMethod] = useState<PaymentMethod>('MTN_MOMO')
  const [reference, setReference] = useState('')
  const [paymentDate, setPaymentDate] = useState(todayInKigaliIso())
  const [notes, setNotes] = useState('')
  const [evidenceKey, setEvidenceKey] = useState('')
  const [evidenceName, setEvidenceName] = useState('')
  const [analysis, setAnalysis] = useState<ReceiptAnalysis | null>(null)
  const [analysisFailed, setAnalysisFailed] = useState(false)
  const [manualReview, setManualReview] = useState(false)
  const [drafts, setDrafts] = useState<AllocationDraft[]>([])
  const [contributionChoice, setContributionChoice] = useState('')
  const [loanChoice, setLoanChoice] = useState('')
  const [creditPurpose, setCreditPurpose] = useState<CreditPurpose>('UNAPPLIED_CREDIT')
  const [creditLoan, setCreditLoan] = useState('')
  const [attempted, setAttempted] = useState(false)
  const [accepted, setAccepted] = useState<MemberPayment | null>(null)
  const [submitError, setSubmitError] = useState<unknown>(null)
  const idempotency = useRef<{ signature: string; key: string } | null>(null)

  const contributionsQuery = useQuery({
    queryKey: ['payment-obligations', cooperativeId, 'contributions'],
    queryFn: () => fetchMyContributions(cooperativeId, { size: 100 }),
  })
  const loansQuery = useQuery({
    queryKey: ['payment-obligations', cooperativeId, 'loans'],
    queryFn: () => fetchMyLoans(cooperativeId, { size: 100 }),
  })

  const accountsQuery = useQuery({
    queryKey: ['payment-accounts', cooperativeId],
    queryFn: () => fetchPaymentAccounts(cooperativeId),
  })
  const payToAccounts = (accountsQuery.data ?? []).filter((a) => a.active)
  const myCredit = useQuery({ queryKey: ['member-credits', cooperativeId, 'mine'], queryFn: () => fetchCredits(cooperativeId) })

  const openContributions = useMemo(
    () =>
      (contributionsQuery.data?.content ?? [])
        .filter(isOpenContribution)
        .sort((a, b) => a.year - b.year || a.month - b.month),
    [contributionsQuery.data],
  )
  const repayableLoans = useMemo(() => (loansQuery.data?.content ?? []).filter(isRepayableLoan), [loansQuery.data])

  const contributionFor = (draft: AllocationDraft): Contribution | undefined =>
    openContributions.find((c) => c.year === draft.periodYear && c.month === draft.periodMonth)
  const loanFor = (draft: AllocationDraft): Loan | undefined => repayableLoans.find((l) => l.id === draft.loanId)

  const chosenContributionKeys = new Set(
    drafts.filter((d) => d.type === 'CONTRIBUTION').map((d) => `${d.periodYear}-${d.periodMonth}`),
  )
  const chosenLoanIds = new Set(drafts.filter((d) => d.loanId).map((d) => d.loanId))
  const availableContributions = openContributions.filter((c) => !chosenContributionKeys.has(`${c.year}-${c.month}`))
  const availableLoans = repayableLoans.filter((l) => !chosenLoanIds.has(l.id))

  const addContribution = () => {
    const row = openContributions.find((c) => c.id === contributionChoice)
    if (!row) return
    const outstanding = contributionOutstandingUnits(row)
    setDrafts((current) => [
      ...current,
      {
        key: newKey(),
        type: 'CONTRIBUTION',
        amount: fromUnits(outstanding),
        periodYear: row.year,
        periodMonth: row.month,
        maxUnits: outstanding,
      },
    ])
    setContributionChoice('')
  }

  const addLoan = () => {
    const loan = repayableLoans.find((l) => l.id === loanChoice)
    if (!loan) return
    const outstanding = loanOutstandingUnits(loan)
    setDrafts((current) => [
      ...current,
      { key: newKey(), type: 'LOAN_REPAYMENT', amount: fromUnits(outstanding), loanId: loan.id, maxUnits: outstanding },
    ])
    setLoanChoice('')
  }

  // excess money is classified explicitly: it is held for the member, never income
  const addCredit = () => {
    const remaining = (toUnits(amount) ?? 0) - allocatedUnits(drafts)
    if (remaining <= 0) return
    if (creditPurpose === 'LOAN_PREPAYMENT' && !creditLoan) return
    setDrafts((current) => [
      ...current,
      {
        key: newKey(),
        type: 'MEMBER_CREDIT',
        amount: fromUnits(remaining),
        creditPurpose,
        loanId: creditPurpose === 'LOAN_PREPAYMENT' ? creditLoan : undefined,
        maxUnits: null,
      },
    ])
    setCreditLoan('')
  }

  const updateDraft = (key: string, value: string) =>
    setDrafts((current) => current.map((d) => (d.key === key ? { ...d, amount: value } : d)))
  const removeDraft = (key: string) => setDrafts((current) => current.filter((d) => d.key !== key))

  const problems = validateSubmission(amount, drafts)
  const problemFor = (key: string): SubmissionProblem | undefined =>
    problems.find((p) => 'key' in p && p.key === key)
  const referenceMissing = method !== 'CASH' && reference.trim() === ''
  const dateInvalid = !isValidIsoDate(paymentDate) || isFutureIsoDate(paymentDate)
  const blocked = problems.length > 0 || referenceMissing || dateInvalid

  const resetReceiptReading = () => {
    setAnalysis(null)
    setAnalysisFailed(false)
    setManualReview(false)
  }

  // The receipt is read as soon as it is uploaded, before anything is submitted. The reading only advises: the backend
  // applies the same rule again when the payment is submitted.
  const analyze = useMutation({
    mutationFn: (fileKey: string) =>
      analyzeReceipt(cooperativeId, fileKey, toUnits(amount) !== null && (toUnits(amount) ?? 0) > 0 ? fromUnits(toUnits(amount) ?? 0) : undefined),
    onSuccess: (result) => {
      setAnalysis(result)
      setAnalysisFailed(false)
      if (result.canSubmit && !result.amountAmbiguous && result.extractedAmount != null && toUnits(amount) === null) {
        setAmount(fromUnits(toUnits(String(result.extractedAmount)) ?? 0))
      }
    },
    onError: () => {
      setAnalysis(null)
      setAnalysisFailed(true)
    },
  })

  const upload = useMutation({
    mutationFn: (file: File) => uploadCooperativeFile(cooperativeId, file, 'CONTRIBUTION_EVIDENCE'),
    onSuccess: (file) => {
      resetReceiptReading()
      setEvidenceKey(file.storageKey)
      setEvidenceName(file.originalFilename ?? file.storageKey)
      analyze.mutate(file.storageKey)
    },
    onError: (error) => enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' }),
  })

  const reviewReasons = receiptReviewReasons(analysis, { amount, reference })
  const receiptBlocked = analysis !== null && !analysis.canSubmit
  const reviewNotRequested = reviewReasons.length > 0 && !manualReview

  const buildPayload = (): PaymentSubmitRequest => ({
    amount: fromUnits(toUnits(amount) ?? 0),
    paymentDate,
    paymentMethod: method,
    transactionReference: reference.trim() || undefined,
    evidenceFileKey: evidenceKey || undefined,
    notes: notes.trim() || undefined,
    manualReview: manualReview && reviewReasons.length > 0 ? true : undefined,
    allocations: drafts.map((d) =>
      d.type === 'CONTRIBUTION'
        ? { type: 'CONTRIBUTION', amount: fromUnits(toUnits(d.amount) ?? 0), periodYear: d.periodYear, periodMonth: d.periodMonth }
        : d.type === 'MEMBER_CREDIT'
          ? { type: 'MEMBER_CREDIT', amount: fromUnits(toUnits(d.amount) ?? 0), creditPurpose: d.creditPurpose, loanId: d.loanId }
          : { type: 'LOAN_REPAYMENT', amount: fromUnits(toUnits(d.amount) ?? 0), loanId: d.loanId },
    ),
  })

  const submit = useMutation({
    mutationFn: (payload: PaymentSubmitRequest) => {
      // The same key for the same content: a double click or a retry after a timeout returns the original payment.
      const signature = JSON.stringify(payload)
      if (idempotency.current?.signature !== signature) {
        idempotency.current = { signature, key: newKey() }
      }
      return submitPayment(cooperativeId, payload, idempotency.current.key)
    },
    onSuccess: (payment) => {
      setSubmitError(null)
      setAccepted(payment)
      setAmount('')
      setReference('')
      setNotes('')
      setEvidenceKey('')
      setEvidenceName('')
      resetReceiptReading()
      setDrafts([])
      setAttempted(false)
      idempotency.current = null
      enqueueSnackbar(t('payments.submit.submitted'), { variant: 'success' })
      void queryClient.invalidateQueries({ queryKey: ['member-payments', cooperativeId] })
      void queryClient.invalidateQueries({ queryKey: ['payment-obligations', cooperativeId] })
    },
    onError: (error) => setSubmitError(error),
  })

  const onSubmit = () => {
    setAttempted(true)
    if (blocked || submit.isPending || receiptBlocked || reviewNotRequested || analyze.isPending) return
    submit.mutate(buildPayload())
  }

  const loading = contributionsQuery.isLoading || loansQuery.isLoading
  const loadError = contributionsQuery.isError || loansQuery.isError
  const total = toUnits(amount)
  const allocated = allocatedUnits(drafts)
  const money = (units: number) => formatMoney(fromUnits(units))
  const methodHint = method === 'CASH' ? t('payments.submit.referenceOptionalCash') : t('payments.submit.referenceHint')

  const problemText = (problem: SubmissionProblem): string => {
    switch (problem.code) {
      case 'NO_ALLOCATIONS':
        return t('payments.validation.noAllocations')
      case 'INVALID_TOTAL':
        return t('payments.validation.invalidTotal')
      case 'INVALID_ALLOCATION_AMOUNT':
        return t('payments.validation.invalidAllocationAmount')
      case 'EXCEEDS_OUTSTANDING':
        return t('payments.validation.exceedsOutstanding')
      case 'DUPLICATE_TARGET':
        return t('payments.validation.duplicateTarget')
      case 'TOTAL_MISMATCH':
        return t('payments.validation.totalMismatch', {
          allocated: money(problem.allocatedUnits),
          total: money(problem.totalUnits),
        })
    }
  }

  if (loading) return <LoadingState variant="skeleton" rows={4} />
  if (loadError) {
    return (
      <ErrorState
        message={t('payments.submit.loadError')}
        onRetry={() => {
          void contributionsQuery.refetch()
          void loansQuery.refetch()
        }}
      />
    )
  }

  return (
    <Stack spacing={2.5} data-testid="payment-submit-panel">
      <Alert severity="info">{t('payments.submit.explain')}</Alert>
      <Alert severity="warning" data-testid="payment-legacy-note">
        {t('payments.submit.legacyNote')}
      </Alert>
      {myCredit.data && myCredit.data.balances.length > 0 ? (
        <Alert severity="info" data-testid="payment-my-credit">
          <Typography variant="body2" sx={{ fontWeight: 600 }}>
            {t('payments.credit.mine')}
          </Typography>
          {myCredit.data.balances.map((b) => (
            <Typography key={`${b.purpose}-${b.loanId ?? ''}`} variant="body2">
              {t('payments.credit.mineLine', {
                purpose: t(`payments.credit.purposes.${b.purpose}`),
                amount: formatMoney(String(b.balance)),
              })}
            </Typography>
          ))}
        </Alert>
      ) : null}
      {payToAccounts.length > 0 ? (
        <Alert severity="info" data-testid="payment-pay-to">
          <Typography variant="body2">{t('payments.accounts.memberHint')}</Typography>
          {payToAccounts.map((a) => (
            <Typography key={a.id} variant="body2" sx={{ fontWeight: 600 }}>
              {t(`payments.method.${a.channel}`)} {a.accountIdentifier}
              {a.label ? ` · ${a.label}` : ''}
            </Typography>
          ))}
        </Alert>
      ) : null}

      {accepted ? (
        <Paper elevation={0} sx={{ p: 2, border: '1px solid', borderColor: 'divider' }} data-testid="payment-accepted">
          <Stack spacing={1}>
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
              <Typography variant="subtitle1">{t('payments.submit.acceptedTitle')}</Typography>
              <PaymentStatusChip status={accepted.status} />
            </Stack>
            <PaymentStatusNotice status={accepted.status} />
            {accepted.evidenceFileKey ? (
              <Typography variant="caption" color="text.secondary">
                {t('payments.evidence.uploadedNotProof')}
              </Typography>
            ) : null}
            {accepted.receiptReviewRequested ? (
              <Typography variant="caption" color="text.secondary" data-testid="payment-review-requested">
                {t('payments.receiptAnalysis.reviewRequested')}
              </Typography>
            ) : null}
            {onViewPayments ? (
              <Box>
                <Button variant="outlined" onClick={onViewPayments} sx={{ minHeight: 44 }}>
                  {t('payments.submit.viewMyPayments')}
                </Button>
              </Box>
            ) : null}
          </Stack>
        </Paper>
      ) : null}

      <Paper elevation={0} sx={{ p: { xs: 2, md: 3 }, border: '1px solid', borderColor: 'divider' }}>
        <Stack spacing={2}>
          <Typography variant="h6">{t('payments.submit.detailsTitle')}</Typography>
          <TextField
            label={t('payments.fields.amount')}
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            error={attempted && (total === null || total <= 0)}
            helperText={attempted && (total === null || total <= 0) ? t('payments.validation.invalidTotal') : t('payments.submit.amountHint')}
            slotProps={{ htmlInput: { inputMode: 'decimal' } }}
            required
          />
          <TextField
            select
            label={t('payments.fields.method')}
            value={method}
            onChange={(e) => setMethod(e.target.value as PaymentMethod)}
          >
            {PAYMENT_METHODS.map((m) => (
              <MenuItem key={m} value={m}>
                {t(`payments.method.${m}`)}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            label={t('payments.fields.reference')}
            value={reference}
            onChange={(e) => setReference(e.target.value)}
            error={attempted && referenceMissing}
            helperText={attempted && referenceMissing ? t('payments.validation.referenceRequired') : methodHint}
            required={method !== 'CASH'}
          />
          <TextField
            label={t('payments.fields.paymentDate')}
            type="date"
            value={paymentDate}
            onChange={(e) => setPaymentDate(e.target.value)}
            error={attempted && dateInvalid}
            helperText={attempted && dateInvalid ? t('payments.validation.dateInvalid') : undefined}
            slotProps={{ inputLabel: { shrink: true } }}
          />
          <TextField
            label={t('payments.fields.notes')}
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
            multiline
            minRows={2}
            slotProps={{ htmlInput: { maxLength: 2000 } }}
          />
          <Box>
            <Typography variant="subtitle2">{t('payments.evidence.title')}</Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
              {t('payments.evidence.instructions')}
            </Typography>
            <Button component="label" variant="outlined" disabled={upload.isPending} sx={{ minHeight: 44 }}>
              {t('payments.evidence.upload')}
              <input
                hidden
                type="file"
                accept="image/*,application/pdf"
                aria-label={t('payments.evidence.upload')}
                onChange={(e) => {
                  const file = e.target.files?.[0]
                  if (file) upload.mutate(file)
                }}
              />
            </Button>
            {evidenceKey ? (
              <Typography variant="body2" sx={{ mt: 1 }} data-testid="payment-evidence-uploaded">
                {t('payments.evidence.uploadedNotProof')} {evidenceName}
              </Typography>
            ) : null}
          </Box>
          {evidenceKey ? (
            <ReceiptAnalysisNotice
              analysis={analysis}
              loading={analyze.isPending}
              failed={analysisFailed}
              reasons={reviewReasons}
              manualReview={manualReview}
              onManualReviewChange={setManualReview}
              onUseAmount={(value) => setAmount(fromUnits(toUnits(value) ?? 0))}
              enteredAmount={amount}
            />
          ) : null}
        </Stack>
      </Paper>

      <Paper elevation={0} sx={{ p: { xs: 2, md: 3 }, border: '1px solid', borderColor: 'divider' }}>
        <Stack spacing={2}>
          <Typography variant="h6">{t('payments.allocation.title')}</Typography>

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
            <TextField
              select
              fullWidth
              size="small"
              label={t('payments.allocation.chooseContribution')}
              value={contributionChoice}
              onChange={(e) => setContributionChoice(e.target.value)}
              helperText={availableContributions.length === 0 ? t('payments.allocation.noOpenContributions') : undefined}
            >
              {availableContributions.map((c) => (
                <MenuItem key={c.id} value={c.id}>
                  {t('payments.allocation.contributionOption', {
                    period: periodLabel(c.year, c.month),
                    outstanding: formatMoney(c.outstandingAmount),
                  })}
                </MenuItem>
              ))}
            </TextField>
            <Button variant="outlined" onClick={addContribution} disabled={!contributionChoice} sx={{ minHeight: 40 }}>
              {t('payments.allocation.addContribution')}
            </Button>
          </Stack>

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
            <TextField
              select
              fullWidth
              size="small"
              label={t('payments.allocation.chooseLoan')}
              value={loanChoice}
              onChange={(e) => setLoanChoice(e.target.value)}
              helperText={availableLoans.length === 0 ? t('payments.allocation.noRepayableLoans') : undefined}
            >
              {availableLoans.map((l) => (
                <MenuItem key={l.id} value={l.id}>
                  {t('payments.allocation.loanOption', {
                    loan: shortId(l.id),
                    outstanding: money(loanOutstandingUnits(l)),
                  })}
                </MenuItem>
              ))}
            </TextField>
            <Button variant="outlined" onClick={addLoan} disabled={!loanChoice} sx={{ minHeight: 40 }}>
              {t('payments.allocation.addLoan')}
            </Button>
          </Stack>

          {total !== null && total > 0 && total - allocated > 0 ? (
            <Paper elevation={0} sx={{ p: 2, border: '1px dashed', borderColor: 'divider' }} data-testid="payment-credit-offer">
              <Stack spacing={1}>
                <Typography variant="subtitle2">{t('payments.credit.title')}</Typography>
                <Typography variant="body2" color="text.secondary">
                  {t('payments.credit.hint')}
                </Typography>
                <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
                  <TextField
                    select
                    fullWidth
                    size="small"
                    label={t('payments.credit.purpose')}
                    value={creditPurpose}
                    onChange={(e) => setCreditPurpose(e.target.value as CreditPurpose)}
                  >
                    {CREDIT_PURPOSES.map((p) => (
                      <MenuItem key={p} value={p}>
                        {t(`payments.credit.purposes.${p}`)}
                      </MenuItem>
                    ))}
                  </TextField>
                  {creditPurpose === 'LOAN_PREPAYMENT' ? (
                    <TextField
                      select
                      fullWidth
                      size="small"
                      label={t('payments.credit.loan')}
                      value={creditLoan}
                      onChange={(e) => setCreditLoan(e.target.value)}
                      helperText={!creditLoan ? t('payments.credit.needsLoan') : undefined}
                    >
                      {availableLoans.map((l) => (
                        <MenuItem key={l.id} value={l.id}>
                          {shortId(l.id)}
                        </MenuItem>
                      ))}
                    </TextField>
                  ) : null}
                  <Button
                    variant="outlined"
                    onClick={addCredit}
                    disabled={creditPurpose === 'LOAN_PREPAYMENT' && !creditLoan}
                    sx={{ minHeight: 40 }}
                  >
                    {t('payments.credit.hold', { amount: money(total - allocated) })}
                  </Button>
                </Stack>
              </Stack>
            </Paper>
          ) : null}

          {drafts.length === 0 ? (
            <Typography variant="body2" color="text.secondary">
              {t('payments.allocation.empty')}
            </Typography>
          ) : null}

          {drafts.map((draft) => {
            const contribution = draft.type === 'CONTRIBUTION' ? contributionFor(draft) : undefined
            const loan = draft.type === 'LOAN_REPAYMENT' ? loanFor(draft) : undefined
            const problem = problemFor(draft.key)
            return (
              <Paper
                key={draft.key}
                elevation={0}
                sx={{ p: 2, border: '1px solid', borderColor: 'divider' }}
                data-testid="payment-allocation-draft"
              >
                <Stack spacing={1}>
                  <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center' }}>
                    <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                      <Chip
                        size="small"
                        label={t(
                          draft.type === 'CONTRIBUTION'
                            ? 'payments.allocation.contribution'
                            : draft.type === 'MEMBER_CREDIT'
                              ? 'payments.credit.heldLine'
                              : 'payments.allocation.loan',
                        )}
                      />
                      <Typography variant="subtitle2">
                        {draft.type === 'CONTRIBUTION'
                          ? periodLabel(draft.periodYear, draft.periodMonth)
                          : draft.type === 'MEMBER_CREDIT'
                            ? t('payments.credit.line', { purpose: t(`payments.credit.purposes.${draft.creditPurpose}`) })
                            : t('payments.allocation.loanLine', { loan: shortId(draft.loanId) })}
                      </Typography>
                    </Stack>
                    <IconButton
                      aria-label={t('payments.allocation.remove')}
                      onClick={() => removeDraft(draft.key)}
                      size="small"
                    >
                      <DeleteOutlinedIcon fontSize="small" />
                    </IconButton>
                  </Stack>

                  {contribution ? (
                    <Stack spacing={0.25}>
                      <Typography variant="caption" color="text.secondary">
                        {t('payments.allocation.contributionFacts', {
                          expected: formatMoney(contribution.expectedAmount),
                          paid: formatMoney(contribution.paidAmount),
                          outstanding: formatMoney(contribution.outstandingAmount),
                        })}
                      </Typography>
                      <ObligationDetails fields={contribution} />
                    </Stack>
                  ) : null}
                  {loan ? (
                    <Typography variant="caption" color="text.secondary">
                      {t('payments.allocation.loanFacts', {
                        outstanding: money(loanOutstandingUnits(loan)),
                        repaid: money(
                          (toUnits(loan.totalRepaidPrincipal) ?? 0) +
                            (toUnits(loan.totalRepaidInterest) ?? 0) +
                            (toUnits(loan.totalRepaidPenalty) ?? 0),
                        ),
                      })}
                    </Typography>
                  ) : null}
                  {draft.type === 'LOAN_REPAYMENT' ? (
                    <Typography variant="caption" color="text.secondary">
                      {t('payments.allocation.engineSplits')}
                    </Typography>
                  ) : null}

                  <TextField
                    size="small"
                    label={t('payments.allocation.amount')}
                    value={draft.amount}
                    onChange={(e) => updateDraft(draft.key, e.target.value)}
                    error={Boolean(problem)}
                    helperText={problem ? problemText(problem) : undefined}
                    slotProps={{ htmlInput: { inputMode: 'decimal' } }}
                  />
                </Stack>
              </Paper>
            )
          })}

          <Box data-testid="payment-allocation-total">
            <Typography variant="body2">
              {t('payments.allocation.totals', { allocated: money(allocated), total: money(total ?? 0) })}
            </Typography>
            {attempted
              ? problems
                  .filter((p) => !('key' in p))
                  .map((p) => (
                    <Typography key={p.code} variant="caption" color="error" component="div" role="alert">
                      {problemText(p)}
                    </Typography>
                  ))
              : null}
          </Box>
        </Stack>
      </Paper>

      {submitError ? <ErrorNotice error={submitError} /> : null}

      <Stack direction="row" spacing={1}>
        <FinancialActionButton
          variant="contained"
          onClick={onSubmit}
          disabled={submit.isPending || receiptBlocked || reviewNotRequested || analyze.isPending}
          sx={{ minHeight: 44 }}
        >
          {submit.isPending ? t('payments.submit.submitting') : t('payments.submit.submit')}
        </FinancialActionButton>
      </Stack>
    </Stack>
  )
}
