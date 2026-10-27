import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  Stack,
  Switch,
  Tab,
  Tabs,
  TextField,
  Typography,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import dayjs from 'dayjs'
import { useSnackbar } from 'notistack'
import { useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useAppSelector } from '@/app/store/hooks'
import { localizedApiError } from '@/shared/utils/localizedApiError'
import { MigrationReviewNotice } from '@/features/historicalTransactions/MigrationReviewNotice'
import {
  cancelHistoricalImport,
  confirmHistoricalImport,
  downloadHistoricalImportLoanTemplate,
  downloadHistoricalImportTemplate,
  fetchHistoricalImportHistory,
  previewHistoricalImport,
} from '@/shared/api/historicalImport'
import { ConfirmDialog } from '@/shared/components/ConfirmDialog'
import { EmptyState } from '@/shared/components/EmptyState'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { PageHeader } from '@/shared/components/PageHeader'
import { HistoricalTransactionsPanel } from '@/features/historicalTransactions/HistoricalTransactionsPanel'
import { OpeningWorkbookPanel } from '@/features/historicalTransactions/OpeningWorkbookPanel'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import { importStatusColor } from '@/features/reports/reportHelpers'
import {
  canCancelHistoricalImport,
  isHistoricalImportReady,
  isLegacyHistoricalImport,
  isValidOpeningFunds,
  type HistoricalImportConfirm,
  type HistoricalImportError,
  type HistoricalImportPreview,
  type HistoricalImportSummary,
} from '@/shared/types/historicalImport'
import { formatMoney } from '@/shared/utils/formatMoney'
import { todayInKigaliIso } from '@/shared/utils/rwandaCooperative'

function OpeningBalanceImport() {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const queryClient = useQueryClient()
  const cooperativeId = useAppSelector((s) => s.auth.selectedCooperativeId)
  const fileInputRef = useRef<HTMLInputElement | null>(null)
  const [file, setFile] = useState<File | null>(null)
  const [cutoverDate, setCutoverDate] = useState('')
  const [openingFunds, setOpeningFunds] = useState('')
  const [loansMode, setLoansMode] = useState(false)
  const [preview, setPreview] = useState<HistoricalImportPreview | null>(null)
  const [confirmResult, setConfirmResult] = useState<HistoricalImportConfirm | null>(null)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [revisionReason, setRevisionReason] = useState('')
  const [cancelTarget, setCancelTarget] = useState<HistoricalImportSummary | null>(null)

  const historyQuery = useQuery({
    queryKey: ['historical-imports', cooperativeId],
    queryFn: () => fetchHistoricalImportHistory(cooperativeId!),
    enabled: Boolean(cooperativeId),
  })

  const templateMutation = useMutation({
    mutationFn: () => downloadHistoricalImportTemplate(cooperativeId!),
    onSuccess: ({ filename }) => {
      enqueueSnackbar(t('historicalImport.templateSuccess', { filename }), { variant: 'success' })
    },
    onError: (error) => {
      enqueueSnackbar(localizedApiError(t, error, 'historicalImport.errorCodes'), { variant: 'error' })
    },
  })

  const loanTemplateMutation = useMutation({
    mutationFn: () => downloadHistoricalImportLoanTemplate(cooperativeId!),
    onSuccess: ({ filename }) => {
      enqueueSnackbar(t('historicalImport.templateSuccess', { filename }), { variant: 'success' })
    },
    onError: (error) => {
      enqueueSnackbar(localizedApiError(t, error, 'historicalImport.errorCodes'), { variant: 'error' })
    },
  })

  const previewMutation = useMutation({
    mutationFn: (selected: File) =>
      previewHistoricalImport(cooperativeId!, selected, cutoverDate, loansMode ? '' : openingFunds.trim()),
    onSuccess: (data) => {
      setPreview(data)
      setConfirmResult(null)
      enqueueSnackbar(t('historicalImport.previewSuccess'), { variant: 'success' })
      void queryClient.invalidateQueries({ queryKey: ['historical-imports'] })
    },
    onError: (error) => {
      enqueueSnackbar(localizedApiError(t, error, 'historicalImport.errorCodes'), { variant: 'error' })
    },
  })

  const confirmMutation = useMutation({
    mutationFn: () =>
      confirmHistoricalImport(
        cooperativeId!,
        preview!.importId,
        preview?.revisionOfImportId ? revisionReason.trim() : undefined,
      ),
    onSuccess: (data) => {
      setConfirmResult(data)
      setConfirmOpen(false)
      setRevisionReason('')
      setPreview(null)
      setFile(null)
      if (fileInputRef.current) fileInputRef.current.value = ''
      enqueueSnackbar(t('historicalImport.confirmSuccess'), { variant: 'success' })
      void queryClient.invalidateQueries({ queryKey: ['historical-imports'] })
      void queryClient.invalidateQueries({ queryKey: ['dashboard'] })
      void queryClient.invalidateQueries({ queryKey: ['members'] })
      void queryClient.invalidateQueries({ queryKey: ['loans'] })
      void queryClient.invalidateQueries({ queryKey: ['contributions'] })
      void queryClient.invalidateQueries({ queryKey: ['shares'] })
    },
    onError: (error) => {
      enqueueSnackbar(localizedApiError(t, error, 'historicalImport.errorCodes'), { variant: 'error' })
    },
  })

  const cancelMutation = useMutation({
    mutationFn: (importId: string) => cancelHistoricalImport(cooperativeId!, importId),
    onSuccess: () => {
      enqueueSnackbar(t('historicalImport.cancelSuccess'), { variant: 'success' })
      setCancelTarget(null)
      if (preview && cancelTarget?.id === preview.importId) {
        setPreview(null)
      }
      void queryClient.invalidateQueries({ queryKey: ['historical-imports'] })
    },
    onError: (error) => {
      enqueueSnackbar(localizedApiError(t, error, 'historicalImport.errorCodes'), { variant: 'error' })
    },
  })

  const errorColumns: TableColumn<HistoricalImportError>[] = useMemo(
    () => [
      { id: 'sheet', label: t('historicalImport.fields.sheet'), render: (row) => row.sheet || '—' },
      { id: 'row', label: t('historicalImport.fields.row'), render: (row) => row.rowNumber ?? '—' },
      { id: 'field', label: t('historicalImport.fields.field'), render: (row) => row.field || '—' },
      {
        id: 'message',
        label: t('historicalImport.fields.error'),
        render: (row) =>
          row.code ? t(`historicalImport.rowErrors.${row.code}`, { defaultValue: row.message || row.code }) : row.message || '—',
      },
    ],
    [t],
  )

  const historyColumns: TableColumn<HistoricalImportSummary>[] = useMemo(
    () => [
      {
        id: 'file',
        label: t('historicalImport.fields.filename'),
        render: (row) => row.originalFilename || '—',
      },
      {
        id: 'cutover',
        label: t('historicalImport.fields.cutoverDate'),
        render: (row) =>
          isLegacyHistoricalImport(row.format) || !row.cutoverDate ? (
            <Chip size="small" variant="outlined" label={t('historicalImport.legacyFormat')} />
          ) : (
            dayjs(row.cutoverDate).format('YYYY-MM-DD')
          ),
      },
      {
        id: 'uploadedBy',
        label: t('historicalImport.fields.uploadedBy'),
        render: (row) => row.uploadedBy || '—',
        hideOnMobile: true,
      },
      {
        id: 'created',
        label: t('historicalImport.fields.uploadedAt'),
        render: (row) => (row.createdAt ? dayjs(row.createdAt).format('YYYY-MM-DD HH:mm') : '—'),
      },
      {
        id: 'status',
        label: t('historicalImport.fields.status'),
        render: (row) => (
          <Chip
            size="small"
            color={importStatusColor(String(row.status))}
            label={t(`historicalImport.status.${row.status}`, { defaultValue: String(row.status) })}
          />
        ),
      },
      {
        id: 'counts',
        label: t('historicalImport.fields.counts'),
        render: (row) =>
          t('historicalImport.countsSummary', {
            total: row.totalRows,
            valid: row.validRows,
            invalid: row.invalidRows,
          }),
        hideOnMobile: true,
      },
      {
        id: 'confirmed',
        label: t('historicalImport.fields.confirmedAt'),
        render: (row) =>
          row.confirmedAt ? dayjs(row.confirmedAt).format('YYYY-MM-DD HH:mm') : '—',
        hideOnMobile: true,
      },
      {
        id: 'revision',
        label: t('historicalImport.fields.revision'),
        render: (row) =>
          row.status === 'SUPERSEDED'
            ? t('historicalImport.revision.replacedOn', {
                date: row.supersededAt ? dayjs(row.supersededAt).format('YYYY-MM-DD HH:mm') : '—',
              })
            : row.revisionOfImportId && row.revisionReason
              ? t('historicalImport.revision.correction', { reason: row.revisionReason })
              : '—',
        hideOnMobile: true,
      },
      {
        id: 'actions',
        label: t('historicalImport.fields.actions'),
        render: (row) =>
          canCancelHistoricalImport(String(row.status)) ? (
            <Button size="small" onClick={() => setCancelTarget(row)}>
              {t('historicalImport.cancel')}
            </Button>
          ) : (
            '—'
          ),
      },
    ],
    [t],
  )

  if (!cooperativeId) {
    return (
      <Box>
        <PageHeader
          title={t('pages.historicalImport.title')}
          description={t('pages.historicalImport.description')}
        />
        <EmptyState
          title={t('historicalImport.selectCooperativeTitle')}
          description={t('historicalImport.selectCooperativeDescription')}
        />
      </Box>
    )
  }

  const confirmEnabled = isHistoricalImportReady(preview)
  const migration = preview?.migration
  const previewCutover = preview?.cutoverDate ?? migration?.cutoverDate ?? cutoverDate
  const fundsValid = isValidOpeningFunds(openingFunds)
  const canPreview = Boolean(file) && Boolean(cutoverDate) && (loansMode || fundsValid) && !previewMutation.isPending

  return (
    <Box>
      <PageHeader
        title={t('pages.historicalImport.title')}
        description={t('pages.historicalImport.description')}
      />

      <MigrationReviewNotice cooperativeId={cooperativeId} />

      <Stack spacing={3}>
        <Card variant="outlined">
          <CardContent>
            <Stack spacing={2}>
              <Box>
                <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
                  {t('historicalImport.setupTitle')}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  {t('historicalImport.setupDescription')} {t('historicalImport.setupHint')}
                </Typography>
              </Box>

              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ alignItems: { sm: 'flex-start' } }}>
                <TextField
                  type="date"
                  required
                  size="small"
                  label={t('historicalImport.cutoverDate')}
                  helperText={t('historicalImport.cutoverHelp')}
                  value={cutoverDate}
                  onChange={(event) => {
                    setCutoverDate(event.target.value)
                    setPreview(null)
                    setConfirmResult(null)
                  }}
                  slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: todayInKigaliIso() } }}
                  sx={{ width: { xs: '100%', sm: 230 } }}
                />
                {loansMode ? null : (
                <TextField
                  required
                  size="small"
                  label={t('historicalImport.openingFunds')}
                  helperText={
                    openingFunds.trim() && !fundsValid
                      ? t('historicalImport.openingFundsInvalid')
                      : t('historicalImport.openingFundsHelp')
                  }
                  error={Boolean(openingFunds.trim()) && !fundsValid}
                  value={openingFunds}
                  onChange={(event) => {
                    setOpeningFunds(event.target.value)
                    setPreview(null)
                    setConfirmResult(null)
                  }}
                  slotProps={{ htmlInput: { inputMode: 'decimal' } }}
                  sx={{ width: { xs: '100%', sm: 250 } }}
                />
                )}
              </Stack>

              <Box>
                <FormControlLabel
                  control={
                    <Switch
                      checked={loansMode}
                      onChange={(event) => {
                        setLoansMode(event.target.checked)
                        setPreview(null)
                        setConfirmResult(null)
                      }}
                    />
                  }
                  label={t('historicalImport.loansMode')}
                />
                <Typography variant="caption" color="text.secondary" component="div">
                  {t('historicalImport.loansModeHelp')}
                </Typography>
              </Box>

              <Stack
                direction={{ xs: 'column', sm: 'row' }}
                spacing={{ xs: 1.5, sm: 2 }}
                sx={{ alignItems: { sm: 'center' } }}
              >
                <Button
                  variant="outlined"
                  onClick={() => templateMutation.mutate()}
                  disabled={templateMutation.isPending}
                >
                  {t('historicalImport.downloadTemplate')}
                </Button>
                <Button
                  variant="outlined"
                  onClick={() => loanTemplateMutation.mutate()}
                  disabled={loanTemplateMutation.isPending}
                >
                  {t('historicalImport.downloadLoanTemplate')}
                </Button>
                <Button variant="outlined" component="label">
                  {t('historicalImport.chooseFile')}
                  <input
                    ref={fileInputRef}
                    hidden
                    type="file"
                    accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    onChange={(event) => {
                      const selected = event.target.files?.[0] ?? null
                      setFile(selected)
                      setPreview(null)
                      setConfirmResult(null)
                    }}
                  />
                </Button>
                <Typography
                  variant="body2"
                  color="text.secondary"
                  title={file?.name ?? undefined}
                  noWrap
                  sx={{ maxWidth: { xs: '100%', sm: 280 }, minWidth: 0 }}
                >
                  {file?.name ?? t('historicalImport.noFile')}
                </Typography>
                <Box sx={{ flexGrow: 1, display: { xs: 'none', sm: 'block' } }} />
                <Button
                  variant="contained"
                  disabled={!canPreview}
                  onClick={() => file && previewMutation.mutate(file)}
                >
                  {t('historicalImport.preview')}
                </Button>
              </Stack>
            </Stack>
          </CardContent>
        </Card>

        {previewMutation.isPending && <LoadingState />}
        {previewMutation.isError && (
          <ErrorState
            title={t('historicalImport.previewFailed')}
            message={localizedApiError(t, previewMutation.error, 'historicalImport.errorCodes')}
          />
        )}

        {preview && (
          <Stack spacing={2}>
            <Box>
              <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
                {t('historicalImport.previewTitle')}
              </Typography>
              <Typography variant="body2" color="text.secondary">
                {t('historicalImport.cutoverDate')}: {formatDate(previewCutover)}
              </Typography>
              <Typography variant="body2" color="text.secondary">
                {t('historicalImport.metrics.openingFunds')}:{' '}
                <Box component="span" sx={{ color: 'text.primary', fontWeight: 600 }}>
                  {formatMoney(migration?.openingAvailableFunds)}
                </Box>
              </Typography>
            </Box>
            <Box
              sx={{
                display: 'grid',
                gap: 2,
                gridTemplateColumns: { xs: '1fr', md: 'repeat(3, 1fr)' },
              }}
            >
              <SectionCard
                title={t('historicalImport.sections.members')}
                lines={
                  migration?.members
                    ? [
                        rowsLine(t, migration.members),
                        [
                          t('historicalImport.metrics.openingContributions'),
                          formatMoney(migration.members.totalOpeningContributions),
                        ],
                      ]
                    : []
                }
              />
              <SectionCard
                title={t('historicalImport.sections.shareCapital')}
                lines={
                  migration?.shareCapital
                    ? [
                        rowsLine(t, migration.shareCapital),
                        [
                          t('historicalImport.metrics.openingShares'),
                          String(migration.shareCapital.totalOpeningShares),
                        ],
                        [
                          t('historicalImport.metrics.openingShareCapital'),
                          formatMoney(migration.shareCapital.totalOpeningShareCapital),
                        ],
                      ]
                    : []
                }
              />
              <SectionCard
                title={t('historicalImport.sections.loans')}
                lines={
                  migration?.loans
                    ? [
                        rowsLine(t, migration.loans),
                        [t('historicalImport.metrics.endOfTerm'), String(migration.loans.endOfTermCount)],
                        [t('historicalImport.metrics.monthly'), String(migration.loans.monthlyCount)],
                        [
                          t('historicalImport.metrics.originalPrincipal'),
                          formatMoney(migration.loans.totalOriginalPrincipal),
                        ],
                        [
                          t('historicalImport.metrics.outstandingPrincipal'),
                          formatMoney(migration.loans.totalOutstandingPrincipal),
                        ],
                        [
                          t('historicalImport.metrics.amountPaid'),
                          formatMoney(migration.loans.totalAmountPaid),
                        ],
                      ]
                    : []
                }
              />
            </Box>

            {preview.revisionOfImportId ? (
              <Alert severity="warning" data-testid="historical-import-revision">
                {t('historicalImport.revision.banner', {
                  filename: preview.revisionOfFilename ?? '—',
                  date: preview.revisionOfConfirmedAt ? dayjs(preview.revisionOfConfirmedAt).format('YYYY-MM-DD HH:mm') : '—',
                })}
                {preview.unchangedLoans != null
                  ? ` ${t('historicalImport.revision.loans', {
                      kept: preview.unchangedLoans,
                      replaced: preview.replacedLoans ?? 0,
                    })}`
                  : ''}
              </Alert>
            ) : null}

            {preview.totalRows === 0 && !preview.unchangedLoans && (
              <Alert severity="info">{t('historicalImport.emptyWorkbook')}</Alert>
            )}

            {(preview.invalidRows > 0 || (preview.errors ?? []).length > 0) && (
              <Alert severity="error">{t('historicalImport.fixAndReupload')}</Alert>
            )}

            <ResponsiveTable
              columns={errorColumns}
              rows={preview.errors ?? []}
              emptyTitle={t('historicalImport.noErrors')}
              emptyDescription=""
              getRowId={(row) =>
                `${row.sheet ?? 'sheet'}-${row.rowNumber ?? 'row'}-${row.field ?? 'field'}-${row.message ?? ''}`
              }
            />

            <Box sx={{ display: 'flex', justifyContent: { xs: 'stretch', sm: 'flex-end' } }}>
              <Button
                variant="contained"
                fullWidth={false}
                sx={{ width: { xs: '100%', sm: 'auto' } }}
                disabled={!confirmEnabled || confirmMutation.isPending}
                onClick={() => setConfirmOpen(true)}
              >
                {t('historicalImport.confirm')}
              </Button>
            </Box>
          </Stack>
        )}

        {confirmResult?.membersLeftOutButKept && confirmResult.membersLeftOutButKept.length > 0 ? (
          <Alert severity="info">
            {t('historicalImport.revision.membersKept', { names: confirmResult.membersLeftOutButKept.join(', ') })}
          </Alert>
        ) : null}
        {confirmResult && (
          <Alert severity="success">
            <Typography variant="subtitle2">{t('historicalImport.confirmSummaryTitle')}</Typography>
            <Typography variant="body2">
              {t('historicalImport.confirmSummaryBody', {
                date: formatDate(confirmResult.cutoverDate),
                funds: formatMoney(confirmResult.openingAvailableFunds),
                members: confirmResult.membersImported,
                balances: confirmResult.memberOpeningBalancesImported,
                shareBalances: confirmResult.shareOpeningBalancesImported,
                loans: confirmResult.loansImported,
                installments: confirmResult.loanInstallmentsCreated,
              })}
            </Typography>
            <Typography variant="body2">
              {t('historicalImport.confirmTotals', {
                contributions: formatMoney(confirmResult.totalHistoricalContributions),
                shares: confirmResult.totalHistoricalShares,
                capital: formatMoney(confirmResult.totalHistoricalShareCapital),
                outstanding: formatMoney(confirmResult.totalOutstandingLoanPrincipal),
              })}
            </Typography>
          </Alert>
        )}

        <Box>
          <Typography variant="h6" gutterBottom>
            {t('historicalImport.historyTitle')}
          </Typography>
          {historyQuery.isLoading && <LoadingState />}
          {historyQuery.isError && (
            <ErrorState
              title={t('historicalImport.historyFailed')}
              message={localizedApiError(t, historyQuery.error, 'historicalImport.errorCodes')}
            />
          )}
          {historyQuery.data && (
            <ResponsiveTable
              columns={historyColumns}
              rows={historyQuery.data}
              emptyTitle={t('historicalImport.noHistory')}
              emptyDescription=""
              getRowId={(row) => row.id}
            />
          )}
        </Box>
      </Stack>

      {preview?.revisionOfImportId ? (
        <Dialog open={confirmOpen} onClose={() => !confirmMutation.isPending && setConfirmOpen(false)} fullWidth maxWidth="sm">
          <DialogTitle>{t('historicalImport.revision.validateTitle')}</DialogTitle>
          <DialogContent>
            <Stack spacing={2} sx={{ pt: 1 }}>
              <Alert severity="warning">
                {t('historicalImport.revision.validateWarning', { date: formatDate(previewCutover) })}
              </Alert>
              <TextField
                label={t('historicalImport.revision.reason')}
                value={revisionReason}
                onChange={(e) => setRevisionReason(e.target.value)}
                helperText={t('historicalImport.revision.reasonHint')}
                multiline
                minRows={2}
                slotProps={{ htmlInput: { maxLength: 500 } }}
              />
            </Stack>
          </DialogContent>
          <DialogActions sx={{ px: 3, pb: 2 }}>
            <Button onClick={() => setConfirmOpen(false)} disabled={confirmMutation.isPending}>
              {t('common.cancel')}
            </Button>
            <Button
              variant="contained"
              disabled={confirmMutation.isPending || revisionReason.trim().length < 10}
              onClick={() => confirmMutation.mutate()}
            >
              {t('historicalImport.confirm')}
            </Button>
          </DialogActions>
        </Dialog>
      ) : (
        <ConfirmDialog
          open={confirmOpen}
          title={t('historicalImport.confirmTitle')}
          message={t('historicalImport.confirmWarning', { date: formatDate(previewCutover) })}
          confirmLabel={t('historicalImport.confirm')}
          loading={confirmMutation.isPending}
          onConfirm={() => confirmMutation.mutate()}
          onCancel={() => setConfirmOpen(false)}
        />
      )}
      <ConfirmDialog
        open={Boolean(cancelTarget)}
        title={t('historicalImport.cancelTitle')}
        message={t('historicalImport.cancelMessage')}
        loading={cancelMutation.isPending}
        onConfirm={() => cancelTarget && cancelMutation.mutate(cancelTarget.id)}
        onCancel={() => setCancelTarget(null)}
      />
    </Box>
  )
}

function formatDate(value?: string | null): string {
  return value ? dayjs(value).format('D MMM YYYY') : '—'
}

function rowsLine(
  t: (key: string, options?: Record<string, unknown>) => string,
  section: { totalRows: number; validRows: number; invalidRows: number },
): [string, string] {
  return [
    t('historicalImport.metrics.rows'),
    t('historicalImport.sheetCounts', {
      total: section.totalRows,
      valid: section.validRows,
      invalid: section.invalidRows,
    }),
  ]
}

function SectionCard({ title, lines }: { title: string; lines: Array<[string, string]> }) {
  return (
    <Card variant="outlined">
      <CardContent>
        <Typography variant="subtitle1" gutterBottom>
          {title}
        </Typography>
        <Stack spacing={0.5}>
          {lines.map(([label, value]) => (
            <Box key={label} sx={{ display: 'flex', justifyContent: 'space-between', gap: 2 }}>
              <Typography variant="body2" color="text.secondary">
                {label}
              </Typography>
              <Typography variant="body2" sx={{ fontWeight: 600, textAlign: 'right' }}>
                {value}
              </Typography>
            </Box>
          ))}
        </Stack>
      </CardContent>
    </Card>
  )
}

/**
 * Two guided imports for an existing Saving Scheme: opening balances at a cutover date, and a multi-year history of
 * dated transactions (validated first, approved by a different officer, then posted). Each is explained on its own tab.
 */
export function HistoricalImportPage() {
  const { t } = useTranslation()
  const cooperativeId = useAppSelector((s) => s.auth.selectedCooperativeId)
  const [tab, setTab] = useState<'opening' | 'rotation' | 'transactions'>('opening')
  return (
    <Box>
      <Tabs
        value={tab}
        onChange={(_, value: 'opening' | 'rotation' | 'transactions') => setTab(value)}
        variant="scrollable"
        scrollButtons="auto"
        aria-label={t('historicalTransactions.tabsLabel')}
        sx={{ mb: 2 }}
      >
        <Tab value="opening" label={t('historicalTransactions.tabs.opening')} />
        <Tab value="rotation" label={t('historicalTransactions.tabs.rotation')} />
        <Tab value="transactions" label={t('historicalTransactions.tabs.transactions')} />
      </Tabs>
      {tab === 'opening' ? (
        <OpeningBalanceImport />
      ) : tab === 'rotation' ? (
        cooperativeId ? <OpeningWorkbookPanel cooperativeId={cooperativeId} /> : <Alert severity="info">{t('payments.selectSchemeTitle')}</Alert>
      ) : cooperativeId ? (
        <Box>
          <PageHeader title={t('historicalTransactions.title')} description={t('historicalTransactions.description')} />
          <HistoricalTransactionsPanel cooperativeId={cooperativeId} />
        </Box>
      ) : (
        <Alert severity="info">{t('payments.selectSchemeTitle')}</Alert>
      )}
    </Box>
  )
}
