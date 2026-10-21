import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Box,
  Button,
  Divider,
  Drawer,
  MenuItem,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TablePagination,
  TableRow,
  TextField,
  Typography,
} from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import dayjs from 'dayjs'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useAppSelector } from '@/app/store/hooks'
import {
  AUDITABLE_ACTIONS,
  AUDIT_ENTITY_TYPES,
  auditEntityLabel,
  auditUserLabel,
  buildAuditChangeRows,
  buildAuditDescription,
  buildAuditRecordLabel,
  displayAuditAction,
  displayAuditEntityType,
  formatAuditDateTime,
} from '@/features/auditLogs'
import { fetchAuditLog, fetchAuditLogs } from '@/shared/api/auditLogs'
import { getErrorMessage } from '@/shared/api/client'
import { DateRangeFields } from '@/shared/components/DateRangeFields'
import { EmptyState } from '@/shared/components/EmptyState'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { PageHeader } from '@/shared/components/PageHeader'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import type { AuditLog } from '@/shared/types/auditLog'
import { validateOptionalDateRange } from '@/shared/utils/filterValidation'

export function AuditLogsPage() {
  const { t } = useTranslation()
  const cooperativeId = useAppSelector((s) => s.auth.selectedCooperativeId)
  const [action, setAction] = useState('')
  const [entityType, setEntityType] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)
  const [selectedId, setSelectedId] = useState<string | null>(null)

  const dateIssue = validateOptionalDateRange(from, to)
  const filtersValid = !dateIssue

  const listQuery = useQuery({
    queryKey: ['audit-logs', cooperativeId, action, entityType, from, to, page, size],
    queryFn: () =>
      fetchAuditLogs(cooperativeId!, {
        action: action || undefined,
        entityType: entityType || undefined,
        from: from || undefined,
        to: to || undefined,
        page,
        size,
        sort: 'createdAt,desc',
      }),
    enabled: Boolean(cooperativeId) && filtersValid,
  })

  const detailQuery = useQuery({
    queryKey: ['audit-log', cooperativeId, selectedId],
    queryFn: () => fetchAuditLog(cooperativeId!, selectedId!),
    enabled: Boolean(cooperativeId && selectedId),
  })

  const columns: TableColumn<AuditLog>[] = useMemo(
    () => [
      {
        id: 'createdAt',
        label: t('auditLogs.fields.createdAt'),
        render: (row) =>
          row.createdAt ? dayjs(row.createdAt).format('YYYY-MM-DD HH:mm') : '—',
      },
      {
        id: 'action',
        label: t('auditLogs.fields.action'),
        render: (row) => displayAuditAction(row.action, t),
      },
      {
        id: 'entityType',
        label: t('auditLogs.fields.entityType'),
        render: (row) => displayAuditEntityType(row.entityType, t),
      },
      {
        id: 'entityId',
        label: t('auditLogs.fields.entityId'),
        hideOnMobile: true,
        render: (row) => auditEntityLabel(row),
      },
      {
        id: 'userId',
        label: t('auditLogs.fields.userId'),
        hideOnMobile: true,
        render: (row) => auditUserLabel(row),
      },
    ],
    [t],
  )

  if (!cooperativeId) {
    return (
      <Box>
        <PageHeader
          title={t('pages.auditLogs.title')}
          description={t('pages.auditLogs.description')}
        />
        <EmptyState
          title={t('auditLogs.selectCooperativeTitle')}
          description={t('auditLogs.selectCooperativeDescription')}
        />
      </Box>
    )
  }

  const rows = listQuery.data?.content ?? []
  const detail = detailQuery.data
  const changes = detail
    ? buildAuditChangeRows(detail.previousValues, detail.newValues, t, detail.entityType)
    : null

  return (
    <Box>
      <PageHeader
        title={t('pages.auditLogs.title')}
        description={t('pages.auditLogs.description')}
      />

      <Stack
        direction={{ xs: 'column', md: 'row' }}
        spacing={1.5}
        useFlexGap
        sx={{ mb: 2, flexWrap: 'wrap' }}
      >
        <TextField
          select
          size="small"
          label={t('auditLogs.fields.action')}
          value={action}
          onChange={(e) => {
            setAction(e.target.value)
            setPage(0)
          }}
          sx={{ minWidth: { xs: '100%', sm: 220 } }}
        >
          <MenuItem value="">{t('common.all')}</MenuItem>
          {AUDITABLE_ACTIONS.map((value) => (
            <MenuItem key={value} value={value}>
              {displayAuditAction(value, t)}
            </MenuItem>
          ))}
        </TextField>
        <TextField
          select
          size="small"
          label={t('auditLogs.fields.entityType')}
          value={entityType}
          onChange={(e) => {
            setEntityType(e.target.value)
            setPage(0)
          }}
          sx={{ minWidth: { xs: '100%', sm: 220 } }}
        >
          <MenuItem value="">{t('common.all')}</MenuItem>
          {AUDIT_ENTITY_TYPES.map((value) => (
            <MenuItem key={value} value={value}>
              {displayAuditEntityType(value, t)}
            </MenuItem>
          ))}
        </TextField>
        <DateRangeFields
          from={from}
          to={to}
          onFromChange={(value) => {
            setFrom(value)
            setPage(0)
          }}
          onToChange={(value) => {
            setTo(value)
            setPage(0)
          }}
          fromLabel={t('auditLogs.fields.from')}
          toLabel={t('auditLogs.fields.to')}
          issue={dateIssue}
        />
        <Button
          variant="outlined"
          onClick={() => {
            setAction('')
            setEntityType('')
            setFrom('')
            setTo('')
            setPage(0)
          }}
          sx={{ minHeight: 40 }}
        >
          {t('auditLogs.clearFilters')}
        </Button>
      </Stack>

      {listQuery.isLoading ? <LoadingState variant="skeleton" rows={5} /> : null}

      {filtersValid && listQuery.isError ? (
        <ErrorState
          message={getErrorMessage(listQuery.error)}
          onRetry={() => void listQuery.refetch()}
        />
      ) : null}

      {filtersValid && !listQuery.isLoading && !listQuery.isError ? (
        <>
          <ResponsiveTable
            columns={columns}
            rows={rows}
            getRowId={(row) => row.id}
            emptyTitle={t('auditLogs.emptyTitle')}
            emptyDescription={t('auditLogs.emptyDescription')}
            onRowClick={(row) => setSelectedId(row.id)}
          />
          <TablePagination
            component="div"
            count={listQuery.data?.totalElements ?? 0}
            page={page}
            onPageChange={(_, next) => setPage(next)}
            rowsPerPage={size}
            onRowsPerPageChange={(e) => {
              setSize(Number(e.target.value))
              setPage(0)
            }}
            rowsPerPageOptions={[5, 10, 25, 50]}
          />
        </>
      ) : null}

      <Drawer
        anchor="right"
        open={Boolean(selectedId)}
        onClose={() => setSelectedId(null)}
        slotProps={{
          paper: {
            sx: {
              width: { xs: '100%', sm: 460, md: 560 },
              p: 2.5,
            },
          },
        }}
      >
        <Typography variant="h6" gutterBottom>
          {t('auditLogs.detailTitle')}
        </Typography>
        <Button
          onClick={() => setSelectedId(null)}
          sx={{ mb: 2, minHeight: 40, alignSelf: 'flex-start' }}
        >
          {t('common.cancel')}
        </Button>
        <Divider sx={{ mb: 2 }} />

        {detailQuery.isLoading ? <LoadingState /> : null}
        {detailQuery.isError ? (
          <ErrorState
            message={getErrorMessage(detailQuery.error)}
            onRetry={() => void detailQuery.refetch()}
          />
        ) : null}

        {detail ? (
          <Stack spacing={2.5}>
            <Box>
              <Typography variant="subtitle2" sx={{ mb: 1.25, fontWeight: 700 }}>
                {t('auditLogs.summaryTitle')}
              </Typography>
              <Stack spacing={1.25}>
                <DetailRow
                  label={t('auditLogs.summary.action')}
                  value={displayAuditAction(detail.action, t)}
                />
                <DetailRow
                  label={t('auditLogs.summary.performedBy')}
                  value={auditUserLabel(detail)}
                />
                <DetailRow
                  label={t('auditLogs.summary.date')}
                  value={formatAuditDateTime(detail.createdAt)}
                />
                <DetailRow
                  label={t('auditLogs.summary.record')}
                  value={buildAuditRecordLabel(detail, t)}
                />
                <DetailRow
                  label={t('auditLogs.summary.description')}
                  value={buildAuditDescription(detail, t)}
                />
              </Stack>
            </Box>

            <Box>
              <Typography variant="subtitle2" sx={{ mb: 1.25, fontWeight: 700 }}>
                {t('auditLogs.changesTitle')}
              </Typography>
              {changes?.empty ? (
                <Typography variant="body2" color="text.secondary">
                  {t('auditLogs.noChanges')}
                </Typography>
              ) : (
                <AuditChangesTable
                  rows={changes?.rows ?? []}
                  showPrevious={Boolean(changes?.hasPrevious)}
                  showNew={Boolean(changes?.hasNew)}
                  fieldLabel={t('auditLogs.changes.field')}
                  previousLabel={t('auditLogs.changes.previous')}
                  newLabel={t('auditLogs.changes.new')}
                />
              )}
            </Box>

            {detail.ipAddress ? (
              <Accordion
                disableGutters
                elevation={0}
                defaultExpanded={false}
                sx={{
                  border: '1px solid',
                  borderColor: 'divider',
                  borderRadius: 1,
                  '&:before': { display: 'none' },
                }}
              >
                <AccordionSummary expandIcon={<ExpandMoreIcon />}>
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>
                    {t('auditLogs.technicalDetails')}
                  </Typography>
                </AccordionSummary>
                <AccordionDetails>
                  <DetailRow label={t('auditLogs.fields.ipAddress')} value={detail.ipAddress} />
                </AccordionDetails>
              </Accordion>
            ) : null}
          </Stack>
        ) : null}
      </Drawer>
    </Box>
  )
}

function DetailRow({ label, value }: { label: string; value: string }) {
  return (
    <Box>
      <Typography variant="caption" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="body2">{value}</Typography>
    </Box>
  )
}

function AuditChangesTable({
  rows,
  showPrevious,
  showNew,
  fieldLabel,
  previousLabel,
  newLabel,
}: {
  rows: Array<{ key: string; label: string; previous: string; next: string }>
  showPrevious: boolean
  showNew: boolean
  fieldLabel: string
  previousLabel: string
  newLabel: string
}) {
  const previousVisible = showPrevious || !showNew
  const newVisible = showNew || !showPrevious
  return (
    <Table size="small" sx={{ '& td, & th': { px: 1, py: 1, verticalAlign: 'top' } }}>
      <TableHead>
        <TableRow>
          <TableCell sx={{ fontWeight: 700 }}>{fieldLabel}</TableCell>
          {previousVisible ? (
            <TableCell sx={{ fontWeight: 700 }}>{previousLabel}</TableCell>
          ) : null}
          {newVisible ? <TableCell sx={{ fontWeight: 700 }}>{newLabel}</TableCell> : null}
        </TableRow>
      </TableHead>
      <TableBody>
        {rows.map((row) => (
          <TableRow key={row.key}>
            <TableCell>{row.label}</TableCell>
            {previousVisible ? (
              <TableCell sx={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
                {row.previous}
              </TableCell>
            ) : null}
            {newVisible ? (
              <TableCell sx={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
                {row.next}
              </TableCell>
            ) : null}
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}
