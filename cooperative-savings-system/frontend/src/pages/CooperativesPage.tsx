import AddIcon from '@mui/icons-material/Add'
import {
  Box,
  Button,
  Chip,
  MenuItem,
  Stack,
  TablePagination,
  TextField,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { CooperativeFormDialog } from '@/features/cooperatives/CooperativeFormDialog'
import { createCooperative, fetchCooperatives } from '@/shared/api/cooperatives'
import { getErrorMessage } from '@/shared/api/client'
import { ConfirmDialog } from '@/shared/components/ConfirmDialog'
import { EmptyState } from '@/shared/components/EmptyState'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { PageHeader } from '@/shared/components/PageHeader'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import { usePortalRoutes } from '@/shared/constants/portal'
import { useDebouncedValue } from '@/shared/hooks/useDebouncedValue'
import type { Cooperative, CooperativeCreateRequest, CooperativeStatus } from '@/shared/types/cooperative'
import { COOPERATIVE_STATUSES, isOnboardingIncomplete } from '@/shared/types/cooperative'
import { formatMoney } from '@/shared/utils/formatMoney'

function statusColor(
  status: CooperativeStatus,
): 'success' | 'default' | 'warning' | 'error' {
  switch (status) {
    case 'ACTIVE':
      return 'success'
    case 'SUSPENDED':
      return 'warning'
    case 'ARCHIVED':
      return 'error'
    default:
      return 'default'
  }
}

export function CooperativesPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { enqueueSnackbar } = useSnackbar()
  const routes = usePortalRoutes()

  const [search, setSearch] = useState('')
  const debouncedSearch = useDebouncedValue(search, 300)
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)
  const [createOpen, setCreateOpen] = useState(false)
  const [welcomeCoop, setWelcomeCoop] = useState<Cooperative | null>(null)

  const query = useQuery({
    queryKey: ['cooperatives', 'list', debouncedSearch, status, page, size],
    queryFn: () =>
      fetchCooperatives({
        q: debouncedSearch || undefined,
        status: status || undefined,
        page,
        size,
      }),
  })

  const createMutation = useMutation({
    mutationFn: (payload: CooperativeCreateRequest) => createCooperative(payload),
    onSuccess: (created) => {
      enqueueSnackbar(t('cooperatives.createSuccess', { name: created.name }), {
        variant: 'success',
      })
      setCreateOpen(false)
      setWelcomeCoop(created)
      void queryClient.invalidateQueries({ queryKey: ['cooperatives'] })
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const columns: TableColumn<Cooperative>[] = useMemo(
    () => [
      {
        id: 'name',
        label: t('cooperatives.fields.name'),
        render: (row) => row.name,
      },
      {
        id: 'status',
        label: t('cooperatives.fields.status'),
        render: (row) => (
          <Stack
            direction="row"
            spacing={0.75}
            useFlexGap
            sx={{ alignItems: 'center', flexWrap: 'wrap' }}
          >
            <Chip size="small" color={statusColor(row.status)} label={t(`status.${row.status}`)} />
            {isOnboardingIncomplete(row) ? (
              <Chip
                size="small"
                color="warning"
                label={t('cooperatives.onboarding.incompleteChip')}
              />
            ) : null}
          </Stack>
        ),
      },
      {
        id: 'currency',
        label: t('cooperatives.fields.currency'),
        render: (row) => row.currency,
        hideOnMobile: true,
      },
      {
        id: 'contribution',
        label: t('cooperatives.fields.monthlyContributionAmount'),
        render: (row) =>
          formatMoney(row.monthlyContributionAmount ?? 0, { currency: row.currency || 'RWF' }),
        hideOnMobile: true,
      },
      ...(routes.inPortal
        ? [
            {
              id: 'open',
              label: '',
              render: (row: Cooperative) => (
                <Button
                  size="small"
                  variant="outlined"
                  onClick={(event) => {
                    event.stopPropagation()
                    navigate(routes.workspace(row.id))
                  }}
                >
                  {t('superAdmin.support.open')}
                </Button>
              ),
            },
          ]
        : []),
      {
        id: 'registration',
        label: t('cooperatives.fields.registrationNumber'),
        render: (row) => row.registrationNumber || '—',
        hideOnMobile: true,
      },
    ],
    [t, routes, navigate],
  )

  const rows = query.data?.content ?? []

  return (
    <Box>
      <PageHeader
        title={t('pages.cooperatives.title')}
        description={t('pages.cooperatives.description')}
        actions={
          <Button variant="contained" startIcon={<AddIcon />} onClick={() => setCreateOpen(true)}>
            {t('cooperatives.create')}
          </Button>
        }
      />

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ mb: 2 }}>
        <TextField
          size="small"
          label={t('common.search')}
          value={search}
          onChange={(e) => {
            setSearch(e.target.value)
            setPage(0)
          }}
          fullWidth
        />
        <TextField
          size="small"
          select
          label={t('cooperatives.fields.status')}
          value={status}
          onChange={(e) => {
            setStatus(e.target.value)
            setPage(0)
          }}
          sx={{ minWidth: { sm: 180 } }}
        >
          <MenuItem value="">{t('common.all')}</MenuItem>
          {COOPERATIVE_STATUSES.map((s) => (
            <MenuItem key={s} value={s}>
              {t(`status.${s}`)}
            </MenuItem>
          ))}
        </TextField>
      </Stack>

      {query.isLoading ? <LoadingState variant="skeleton" rows={5} /> : null}

      {query.isError ? (
        <ErrorState
          message={getErrorMessage(query.error)}
          onRetry={() => void query.refetch()}
        />
      ) : null}

      {query.isSuccess && rows.length === 0 ? (
        <EmptyState
          title={t('cooperatives.emptyTitle')}
          description={t('cooperatives.emptyDescription')}
          actionLabel={t('cooperatives.create')}
          onAction={() => setCreateOpen(true)}
        />
      ) : null}

      {query.isSuccess && rows.length > 0 ? (
        <>
          <ResponsiveTable
            columns={columns}
            rows={rows}
            getRowId={(row) => row.id}
            onRowClick={(row) => navigate(routes.cooperativeDetail(row.id))}
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
      ) : null}

      <CooperativeFormDialog
        open={createOpen}
        mode="create"
        loading={createMutation.isPending}
        onClose={() => setCreateOpen(false)}
        onSubmit={(payload) => createMutation.mutate(payload)}
      />

      <ConfirmDialog
        open={Boolean(welcomeCoop)}
        title={t('cooperatives.welcomeDialogTitle')}
        message={t('cooperatives.welcomeDialogMessage', { name: welcomeCoop?.name ?? '' })}
        confirmLabel={t('cooperatives.welcomeDialogContinue')}
        cancelLabel={t('common.cancel')}
        onConfirm={() => {
          const id = welcomeCoop?.id
          setWelcomeCoop(null)
          if (id) navigate(routes.cooperativeDetail(id))
        }}
        onCancel={() => setWelcomeCoop(null)}
      />
    </Box>
  )
}
