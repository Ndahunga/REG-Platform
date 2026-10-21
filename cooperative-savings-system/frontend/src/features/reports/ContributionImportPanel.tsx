import {
  Alert,
  Box,
  Button,
  Chip,
  MenuItem,
  Stack,
  TablePagination,
  TextField,
  Typography,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import dayjs from 'dayjs'
import { useSnackbar } from 'notistack'
import { useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  cancelContributionImport,
  confirmContributionImport,
  downloadContributionImportTemplate,
  fetchContributionImportHistory,
  previewContributionImport,
} from '@/shared/api/contributionImport'
import { getErrorMessage } from '@/shared/api/client'
import { ConfirmDialog } from '@/shared/components/ConfirmDialog'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import type {
  ContributionImportPreview,
  ContributionImportRow,
  ContributionImportSummary,
} from '@/shared/types/contributionImport'
import { formatMoney } from '@/shared/utils/formatMoney'
import {
  canCancelImport,
  canConfirmImport,
  importRowValidityColor,
  importRowValidityLabelKey,
  importStatusColor,
  importStatusLabelKey,
} from './reportHelpers'

interface ContributionImportPanelProps {
  cooperativeId: string
  isAdmin: boolean
}

export function ContributionImportPanel({
  cooperativeId,
  isAdmin,
}: ContributionImportPanelProps) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const queryClient = useQueryClient()
  const fileInputRef = useRef<HTMLInputElement | null>(null)

  const [year, setYear] = useState(String(dayjs().year()))
  const [month, setMonth] = useState(String(dayjs().month() + 1))
  const [preview, setPreview] = useState<ContributionImportPreview | null>(null)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [cancelOpen, setCancelOpen] = useState(false)
  const [historyPage, setHistoryPage] = useState(0)
  const [historySize, setHistorySize] = useState(10)

  const yearOptions = useMemo(() => {
    const current = dayjs().year()
    return Array.from({ length: 8 }, (_, i) => current - i)
  }, [])

  const historyQuery = useQuery({
    queryKey: ['contribution-imports', cooperativeId, historyPage, historySize],
    queryFn: () => fetchContributionImportHistory(cooperativeId, historyPage, historySize),
    enabled: Boolean(cooperativeId) && isAdmin,
  })

  const templateMutation = useMutation({
    mutationFn: () => downloadContributionImportTemplate(cooperativeId),
    onSuccess: ({ filename }) => {
      enqueueSnackbar(t('reports.import.templateSuccess', { filename }), {
        variant: 'success',
      })
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const previewMutation = useMutation({
    mutationFn: (file: File) =>
      previewContributionImport(cooperativeId, file, Number(year), Number(month)),
    onSuccess: (data) => {
      setPreview(data)
      enqueueSnackbar(t('reports.import.previewSuccess'), { variant: 'success' })
      void queryClient.invalidateQueries({ queryKey: ['contribution-imports'] })
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const confirmMutation = useMutation({
    mutationFn: () => confirmContributionImport(cooperativeId, preview!.importId),
    onSuccess: () => {
      enqueueSnackbar(t('reports.import.confirmSuccess'), { variant: 'success' })
      setConfirmOpen(false)
      setPreview(null)
      if (fileInputRef.current) fileInputRef.current.value = ''
      void queryClient.invalidateQueries({ queryKey: ['contribution-imports'] })
      void queryClient.invalidateQueries({ queryKey: ['contributions'] })
      void queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const cancelMutation = useMutation({
    mutationFn: () => cancelContributionImport(cooperativeId, preview!.importId),
    onSuccess: () => {
      enqueueSnackbar(t('reports.import.cancelSuccess'), { variant: 'success' })
      setCancelOpen(false)
      setPreview(null)
      if (fileInputRef.current) fileInputRef.current.value = ''
      void queryClient.invalidateQueries({ queryKey: ['contribution-imports'] })
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const previewColumns: TableColumn<ContributionImportRow>[] = useMemo(
    () => [
      {
        id: 'row',
        label: t('reports.import.fields.rowNumber'),
        render: (row) => row.rowNumber,
      },
      {
        id: 'username',
        label: t('reports.import.fields.username'),
        render: (row) => row.username || '—',
      },
      {
        id: 'member',
        label: t('reports.import.fields.memberName'),
        render: (row) => row.memberName || '—',
      },
      {
        id: 'amount',
        label: t('reports.import.fields.amount'),
        render: (row) => (row.amount != null && row.amount !== '' ? formatMoney(row.amount) : '—'),
      },
      {
        id: 'paymentDate',
        label: t('reports.import.fields.paymentDate'),
        render: (row) => row.paymentDate || '—',
        hideOnMobile: true,
      },
      {
        id: 'reference',
        label: t('reports.import.fields.reference'),
        render: (row) => row.reference || '—',
        hideOnMobile: true,
      },
      {
        id: 'validity',
        label: t('reports.import.fields.validity'),
        render: (row) => (
          <Chip
            size="small"
            color={importRowValidityColor(row.valid)}
            label={t(importRowValidityLabelKey(row.valid))}
          />
        ),
      },
      {
        id: 'errors',
        label: t('reports.import.fields.errors'),
        render: (row) =>
          row.valid ? '—' : (row.errors ?? []).join('; ') || t('reports.import.unknownError'),
      },
    ],
    [t],
  )

  const historyColumns: TableColumn<ContributionImportSummary>[] = useMemo(
    () => [
      {
        id: 'period',
        label: t('reports.import.fields.period'),
        render: (row) => `${row.year}-${String(row.month).padStart(2, '0')}`,
      },
      {
        id: 'file',
        label: t('reports.import.fields.filename'),
        render: (row) => row.originalFilename || '—',
      },
      {
        id: 'status',
        label: t('reports.import.fields.status'),
        render: (row) => (
          <Chip
            size="small"
            color={importStatusColor(String(row.status))}
            label={t(importStatusLabelKey(String(row.status)), {
              defaultValue: String(row.status),
            })}
          />
        ),
      },
      {
        id: 'counts',
        label: t('reports.import.fields.counts'),
        render: (row) =>
          t('reports.import.countsSummary', {
            valid: row.validRows ?? row.validCount ?? 0,
            invalid: row.invalidRows ?? row.invalidCount ?? 0,
            total: row.totalRows ?? 0,
          }),
        hideOnMobile: true,
      },
      {
        id: 'created',
        label: t('reports.import.fields.createdAt'),
        render: (row) =>
          row.createdAt ? dayjs(row.createdAt).format('YYYY-MM-DD HH:mm') : '—',
        hideOnMobile: true,
      },
    ],
    [t],
  )

  if (!isAdmin) {
    return (
      <Alert severity="warning">{t('reports.import.adminOnly')}</Alert>
    )
  }

  const confirmEnabled = canConfirmImport(
    preview?.status,
    preview?.validCount ?? 0,
    isAdmin,
  )
  const cancelEnabled = canCancelImport(preview?.status, isAdmin) && Boolean(preview?.importId)

  return (
    <Box>
      <Typography variant="h6" gutterBottom>
        {t('reports.import.title')}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2, maxWidth: 720 }}>
        {t('reports.import.description')}
      </Typography>

      <Stack
        spacing={2}
        sx={{
          maxWidth: 640,
          mb: 3,
          p: { xs: 2, sm: 2.5 },
          border: '1px solid',
          borderColor: 'divider',
          borderRadius: 1,
        }}
      >
        <Typography variant="subtitle1">{t('reports.import.step1')}</Typography>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
          <TextField
            select
            label={t('reports.export.year')}
            value={year}
            onChange={(e) => setYear(e.target.value)}
            fullWidth
          >
            {yearOptions.map((y) => (
              <MenuItem key={y} value={String(y)}>
                {y}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            select
            label={t('reports.export.month')}
            value={month}
            onChange={(e) => setMonth(e.target.value)}
            fullWidth
          >
            {Array.from({ length: 12 }, (_, i) => i + 1).map((m) => (
              <MenuItem key={m} value={String(m)}>
                {dayjs().month(m - 1).format('MMMM')}
              </MenuItem>
            ))}
          </TextField>
        </Stack>
        <Box>
          <Button
            variant="outlined"
            onClick={() => templateMutation.mutate()}
            disabled={templateMutation.isPending}
          >
            {templateMutation.isPending
              ? t('reports.import.downloadingTemplate')
              : t('reports.import.downloadTemplate')}
          </Button>
        </Box>

        <Typography variant="subtitle1" sx={{ pt: 1 }}>
          {t('reports.import.step2')}
        </Typography>
        <Button variant="contained" component="label" disabled={previewMutation.isPending}>
          {previewMutation.isPending
            ? t('reports.import.uploading')
            : t('reports.import.uploadFile')}
          <input
            ref={fileInputRef}
            hidden
            type="file"
            accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            onChange={(event) => {
              const file = event.target.files?.[0]
              if (file) previewMutation.mutate(file)
            }}
          />
        </Button>
      </Stack>

      {preview ? (
        <Box sx={{ mb: 4 }}>
          <Alert
            severity={preview.invalidCount > 0 ? 'warning' : 'success'}
            sx={{ mb: 2 }}
          >
            {t('reports.import.previewSummary', {
              valid: preview.validCount,
              invalid: preview.invalidCount,
              total: preview.totalRows ?? preview.rows.length,
              year: preview.year ?? year,
              month: preview.month ?? month,
            })}
          </Alert>

          <Stack direction="row" spacing={1} useFlexGap sx={{ mb: 2, flexWrap: 'wrap' }}>
            <Button
              variant="contained"
              disabled={!confirmEnabled || confirmMutation.isPending}
              onClick={() => setConfirmOpen(true)}
            >
              {t('reports.import.confirm')}
            </Button>
            <Button
              variant="outlined"
              color="inherit"
              disabled={!cancelEnabled || cancelMutation.isPending}
              onClick={() => setCancelOpen(true)}
            >
              {t('reports.import.cancel')}
            </Button>
          </Stack>

          <ResponsiveTable
            columns={previewColumns}
            rows={preview.rows}
            getRowId={(row) => String(row.rowNumber)}
            emptyTitle={t('reports.import.emptyPreviewTitle')}
            emptyDescription={t('reports.import.emptyPreviewDescription')}
          />
        </Box>
      ) : null}

      <Typography variant="h6" gutterBottom>
        {t('reports.import.historyTitle')}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {t('reports.import.historyDescription')}
      </Typography>

      {historyQuery.isLoading ? <LoadingState /> : null}
      {historyQuery.isError ? (
        <ErrorState
          title={t('common.errorTitle')}
          message={getErrorMessage(historyQuery.error, t('errors.generic'))}
          onRetry={() => void historyQuery.refetch()}
        />
      ) : null}
      {historyQuery.data ? (
        <>
          <ResponsiveTable
            columns={historyColumns}
            rows={historyQuery.data.content}
            getRowId={(row) => row.id}
            emptyTitle={t('reports.import.emptyHistoryTitle')}
            emptyDescription={t('reports.import.emptyHistoryDescription')}
          />
          <TablePagination
            component="div"
            count={historyQuery.data.totalElements}
            page={historyPage}
            onPageChange={(_, next) => setHistoryPage(next)}
            rowsPerPage={historySize}
            onRowsPerPageChange={(event) => {
              setHistorySize(Number(event.target.value))
              setHistoryPage(0)
            }}
            rowsPerPageOptions={[5, 10, 20]}
          />
        </>
      ) : null}

      <ConfirmDialog
        open={confirmOpen}
        title={t('reports.import.confirmTitle')}
        message={t('reports.import.confirmMessage', {
          count: preview?.validCount ?? 0,
          year: preview?.year ?? year,
          month: preview?.month ?? month,
        })}
        loading={confirmMutation.isPending}
        onConfirm={() => confirmMutation.mutate()}
        onCancel={() => setConfirmOpen(false)}
      />

      <ConfirmDialog
        open={cancelOpen}
        title={t('reports.import.cancelTitle')}
        message={t('reports.import.cancelMessage')}
        loading={cancelMutation.isPending}
        onConfirm={() => cancelMutation.mutate()}
        onCancel={() => setCancelOpen(false)}
      />
    </Box>
  )
}
