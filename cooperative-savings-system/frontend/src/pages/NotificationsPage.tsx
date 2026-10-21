import DoneAllIcon from '@mui/icons-material/DoneAll'
import MarkEmailReadIcon from '@mui/icons-material/MarkEmailRead'
import {
  Box,
  Button,
  Chip,
  FormControlLabel,
  Stack,
  Switch,
  TablePagination,
  Typography,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import dayjs from 'dayjs'
import { useSnackbar } from 'notistack'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { isUnread, notificationTargetPath, unreadHighlightSx } from '@/features/notifications'
import { getErrorMessage } from '@/shared/api/client'
import {
  fetchNotifications,
  markAllNotificationsRead,
  markNotificationRead,
} from '@/shared/api/notifications'
import { EmptyState } from '@/shared/components/EmptyState'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { PageHeader } from '@/shared/components/PageHeader'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import type { AppNotification } from '@/shared/types/notification'

export function NotificationsPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { enqueueSnackbar } = useSnackbar()
  const [unreadOnly, setUnreadOnly] = useState(false)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)

  const query = useQuery({
    queryKey: ['notifications', unreadOnly, page, size],
    queryFn: () =>
      fetchNotifications({
        unreadOnly: unreadOnly || undefined,
        page,
        size,
        sort: 'createdAt,desc',
      }),
  })

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['notifications'] })
    void queryClient.invalidateQueries({ queryKey: ['notifications-unread-count'] })
  }

  const markOne = useMutation({
    mutationFn: (id: string) => markNotificationRead(id),
    onSuccess: () => {
      enqueueSnackbar(t('notifications.markReadSuccess'), { variant: 'success' })
      invalidate()
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const markAll = useMutation({
    mutationFn: () => markAllNotificationsRead(),
    onSuccess: () => {
      enqueueSnackbar(t('notifications.markAllReadSuccess'), { variant: 'success' })
      invalidate()
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const columns: TableColumn<AppNotification>[] = [
    {
      id: 'title',
      label: t('notifications.fields.title'),
      render: (row) => (
        <Box>
          <Typography variant="body2" sx={{ fontWeight: isUnread(row) ? 700 : 500 }}>
            {row.title}
          </Typography>
          {row.body ? (
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
              {row.body}
            </Typography>
          ) : null}
        </Box>
      ),
    },
    {
      id: 'type',
      label: t('notifications.fields.type'),
      render: (row) => (
        <Chip
          size="small"
          label={t(`notifications.types.${row.type}`, { defaultValue: row.type })}
        />
      ),
    },
    {
      id: 'status',
      label: t('notifications.fields.status'),
      hideOnMobile: true,
      render: (row) =>
        isUnread(row) ? (
          <Chip size="small" color="primary" label={t('notifications.unread')} />
        ) : (
          <Chip size="small" variant="outlined" label={t('notifications.read')} />
        ),
    },
    {
      id: 'createdAt',
      label: t('notifications.fields.createdAt'),
      render: (row) =>
        row.createdAt ? dayjs(row.createdAt).format('YYYY-MM-DD HH:mm') : '—',
    },
    {
      id: 'actions',
      label: t('common.actions'),
      render: (row) =>
        isUnread(row) ? (
          <Button
            size="small"
            startIcon={<MarkEmailReadIcon />}
            onClick={(e) => {
              e.stopPropagation()
              markOne.mutate(row.id)
            }}
            disabled={markOne.isPending}
            sx={{ minHeight: 40 }}
          >
            {t('notifications.markRead')}
          </Button>
        ) : (
          '—'
        ),
    },
  ]

  const rows = query.data?.content ?? []

  return (
    <Box>
      <PageHeader
        title={t('pages.notifications.title')}
        description={t('pages.notifications.description')}
        actions={
          <Button
            variant="outlined"
            startIcon={<DoneAllIcon />}
            onClick={() => markAll.mutate()}
            disabled={markAll.isPending || (query.data?.totalElements ?? 0) === 0}
            sx={{ minHeight: 44 }}
          >
            {t('notifications.markAllRead')}
          </Button>
        }
      />

      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={1.5}
        sx={{ mb: 2, alignItems: { sm: 'center' } }}
      >
        <FormControlLabel
          control={
            <Switch
              checked={unreadOnly}
              onChange={(_, checked) => {
                setUnreadOnly(checked)
                setPage(0)
              }}
            />
          }
          label={t('notifications.unreadOnly')}
        />
      </Stack>

      {query.isLoading ? <LoadingState variant="skeleton" rows={5} /> : null}

      {query.isError ? (
        <ErrorState
          message={getErrorMessage(query.error)}
          onRetry={() => void query.refetch()}
        />
      ) : null}

      {!query.isLoading && !query.isError ? (
        rows.length === 0 ? (
          <EmptyState
            title={t('notifications.emptyTitle')}
            description={t('notifications.emptyDescription')}
          />
        ) : (
          <>
            <ResponsiveTable
              columns={columns}
              rows={rows}
              getRowId={(row) => row.id}
              emptyTitle={t('notifications.emptyTitle')}
              emptyDescription={t('notifications.emptyDescription')}
              getRowSx={(row) => unreadHighlightSx(isUnread(row))}
              onRowClick={(row) => {
                const path = notificationTargetPath(row)
                if (isUnread(row)) {
                  markOne.mutate(row.id, {
                    onSettled: () => {
                      if (path) navigate(path)
                    },
                  })
                  return
                }
                if (path) navigate(path)
              }}
            />
            <TablePagination
              component="div"
              count={query.data?.totalElements ?? 0}
              page={page}
              onPageChange={(_, next) => setPage(next)}
              rowsPerPage={size}
              onRowsPerPageChange={(e) => {
                setSize(Number(e.target.value))
                setPage(0)
              }}
              rowsPerPageOptions={[5, 10, 25]}
            />
          </>
        )
      ) : null}
    </Box>
  )
}
