import AddIcon from '@mui/icons-material/Add'
import { Box, Button, Chip, Stack, Tooltip, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { SuperAdminFormDialog } from '@/features/superAdmin/SuperAdminFormDialog'
import { getErrorMessage } from '@/shared/api/client'
import {
  createSuperAdmin,
  fetchSuperAdmins,
  updateSuperAdminStatus,
  type SuperAdmin,
  SUPER_ADMINS_QUERY_KEY,
  type SuperAdminCreateRequest,
} from '@/shared/api/superAdmins'
import { ConfirmDialog } from '@/shared/components/ConfirmDialog'
import { EmptyState } from '@/shared/components/EmptyState'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { PageHeader } from '@/shared/components/PageHeader'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'

function formatDateTime(value?: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString()
}

export function SuperAdminsPage() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const { enqueueSnackbar } = useSnackbar()
  const [createOpen, setCreateOpen] = useState(false)
  const [createError, setCreateError] = useState<string | null>(null)
  const [pendingDeactivation, setPendingDeactivation] = useState<SuperAdmin | null>(null)

  const query = useQuery({ queryKey: SUPER_ADMINS_QUERY_KEY, queryFn: fetchSuperAdmins })

  const createMutation = useMutation({
    mutationFn: (payload: SuperAdminCreateRequest) => createSuperAdmin(payload),
    onSuccess: () => {
      setCreateOpen(false)
      setCreateError(null)
      enqueueSnackbar(t('superAdmin.admins.createSuccess'), { variant: 'success' })
      void queryClient.invalidateQueries({ queryKey: SUPER_ADMINS_QUERY_KEY })
    },
    onError: (error) => {
      setCreateError(getErrorMessage(error, t('errors.generic')))
    },
  })

  const statusMutation = useMutation({
    mutationFn: ({ id, status }: { id: string; status: 'ACTIVE' | 'INACTIVE' }) =>
      updateSuperAdminStatus(id, status),
    onSuccess: (_data, variables) => {
      setPendingDeactivation(null)
      enqueueSnackbar(
        t(
          variables.status === 'INACTIVE'
            ? 'superAdmin.admins.deactivateSuccess'
            : 'superAdmin.admins.reactivateSuccess',
        ),
        { variant: 'success' },
      )
      void queryClient.invalidateQueries({ queryKey: SUPER_ADMINS_QUERY_KEY })
    },
    onError: (error) => {
      setPendingDeactivation(null)
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const rows = query.data ?? []

  const columns: TableColumn<SuperAdmin>[] = useMemo(
    () => [
      {
        id: 'name',
        label: t('superAdmin.admins.columns.name'),
        render: (row) => (
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }} useFlexGap>
            <Typography component="span" variant="body2" sx={{ fontWeight: 600 }}>
              {row.fullName || `${row.firstName} ${row.lastName}`.trim()}
            </Typography>
            {row.currentUser ? (
              <Chip size="small" color="primary" variant="outlined" label={t('superAdmin.admins.you')} />
            ) : null}
          </Stack>
        ),
      },
      { id: 'username', label: t('superAdmin.admins.columns.username'), render: (row) => row.username },
      {
        id: 'email',
        label: t('superAdmin.admins.columns.email'),
        render: (row) => row.email,
        hideOnMobile: true,
      },
      {
        id: 'status',
        label: t('superAdmin.admins.columns.status'),
        render: (row) => (
          <Chip
            size="small"
            color={row.accountStatus === 'ACTIVE' ? 'success' : 'default'}
            label={
              row.accountStatus === 'ACTIVE'
                ? t('superAdmin.admins.statusActive')
                : t('superAdmin.admins.statusInactive')
            }
          />
        ),
      },
      {
        id: 'lastLogin',
        label: t('superAdmin.admins.columns.lastLogin'),
        render: (row) => formatDateTime(row.lastLoginAt),
        hideOnMobile: true,
      },
      {
        id: 'created',
        label: t('superAdmin.admins.columns.created'),
        render: (row) => formatDateTime(row.createdAt),
        hideOnMobile: true,
      },
      {
        id: 'actions',
        label: t('superAdmin.admins.columns.actions'),
        render: (row) => {
          if (row.accountStatus === 'ACTIVE') {
            return row.currentUser ? (
              <Tooltip title={t('superAdmin.admins.cannotDeactivateSelf')}>
                <span>
                  <Button size="small" color="error" disabled>
                    {t('superAdmin.admins.deactivate')}
                  </Button>
                </span>
              </Tooltip>
            ) : (
              <Button
                size="small"
                color="error"
                onClick={() => setPendingDeactivation(row)}
                disabled={statusMutation.isPending}
              >
                {t('superAdmin.admins.deactivate')}
              </Button>
            )
          }
          return (
            <Button
              size="small"
              onClick={() => statusMutation.mutate({ id: row.id, status: 'ACTIVE' })}
              disabled={statusMutation.isPending}
            >
              {t('superAdmin.admins.reactivate')}
            </Button>
          )
        },
      },
    ],
    [t, statusMutation],
  )

  return (
    <Box>
      <PageHeader
        title={t('superAdmin.admins.title')}
        description={t('superAdmin.admins.description')}
        actions={
          <Button
            variant="contained"
            startIcon={<AddIcon />}
            onClick={() => {
              setCreateError(null)
              setCreateOpen(true)
            }}
          >
            {t('superAdmin.admins.add')}
          </Button>
        }
      />

      {query.isLoading ? <LoadingState variant="skeleton" rows={4} /> : null}
      {query.isError ? (
        <ErrorState message={getErrorMessage(query.error)} onRetry={() => void query.refetch()} />
      ) : null}
      {query.isSuccess && rows.length === 0 ? (
        <EmptyState
          title={t('superAdmin.admins.emptyTitle')}
          description={t('superAdmin.admins.emptyDescription')}
          actionLabel={t('superAdmin.admins.add')}
          onAction={() => setCreateOpen(true)}
        />
      ) : null}
      {query.isSuccess && rows.length > 0 ? (
        <ResponsiveTable columns={columns} rows={rows} getRowId={(row) => row.id} />
      ) : null}

      <SuperAdminFormDialog
        open={createOpen}
        loading={createMutation.isPending}
        errorMessage={createError}
        onClose={() => setCreateOpen(false)}
        onSubmit={(payload) => {
          setCreateError(null)
          createMutation.mutate(payload)
        }}
      />

      <ConfirmDialog
        open={Boolean(pendingDeactivation)}
        title={t('superAdmin.admins.deactivateTitle')}
        message={t('superAdmin.admins.deactivateMessage', {
          name: pendingDeactivation?.fullName || pendingDeactivation?.username || '',
        })}
        confirmLabel={t('superAdmin.admins.deactivate')}
        loading={statusMutation.isPending}
        onConfirm={() => {
          if (pendingDeactivation) {
            statusMutation.mutate({ id: pendingDeactivation.id, status: 'INACTIVE' })
          }
        }}
        onCancel={() => setPendingDeactivation(null)}
      />
    </Box>
  )
}
