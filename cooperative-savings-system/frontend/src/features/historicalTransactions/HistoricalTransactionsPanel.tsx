import {
  Alert,
  Box,
  Button,
  Checkbox,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  LinearProgress,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useAppSelector } from '@/app/store/hooks'
import { localizedApiError } from '@/shared/utils/localizedApiError'
import {
  approveHistoricalTransactions,
  cancelHistoricalTransactions,
  downloadHistoricalTransactionTemplate,
  fetchHistoricalTransactionBatches,
  fetchHistoricalTransactionEvents,
  fetchHistoricalTransactionRows,
  previewHistoricalTransactions,
} from '@/shared/api/historicalTransactions'
import { ErrorState } from '@/shared/components/ErrorState'
import { FinancialActionButton } from '@/shared/components/FinancialActionButton'
import { LoadingState } from '@/shared/components/LoadingState'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import { ErrorNotice } from '@/features/payments/PaymentParts'
import { PERMISSION_CONTRIBUTION_WRITE, hasPermission } from '@/shared/types/auth'
import type { HistoricalBatch, HistoricalIssue, HistoricalRow } from '@/shared/types/historicalTransactions'
import { formatMoney } from '@/shared/utils/formatMoney'

interface HistoricalTransactionsPanelProps {
  cooperativeId: string
}

const statusColor = (status: HistoricalBatch['status']) =>
  status === 'POSTED' ? 'success' : status === 'CANCELLED' ? 'default' : 'warning'

export const isZero = (value: string | number | null | undefined): boolean => value !== null && value !== undefined && Number(value) === 0

/**
 * The guided multi-year history import. Uploading validates the workbook and stores a dry run: nothing is posted. A
 * different officer with fund authority approves it, after which it is posted in one transaction and is final. Imported
 * contributions are never member capital or recipient money until their class is known, and never payout-eligible.
 */
export function HistoricalTransactionsPanel({ cooperativeId }: HistoricalTransactionsPanelProps) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const queryClient = useQueryClient()
  const fileInput = useRef<HTMLInputElement | null>(null)
  const [file, setFile] = useState<File | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)

  const batches = useQuery({
    queryKey: ['historical-transactions', cooperativeId],
    queryFn: () => fetchHistoricalTransactionBatches(cooperativeId),
  })
  const template = useMutation({
    mutationFn: () => downloadHistoricalTransactionTemplate(cooperativeId),
    onSuccess: ({ filename }) => enqueueSnackbar(t('historicalTransactions.templateSuccess', { filename }), { variant: 'success' }),
    onError: (error) => enqueueSnackbar(localizedApiError(t, error, 'historicalTransactions.errorCodes'), { variant: 'error' }),
  })
  const preview = useMutation({
    mutationFn: (selected: File) => previewHistoricalTransactions(cooperativeId, selected),
    onSuccess: async (batch) => {
      setSelectedId(batch.id)
      await queryClient.invalidateQueries({ queryKey: ['historical-transactions', cooperativeId] })
    },
  })

  const list = batches.data ?? []
  const selected = list.find((b) => b.id === selectedId) ?? (preview.data && preview.data.id === selectedId ? preview.data : null)

  const columns: TableColumn<HistoricalBatch>[] = [
    { id: 'file', label: t('historicalTransactions.history.file'), render: (b) => b.fileName },
    { id: 'status', label: t('payments.columns.status'), render: (b) => <Chip size="small" color={statusColor(b.status)} label={t(`historicalTransactions.status.${b.status}`)} /> },
    { id: 'rows', label: t('historicalTransactions.counts.rows'), render: (b) => b.totalRows, hideOnMobile: true },
    { id: 'errors', label: t('historicalTransactions.counts.errors'), render: (b) => b.errorCount },
    { id: 'uploaded', label: t('historicalTransactions.history.uploaded'), render: (b) => new Date(b.uploadedAt).toLocaleString(), hideOnMobile: true },
  ]

  return (
    <Stack spacing={3} data-testid="historical-transactions-panel">
      <Alert severity="info" data-testid="historical-transactions-intro">
        <Typography variant="body2" sx={{ fontWeight: 600 }}>
          {t('historicalTransactions.intro')}
        </Typography>
        <ul style={{ margin: '8px 0 0 18px', padding: 0 }}>
          {['sheets', 'noInvention', 'loans', 'overlap', 'unresolved', 'final'].map((rule) => (
            <li key={rule}>
              <Typography variant="body2">{t(`historicalTransactions.rules.${rule}`)}</Typography>
            </li>
          ))}
        </ul>
      </Alert>

      <Paper elevation={0} sx={{ p: 2, border: '1px solid', borderColor: 'divider' }}>
        <Stack spacing={2}>
          <Box>
            <Button variant="outlined" onClick={() => template.mutate()} disabled={template.isPending} sx={{ minHeight: 44 }} data-testid="ht-template">
              {t('historicalTransactions.template')}
            </Button>
          </Box>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ alignItems: { sm: 'center' } }}>
            <input
              ref={fileInput}
              type="file"
              accept=".xlsx"
              hidden
              data-testid="ht-file"
              onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            />
            <Button variant="outlined" onClick={() => fileInput.current?.click()} sx={{ minHeight: 44 }}>
              {t('historicalTransactions.chooseFile')}
            </Button>
            <Typography variant="body2" color="text.secondary" data-testid="ht-file-name">
              {file ? file.name : t('historicalTransactions.noFile')}
            </Typography>
            <FinancialActionButton
              variant="contained"
              disabled={!file || preview.isPending}
              onClick={() => file && preview.mutate(file)}
              sx={{ minHeight: 44 }}
              data-testid="ht-validate"
            >
              {preview.isPending ? t('historicalTransactions.validating') : t('historicalTransactions.validate')}
            </FinancialActionButton>
          </Stack>
          {preview.isPending ? <LinearProgress /> : null}
          {preview.isError ? <ErrorNotice error={preview.error} /> : null}
          {preview.data?.reused ? (
            <Alert severity="info" data-testid="ht-reused">
              {t('historicalTransactions.reused')}
            </Alert>
          ) : null}
        </Stack>
      </Paper>

      {selected ? <BatchDetail cooperativeId={cooperativeId} batch={selected} onChanged={() => void batches.refetch()} /> : null}

      <Box>
        <Typography variant="h6" gutterBottom>
          {t('historicalTransactions.history.title')}
        </Typography>
        {batches.isLoading ? <LoadingState variant="skeleton" rows={3} /> : null}
        {batches.isError ? (
          <ErrorState message={localizedApiError(t, batches.error, 'historicalTransactions.errorCodes')} onRetry={() => void batches.refetch()} />
        ) : null}
        {batches.data ? (
          <ResponsiveTable
            columns={columns}
            rows={list}
            getRowId={(b) => b.id}
            emptyTitle={t('historicalTransactions.history.empty')}
            emptyDescription=""
            onRowClick={(b) => setSelectedId(b.id)}
          />
        ) : null}
      </Box>
    </Stack>
  )
}

// ---------------------------------------------------------------------------------------------------------------------

function BatchDetail({ cooperativeId, batch, onChanged }: { cooperativeId: string; batch: HistoricalBatch; onChanged: () => void }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const user = useAppSelector((s) => s.auth.user)
  const [approveOpen, setApproveOpen] = useState(false)
  const [cancelError, setCancelError] = useState<unknown>(null)
  const summary = batch.summary
  const recon = summary?.reconciliation
  const balanced = recon?.balanced === true
  const canDecide = hasPermission(user, 'FUND_AUTHORIZE')
  const isUploader = user?.id === batch.uploadedBy
  const canCancel = hasPermission(user, PERMISSION_CONTRIBUTION_WRITE) || canDecide

  const rows = useQuery({
    queryKey: ['historical-transactions', cooperativeId, batch.id, 'errors'],
    queryFn: () => fetchHistoricalTransactionRows(cooperativeId, batch.id, true),
    enabled: batch.errorCount > 0,
  })
  const events = useQuery({
    queryKey: ['historical-transactions', cooperativeId, batch.id, 'events'],
    queryFn: () => fetchHistoricalTransactionEvents(cooperativeId, batch.id),
  })
  const cancel = useMutation({
    mutationFn: () => cancelHistoricalTransactions(cooperativeId, batch.id),
    onSuccess: async () => {
      setCancelError(null)
      await queryClient.invalidateQueries({ queryKey: ['historical-transactions', cooperativeId] })
      onChanged()
    },
    onError: (error) => setCancelError(error),
  })

  const errorColumns: TableColumn<HistoricalRow>[] = [
    { id: 'row', label: t('historicalTransactions.errorsTable.row'), render: (r) => `${r.sheet === 'MEMBERS' ? t('historicalTransactions.errorsTable.membersSheet') : t('historicalTransactions.errorsTable.transactionsSheet')} #${r.rowNumber}` },
    { id: 'type', label: t('historicalTransactions.errorsTable.type'), render: (r) => r.rowType, hideOnMobile: true },
    { id: 'issues', label: t('historicalTransactions.errorsTable.issues'), render: (r) => <Issues issues={r.errors} /> },
  ]

  return (
    <Paper elevation={0} sx={{ p: 2, border: '1px solid', borderColor: 'divider' }} data-testid="ht-batch">
      <Stack spacing={2}>
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 0.5 }}>
          <Typography variant="h6">{batch.fileName}</Typography>
          <Chip size="small" color={statusColor(batch.status)} label={t(`historicalTransactions.status.${batch.status}`)} data-testid="ht-status" />
        </Stack>
        <Typography variant="body2" color="text.secondary" data-testid="ht-counts">
          {t('historicalTransactions.counts.summary', { rows: batch.totalRows, valid: batch.validRows, errors: batch.errorCount })}
        </Typography>

        {batch.errorCount === 0 && batch.status === 'PREVIEWED' ? (
          <Alert severity="success" data-testid="ht-ready">
            {t('historicalTransactions.ready')}
          </Alert>
        ) : null}
        {batch.errorCount > 0 ? (
          <Alert severity="error" data-testid="ht-has-errors">
            {t('historicalTransactions.hasErrors', { count: batch.errorCount })}
          </Alert>
        ) : null}

        {recon ? (
          <Box data-testid="ht-reconciliation">
            <Typography variant="subtitle2" gutterBottom>
              {t('historicalTransactions.reconciliation.title')}
            </Typography>
            <Stack spacing={0.5}>
              <Line label={t('historicalTransactions.reconciliation.opening')} value={recon.opening} />
              <Line label={t('historicalTransactions.reconciliation.computed')} value={recon.computedClosing} testId="ht-computed" />
              <Line label={t('historicalTransactions.reconciliation.declared')} value={recon.declaredClosing} testId="ht-declared" />
              <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                <Typography variant="body2" sx={{ minWidth: 220 }}>
                  {t('historicalTransactions.reconciliation.variance')}
                </Typography>
                <Typography variant="body2" data-testid="ht-variance">
                  {recon.variance === null ? '—' : formatMoney(recon.variance)}
                </Typography>
                {recon.variance !== null ? (
                  <Chip
                    size="small"
                    color={balanced ? 'success' : 'warning'}
                    label={balanced ? t('historicalTransactions.reconciliation.balanced') : t('historicalTransactions.reconciliation.notBalanced')}
                    data-testid="ht-balanced"
                  />
                ) : null}
              </Stack>
            </Stack>
            {recon.byYear.length > 0 ? (
              <Box sx={{ overflowX: 'auto', mt: 1 }}>
                <table style={{ borderCollapse: 'collapse', minWidth: 360 }} data-testid="ht-by-year">
                  <thead>
                    <tr>
                      {['year', 'credits', 'debits', 'net'].map((h) => (
                        <th key={h} style={{ textAlign: 'left', padding: '4px 12px 4px 0' }}>
                          {t(`historicalTransactions.reconciliation.${h}`)}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {recon.byYear.map((y) => (
                      <tr key={y.year}>
                        <td style={{ padding: '2px 12px 2px 0' }}>{y.year}</td>
                        <td style={{ padding: '2px 12px 2px 0' }}>{formatMoney(y.credits)}</td>
                        <td style={{ padding: '2px 12px 2px 0' }}>{formatMoney(y.debits)}</td>
                        <td style={{ padding: '2px 12px 2px 0' }}>{formatMoney(y.net)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </Box>
            ) : null}
            {recon.loans.length > 0 ? (
              <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 1 }} data-testid="ht-loans">
                {t('historicalTransactions.reconciliation.loansClosed', { count: recon.loans.filter((l) => l.closed).length, total: recon.loans.length })}
              </Typography>
            ) : null}
            {Number(recon.unresolvedAttribution.amount) > 0 ? (
              <Alert severity="warning" sx={{ mt: 1 }} data-testid="ht-unresolved">
                {t('historicalTransactions.unresolved', { amount: formatMoney(recon.unresolvedAttribution.amount), rows: recon.unresolvedAttribution.rows })}
              </Alert>
            ) : null}
          </Box>
        ) : null}

        {summary && summary.sheetIssues.length > 0 ? (
          <Box data-testid="ht-sheet-issues">
            <Typography variant="subtitle2">{t('historicalTransactions.sheetIssues')}</Typography>
            <Issues issues={summary.sheetIssues} />
          </Box>
        ) : null}
        {summary && summary.warnings.length > 0 ? (
          <Alert severity="warning" data-testid="ht-warnings">
            <Typography variant="body2" sx={{ fontWeight: 600 }}>
              {t('historicalTransactions.warnings')}
            </Typography>
            {summary.warnings.slice(0, 20).map((w) => (
              <Typography key={w} variant="caption" component="div">
                {w}
              </Typography>
            ))}
          </Alert>
        ) : null}

        {batch.errorCount > 0 ? (
          <Box data-testid="ht-row-errors">
            <Typography variant="subtitle2" gutterBottom>
              {t('historicalTransactions.errorsTable.title')}
            </Typography>
            {rows.isLoading ? <LoadingState variant="skeleton" rows={3} /> : null}
            {rows.isError ? <ErrorNotice error={rows.error} /> : null}
            {rows.data ? <ResponsiveTable columns={errorColumns} rows={rows.data} getRowId={(r) => `${r.sheet}-${r.rowNumber}`} /> : null}
          </Box>
        ) : null}

        {batch.status === 'POSTED' ? (
          <Alert severity="success" data-testid="ht-posted">
            {t('historicalTransactions.posted')}
            {batch.varianceAcknowledged && batch.varianceNote ? ` ${t('historicalTransactions.varianceRecorded', { note: batch.varianceNote })}` : ''}
          </Alert>
        ) : null}

        {batch.status === 'PREVIEWED' ? (
          <Stack spacing={1}>
            {cancelError ? <ErrorNotice error={cancelError} /> : null}
            <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', rowGap: 1 }}>
              {canDecide && !isUploader ? (
                <FinancialActionButton
                  variant="contained"
                  disabled={!batch.canApprove}
                  onClick={() => setApproveOpen(true)}
                  sx={{ minHeight: 44 }}
                  data-testid="ht-approve"
                >
                  {t('historicalTransactions.approve')}
                </FinancialActionButton>
              ) : null}
              {canCancel ? (
                <Button variant="outlined" color="error" onClick={() => cancel.mutate()} disabled={cancel.isPending} sx={{ minHeight: 44 }} data-testid="ht-cancel">
                  {t('historicalTransactions.cancel')}
                </Button>
              ) : null}
            </Stack>
            {canDecide && isUploader ? (
              <Alert severity="info" data-testid="ht-needs-another">
                {t('historicalTransactions.needsAnotherOfficer')}
              </Alert>
            ) : null}
            {!canDecide ? (
              <Alert severity="info" data-testid="ht-needs-authority">
                {t('historicalTransactions.needsFundAuthority')}
              </Alert>
            ) : null}
          </Stack>
        ) : null}

        {events.data && events.data.length > 0 ? (
          <Box data-testid="ht-events">
            <Typography variant="caption" color="text.secondary">
              {t('historicalTransactions.history.events')}
            </Typography>
            {events.data.map((e, i) => (
              <Typography key={`${e.createdAt}-${i}`} variant="caption" component="div">
                {new Date(e.createdAt).toLocaleString()} — {t(`historicalTransactions.status.${e.toStatus}`)}
                {e.comment ? `: ${e.comment}` : ''}
              </Typography>
            ))}
          </Box>
        ) : null}
      </Stack>

      {approveOpen ? (
        <ApproveDialog
          cooperativeId={cooperativeId}
          batch={batch}
          balanced={balanced}
          onClose={() => setApproveOpen(false)}
          onDone={async () => {
            setApproveOpen(false)
            await queryClient.invalidateQueries({ queryKey: ['historical-transactions', cooperativeId] })
            await queryClient.invalidateQueries({ queryKey: ['model-summary', cooperativeId] })
            onChanged()
          }}
        />
      ) : null}
    </Paper>
  )
}

function Line({ label, value, testId }: { label: string; value: string | number | null; testId?: string }) {
  return (
    <Stack direction="row" spacing={1}>
      <Typography variant="body2" sx={{ minWidth: 220 }}>
        {label}
      </Typography>
      <Typography variant="body2" data-testid={testId}>
        {value === null ? '—' : formatMoney(value)}
      </Typography>
    </Stack>
  )
}

export function Issues({ issues }: { issues: HistoricalIssue[] }) {
  const { t } = useTranslation()
  return (
    <Stack spacing={0.5}>
      {issues.map((issue, i) => (
        <Box key={`${issue.code}-${i}`}>
          <Typography variant="body2">{t(`historicalTransactions.issue.${issue.code}`, { defaultValue: issue.message })}</Typography>
          <Typography variant="caption" color="text.secondary">
            {issue.code}
            {issue.field ? ` · ${issue.field}` : ''}
          </Typography>
        </Box>
      ))}
    </Stack>
  )
}

function ApproveDialog({
  cooperativeId,
  batch,
  balanced,
  onClose,
  onDone,
}: {
  cooperativeId: string
  batch: HistoricalBatch
  balanced: boolean
  onClose: () => void
  onDone: () => void
}) {
  const { t } = useTranslation()
  const [acknowledge, setAcknowledge] = useState(false)
  const [note, setNote] = useState('')
  const [attempted, setAttempted] = useState(false)
  const mutation = useMutation({
    mutationFn: () => approveHistoricalTransactions(cooperativeId, batch.id, { acknowledgeVariance: !balanced && acknowledge, note: note.trim() || undefined }),
    onSuccess: () => onDone(),
  })
  const varianceMissing = !balanced && (!acknowledge || note.trim().length < 10)

  const submit = () => {
    setAttempted(true)
    if (varianceMissing || mutation.isPending) return
    mutation.mutate()
  }

  return (
    <Dialog open onClose={mutation.isPending ? undefined : onClose} fullWidth maxWidth="sm" aria-label={t('historicalTransactions.approveTitle')}>
      <DialogTitle>{t('historicalTransactions.approveTitle')}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <Alert severity="warning">{t('historicalTransactions.approveExplain', { rows: batch.totalRows })}</Alert>
          {!balanced ? (
            <>
              <Alert severity="error" data-testid="ht-variance-alert">
                {t('historicalTransactions.varianceAlert', { variance: batch.balanceVariance === null ? '—' : formatMoney(batch.balanceVariance) })}
              </Alert>
              <FormControlLabel
                control={<Checkbox checked={acknowledge} onChange={(_, v) => setAcknowledge(v)} />}
                label={t('historicalTransactions.acknowledge')}
              />
              <TextField
                label={t('historicalTransactions.varianceNote')}
                value={note}
                onChange={(e) => setNote(e.target.value)}
                multiline
                minRows={2}
                error={attempted && varianceMissing}
                helperText={attempted && varianceMissing ? t('historicalTransactions.varianceRequired') : undefined}
              />
            </>
          ) : null}
          {mutation.isPending ? <LinearProgress data-testid="ht-posting-progress" /> : null}
          {mutation.isError ? <ErrorNotice error={mutation.error} /> : null}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose} disabled={mutation.isPending}>
          {t('common.cancel')}
        </Button>
        <FinancialActionButton variant="contained" onClick={submit} disabled={mutation.isPending} sx={{ minHeight: 44 }} data-testid="ht-approve-confirm">
          {mutation.isPending ? t('historicalTransactions.approving') : t('historicalTransactions.approveConfirm')}
        </FinancialActionButton>
      </DialogActions>
    </Dialog>
  )
}
