import AddIcon from '@mui/icons-material/Add'
import MoreVertIcon from '@mui/icons-material/MoreVert'
import {
  Box,
  Chip,
  IconButton,
  Menu,
  MenuItem,
  Stack,
  TablePagination,
  TextField,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { MemberFormDialog } from '@/features/members/MemberFormDialog'
import { MemberRemovalDialog } from '@/features/members/MemberRemovalDialog'
import { useAppSelector } from '@/app/store/hooks'
import { selectIsLeadership } from '@/app/store/authSlice'
import { createMember, fetchMembers } from '@/shared/api/members'
import { getErrorMessage } from '@/shared/api/client'
import { EmptyState } from '@/shared/components/EmptyState'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { PageHeader } from '@/shared/components/PageHeader'
import { SubscriptionAwareButton } from '@/shared/components/SubscriptionAwareButton'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import { ROUTES } from '@/shared/constants/routes'
import { useDebouncedValue } from '@/shared/hooks/useDebouncedValue'
import type { Member, MemberCreateRequest, MembershipStatus } from '@/shared/types/member'
import { MEMBERSHIP_STATUSES, memberDisplayName } from '@/shared/types/member'

function membershipColor(
  status: MembershipStatus,
): 'success' | 'default' | 'warning' | 'info' {
  switch (status) {
    case 'ACTIVE':
      return 'success'
    case 'SUSPENDED':
      return 'warning'
    case 'PENDING':
      return 'info'
    default:
      return 'default'
  }
}

export function MembersPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const queryClient = useQueryClient()
  const { enqueueSnackbar } = useSnackbar()
  const cooperativeId = useAppSelector((s) => s.auth.selectedCooperativeId)
  const currentUserId = useAppSelector((s) => s.auth.user?.id)
  const isLeadership = useAppSelector(selectIsLeadership)
  const [menu, setMenu] = useState<{ anchor: HTMLElement; member: Member } | null>(null)
  const [removalTarget, setRemovalTarget] = useState<Member | null>(null)

  const [search, setSearch] = useState('')
  const debouncedSearch = useDebouncedValue(search, 300)
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)
  const [createOpen, setCreateOpen] = useState(() => searchParams.get('action') === 'register')

  useEffect(() => {
    if (searchParams.get('action') === 'register') {
      setCreateOpen(true)
      const next = new URLSearchParams(searchParams)
      next.delete('action')
      setSearchParams(next, { replace: true })
    }
  }, [searchParams, setSearchParams])

  const query = useQuery({
    queryKey: ['members', cooperativeId, debouncedSearch, status, page, size],
    queryFn: () =>
      fetchMembers(cooperativeId!, {
        q: debouncedSearch || undefined,
        status: status || undefined,
        page,
        size,
      }),
    enabled: Boolean(cooperativeId),
  })

  const createMutation = useMutation({
    mutationFn: (payload: MemberCreateRequest) => createMember(cooperativeId!, payload),
    onSuccess: (created) => {
      enqueueSnackbar(t('members.createSuccess'), { variant: 'success' })
      setCreateOpen(false)
      void queryClient.invalidateQueries({ queryKey: ['members', cooperativeId] })
      navigate(ROUTES.memberDetail(created.userId), {
        state: created.temporaryPassword
          ? { temporaryPassword: created.temporaryPassword }
          : undefined,
      })
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const columns: TableColumn<Member>[] = useMemo(
    () => [
      {
        id: 'name',
        label: t('members.fields.fullName'),
        render: (row) => memberDisplayName(row),
      },
      {
        id: 'username',
        label: t('members.fields.username'),
        render: (row) => row.username,
      },
      {
        id: 'email',
        label: t('members.fields.email'),
        render: (row) => row.email,
        hideOnMobile: true,
      },
      {
        id: 'membershipStatus',
        label: t('members.fields.membershipStatus'),
        render: (row) => (
          <Chip
            size="small"
            color={membershipColor(row.membershipStatus)}
            label={t(`status.${row.membershipStatus}`)}
          />
        ),
      },
      {
        id: 'role',
        label: t('members.fields.roleInCooperative'),
        render: (row) =>
          t(`members.roles.${row.roleInCooperative}`, {
            defaultValue: row.roleInCooperative,
          }),
        hideOnMobile: true,
      },
      ...(isLeadership
        ? [
            {
              id: 'actions',
              label: '',
              render: (row: Member) =>
                row.userId === currentUserId ? null : (
                  <IconButton
                    size="small"
                    aria-label={t('members.actions.open', { name: memberDisplayName(row) })}
                    aria-haspopup="menu"
                    onClick={(event) => {
                      event.stopPropagation()
                      setMenu({ anchor: event.currentTarget, member: row })
                    }}
                  >
                    <MoreVertIcon />
                  </IconButton>
                ),
            },
          ]
        : []),
    ],
    [t, isLeadership, currentUserId],
  )

  if (!cooperativeId) {
    return (
      <Box>
        <PageHeader
          title={t('pages.members.title')}
          description={t('pages.members.description')}
        />
        <EmptyState
          title={t('members.selectCooperativeTitle')}
          description={t('members.selectCooperativeDescription')}
        />
      </Box>
    )
  }

  const rows = query.data?.content ?? []

  return (
    <Box>
      <PageHeader
        title={t('pages.members.title')}
        description={t('pages.members.description')}
        actions={
          <SubscriptionAwareButton variant="contained" startIcon={<AddIcon />} onClick={() => setCreateOpen(true)}>
            {t('members.register')}
          </SubscriptionAwareButton>
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
          label={t('members.fields.membershipStatus')}
          value={status}
          onChange={(e) => {
            setStatus(e.target.value)
            setPage(0)
          }}
          sx={{ minWidth: { sm: 180 } }}
        >
          <MenuItem value="">{t('common.all')}</MenuItem>
          {MEMBERSHIP_STATUSES.map((s) => (
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
          title={t('members.emptyTitle')}
          description={t('members.emptyDescription')}
          actionLabel={t('members.register')}
          onAction={() => setCreateOpen(true)}
        />
      ) : null}

      {query.isSuccess && rows.length > 0 ? (
        <>
          <ResponsiveTable
            columns={columns}
            rows={rows}
            getRowId={(row) => row.userId}
            onRowClick={(row) => navigate(ROUTES.memberDetail(row.userId))}
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

      <Menu anchorEl={menu?.anchor ?? null} open={Boolean(menu)} onClose={() => setMenu(null)}>
        <MenuItem
          onClick={() => {
            if (menu) navigate(ROUTES.memberDetail(menu.member.userId))
            setMenu(null)
          }}
        >
          {t('members.actions.view')}
        </MenuItem>
        <MenuItem
          onClick={() => {
            if (menu) setRemovalTarget(menu.member)
            setMenu(null)
          }}
        >
          {t('members.removal.action')}
        </MenuItem>
      </Menu>

      {removalTarget && cooperativeId ? (
        <MemberRemovalDialog
          cooperativeId={cooperativeId}
          userId={removalTarget.userId}
          memberName={memberDisplayName(removalTarget)}
          open
          onClose={() => setRemovalTarget(null)}
        />
      ) : null}

      <MemberFormDialog
        open={createOpen}
        mode="create"
        loading={createMutation.isPending}
        onClose={() => setCreateOpen(false)}
        onCreate={(payload) => createMutation.mutate(payload)}
      />
    </Box>
  )
}
