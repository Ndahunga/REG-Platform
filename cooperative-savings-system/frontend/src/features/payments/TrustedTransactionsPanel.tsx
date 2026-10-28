import { Alert, Box, Button, Chip, MenuItem, Paper, Stack, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { getErrorMessage } from '@/shared/api/client'
import { fetchAttention, fetchTrustedTransactions, importTrustedTransactions } from '@/shared/api/paymentVerification'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import type {
  AttentionItem,
  ImportResponse,
  ProviderStatus,
  TrustedChannel,
  TrustedTransaction,
  TrustedTransactionLine,
} from '@/shared/types/paymentVerification'
import { formatMoney } from '@/shared/utils/formatMoney'
import { PaymentDetailDialog } from './PaymentDetailDialog'
import { VerificationCapabilitiesNotice } from './VerificationCapabilitiesNotice'
import { ErrorNotice, PaymentStatusChip } from './PaymentParts'
import { isPositiveAmount } from './paymentHelpers'
import { outcomeColor } from './VerificationOutcomeSection'
import { MoneyField } from '@/shared/components/MoneyField'

interface TrustedTransactionsPanelProps {
  cooperativeId: string
}

type ImportSource = TrustedTransactionLine['source']

const SOURCES: ImportSource[] = ['MOBILE_MONEY_STATEMENT_IMPORT', 'BANK_STATEMENT_IMPORT']
const CHANNELS_BY_SOURCE: Record<ImportSource, TrustedChannel[]> = {
  MOBILE_MONEY_STATEMENT_IMPORT: ['MTN_MOMO', 'AIRTEL_MONEY', 'OTHER'],
  BANK_STATEMENT_IMPORT: ['BANK_TRANSFER', 'OTHER'],
}
const STATUSES: ProviderStatus[] = ['SUCCESS', 'PENDING', 'FAILED']

const resultColor = (result: ImportResponse['results'][number]['result']) =>
  result === 'CREATED' || result === 'UPDATED' ? 'success' : result === 'DUPLICATE' ? 'default' : 'error'

/**
 * Payments whose evidence needs an officer's attention, and the authoritative records they are matched against.
 * Recording a statement line matches payments and records an outcome; it never verifies or posts a payment, and the
 * officer who recorded a line cannot verify the payment it confirms.
 */
export function TrustedTransactionsPanel({ cooperativeId }: TrustedTransactionsPanelProps) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const [openId, setOpenId] = useState<string | null>(null)
  const [source, setSource] = useState<ImportSource>('MOBILE_MONEY_STATEMENT_IMPORT')
  const [channel, setChannel] = useState<TrustedChannel>('MTN_MOMO')
  const [reference, setReference] = useState('')
  const [amount, setAmount] = useState('')
  const [currency, setCurrency] = useState('RWF')
  const [status, setStatus] = useState<ProviderStatus>('SUCCESS')
  const [recipient, setRecipient] = useState('')
  const [attempted, setAttempted] = useState(false)
  const [outcome, setOutcome] = useState<ImportResponse | null>(null)

  const attention = useQuery({ queryKey: ['payment-attention', cooperativeId], queryFn: () => fetchAttention(cooperativeId) })
  const transactions = useQuery({
    queryKey: ['trusted-transactions', cooperativeId],
    queryFn: () => fetchTrustedTransactions(cooperativeId),
  })
  const record = useMutation({
    mutationFn: (line: TrustedTransactionLine) => importTrustedTransactions(cooperativeId, [line]),
    onSuccess: async (result) => {
      setOutcome(result)
      setReference('')
      setAmount('')
      setAttempted(false)
      await queryClient.invalidateQueries({ queryKey: ['trusted-transactions', cooperativeId] })
      await queryClient.invalidateQueries({ queryKey: ['payment-attention', cooperativeId] })
      await queryClient.invalidateQueries({ queryKey: ['member-payments', cooperativeId] })
    },
  })

  const referenceMissing = reference.trim() === ''
  const amountInvalid = !isPositiveAmount(amount.trim())
  const currencyInvalid = !/^[A-Za-z]{3}$/.test(currency.trim())

  const submit = () => {
    setAttempted(true)
    if (referenceMissing || amountInvalid || currencyInvalid || record.isPending) return
    record.mutate({
      source,
      channel,
      reference: reference.trim(),
      amount: Number(amount.trim()),
      currency: currency.trim().toUpperCase(),
      status,
      ...(recipient.trim() ? { recipient: recipient.trim() } : {}),
    })
  }

  const attentionColumns: TableColumn<AttentionItem>[] = [
    { id: 'amount', label: t('payments.columns.amount'), render: (r) => formatMoney(r.amount, { currency: r.currency }) },
    { id: 'method', label: t('payments.columns.method'), render: (r) => t(`payments.method.${r.paymentMethod}`), hideOnMobile: true },
    { id: 'reference', label: t('payments.columns.reference'), render: (r) => r.transactionReference || '—', hideOnMobile: true },
    { id: 'status', label: t('payments.columns.status'), render: (r) => <PaymentStatusChip status={r.paymentStatus} /> },
    {
      id: 'outcome',
      label: t('payments.outcome.evidenceOutcome'),
      render: (r) => (
        <Stack spacing={0.5}>
          <Chip size="small" color={outcomeColor(r.outcome)} label={t(`payments.outcome.value.${r.outcome}`)} />
          {r.reasons.map((code) => (
            <Typography key={code} variant="caption" color="text.secondary">
              {t(`payments.outcome.reason.${code}`, { defaultValue: code })}
            </Typography>
          ))}
        </Stack>
      ),
    },
  ]

  const transactionColumns: TableColumn<TrustedTransaction>[] = [
    { id: 'reference', label: t('payments.columns.reference'), render: (r) => r.reference },
    { id: 'amount', label: t('payments.columns.amount'), render: (r) => formatMoney(r.amount, { currency: r.currency }) },
    { id: 'channel', label: t('payments.columns.method'), render: (r) => t(`payments.method.${r.channel}`), hideOnMobile: true },
    { id: 'source', label: t('payments.reconcile.source'), render: (r) => t(`payments.outcome.source.${r.source}`), hideOnMobile: true },
    { id: 'providerStatus', label: t('payments.columns.status'), render: (r) => t(`payments.reconcile.providerStatus.${r.providerStatus}`) },
    {
      id: 'flags',
      label: t('payments.reconcile.flags'),
      render: (r) => (
        <Stack direction="row" spacing={0.5} sx={{ flexWrap: 'wrap', rowGap: 0.5 }}>
          {r.consumed ? <Chip size="small" label={t('payments.reconcile.consumed')} /> : null}
          {r.conflicted ? <Chip size="small" color="error" label={t('payments.reconcile.conflicted')} /> : null}
          {!r.consumed && !r.conflicted ? '—' : null}
        </Stack>
      ),
    },
  ]

  return (
    <Stack spacing={3} data-testid="trusted-transactions-panel">
      <VerificationCapabilitiesNotice cooperativeId={cooperativeId} />
      <Box>
        <Typography variant="h6" gutterBottom>
          {t('payments.reconcile.attentionTitle')}
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
          {t('payments.reconcile.attentionHelp')}
        </Typography>
        {attention.isLoading ? <LoadingState variant="skeleton" rows={3} /> : null}
        {attention.isError ? (
          <ErrorState message={getErrorMessage(attention.error, t('errors.generic'))} onRetry={() => void attention.refetch()} />
        ) : null}
        {attention.data ? (
          <ResponsiveTable
            columns={attentionColumns}
            rows={attention.data}
            getRowId={(r) => r.paymentId}
            emptyTitle={t('payments.reconcile.attentionEmpty')}
            emptyDescription=""
            onRowClick={(r) => setOpenId(r.paymentId)}
          />
        ) : null}
      </Box>

      <Paper elevation={0} sx={{ p: 2, border: '1px solid', borderColor: 'divider' }} data-testid="trusted-import-form">
        <Typography variant="h6" gutterBottom>
          {t('payments.reconcile.importTitle')}
        </Typography>
        <Alert severity="info" sx={{ mb: 2 }}>
          {t('payments.reconcile.importHelp')}
        </Alert>
        <Stack spacing={2}>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              select
              fullWidth
              size="small"
              label={t('payments.reconcile.source')}
              value={source}
              onChange={(e) => {
                const next = e.target.value as ImportSource
                setSource(next)
                setChannel(CHANNELS_BY_SOURCE[next][0])
              }}
            >
              {SOURCES.map((s) => (
                <MenuItem key={s} value={s}>
                  {t(`payments.outcome.source.${s}`)}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              select
              fullWidth
              size="small"
              label={t('payments.columns.method')}
              value={channel}
              onChange={(e) => setChannel(e.target.value as TrustedChannel)}
            >
              {CHANNELS_BY_SOURCE[source].map((c) => (
                <MenuItem key={c} value={c}>
                  {t(`payments.method.${c}`)}
                </MenuItem>
              ))}
            </TextField>
          </Stack>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              fullWidth
              size="small"
              label={t('payments.columns.reference')}
              value={reference}
              onChange={(e) => setReference(e.target.value)}
              error={attempted && referenceMissing}
              helperText={attempted && referenceMissing ? t('payments.reconcile.referenceRequired') : undefined}
            />
            <MoneyField
              fullWidth
              size="small"
              label={t('payments.columns.amount')}
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              error={attempted && amountInvalid}
              helperText={attempted && amountInvalid ? t('payments.reconcile.amountInvalid') : undefined}
              slotProps={{ htmlInput: { inputMode: 'decimal' } }}
            />
            <TextField
              size="small"
              label={t('payments.reconcile.currency')}
              value={currency}
              onChange={(e) => setCurrency(e.target.value)}
              error={attempted && currencyInvalid}
              sx={{ maxWidth: { sm: 120 } }}
            />
          </Stack>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              select
              fullWidth
              size="small"
              label={t('payments.reconcile.providerStatusLabel')}
              value={status}
              onChange={(e) => setStatus(e.target.value as ProviderStatus)}
            >
              {STATUSES.map((s) => (
                <MenuItem key={s} value={s}>
                  {t(`payments.reconcile.providerStatus.${s}`)}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              fullWidth
              size="small"
              label={t('payments.reconcile.recipient')}
              value={recipient}
              onChange={(e) => setRecipient(e.target.value)}
            />
          </Stack>
          {record.isError ? <ErrorNotice error={record.error} /> : null}
          {outcome ? (
            <Stack spacing={0.5} data-testid="trusted-import-result">
              {outcome.results.map((r) => (
                <Stack key={`${r.reference}-${r.result}`} direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                  <Chip size="small" color={resultColor(r.result)} label={t(`payments.reconcile.result.${r.result}`)} />
                  <Typography variant="body2">{r.reference}</Typography>
                  {r.reason ? (
                    <Typography variant="caption" color="text.secondary">
                      {t(`payments.errors.${r.reason}`, { defaultValue: r.reason })}
                    </Typography>
                  ) : null}
                </Stack>
              ))}
            </Stack>
          ) : null}
          <Box>
            <Button variant="contained" onClick={submit} disabled={record.isPending} sx={{ minHeight: 44 }} data-testid="trusted-import-submit">
              {record.isPending ? t('payments.reconcile.recording') : t('payments.reconcile.record')}
            </Button>
          </Box>
        </Stack>
      </Paper>

      <Box>
        <Typography variant="h6" gutterBottom>
          {t('payments.reconcile.recordedTitle')}
        </Typography>
        {transactions.isLoading ? <LoadingState variant="skeleton" rows={3} /> : null}
        {transactions.isError ? (
          <ErrorState message={getErrorMessage(transactions.error, t('errors.generic'))} onRetry={() => void transactions.refetch()} />
        ) : null}
        {transactions.data ? (
          <ResponsiveTable
            columns={transactionColumns}
            rows={transactions.data}
            getRowId={(r) => r.id}
            emptyTitle={t('payments.reconcile.recordedEmpty')}
            emptyDescription=""
          />
        ) : null}
      </Box>

      {openId ? <PaymentDetailDialog cooperativeId={cooperativeId} paymentId={openId} staff onClose={() => setOpenId(null)} /> : null}
    </Stack>
  )
}
