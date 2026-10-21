import ArrowBackIcon from '@mui/icons-material/ArrowBack'
import EditIcon from '@mui/icons-material/Edit'
import {
  Alert,
  Box,
  Button,
  Chip,
  Paper,
  Stack,
  Typography,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link as RouterLink, useLocation, useNavigate, useParams } from 'react-router-dom'
import { contributionStatusColor } from '@/features/contributions'
import { fineStatusColor } from '@/features/fines'
import { loanStatusColor } from '@/features/loans'
import { formatPayoutPercentage, payoutStatusColor } from '@/features/payouts'
import { socialStatusColor } from '@/features/socialFund'
import { MemberFormDialog } from '@/features/members/MemberFormDialog'
import { BuySharesDialog, SharePurchaseHistoryPanel } from '@/features/shares'
import { selectCanRecordContributions, selectIsLeadership } from '@/app/store/authSlice'
import { useAppSelector } from '@/app/store/hooks'
import { fetchContributions } from '@/shared/api/contributions'
import { fetchFines } from '@/shared/api/fines'
import { fetchLoans } from '@/shared/api/loans'
import {
  fetchMember,
  fetchMemberFinancialSummary,
  updateMember,
  updateMemberStatus,
  uploadMemberProfileImage,
} from '@/shared/api/members'
import { fetchSocialContributions } from '@/shared/api/socialFund'
import { getErrorMessage } from '@/shared/api/client'
import { ConfirmDialog } from '@/shared/components/ConfirmDialog'
import { EmptyState } from '@/shared/components/EmptyState'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { MemberRemovalDialog } from '@/features/members/MemberRemovalDialog'
import { PageHeader } from '@/shared/components/PageHeader'
import { RoleDutiesNote } from '@/shared/components/RoleDutiesNote'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import { ROUTES } from '@/shared/constants/routes'
import type { Contribution } from '@/shared/types/contribution'
import type { Fine } from '@/shared/types/fine'
import { mapFine } from '@/shared/types/fine'
import type { Loan } from '@/shared/types/loan'
import { mapLoan } from '@/shared/types/loan'
import type { PayoutLine } from '@/shared/types/payout'
import { mapPayoutLine } from '@/shared/types/payout'
import type { SocialContribution } from '@/shared/types/socialFund'
import { mapSocialContribution } from '@/shared/types/socialFund'
import type { MembershipStatus, MemberUpdateRequest } from '@/shared/types/member'
import { MEMBERSHIP_STATUSES, memberDisplayName } from '@/shared/types/member'
import { formatMoney } from '@/shared/utils/formatMoney'

function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <Box>
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
        {label}
      </Typography>
      <Typography variant="body1" sx={{ fontWeight: 500, wordBreak: 'break-word' }}>
        {value || '—'}
      </Typography>
    </Box>
  )
}

function MemberFinancialSummaryPanel({
  cooperativeId,
  userId,
}: {
  cooperativeId: string
  userId: string
}) {
  const { t } = useTranslation()
  const query = useQuery({
    queryKey: ['members', 'financial-summary', cooperativeId, userId],
    queryFn: () => fetchMemberFinancialSummary(cooperativeId, userId),
    enabled: Boolean(cooperativeId && userId),
  })

  if (query.isLoading) {
    return (
      <Paper elevation={0} sx={{ p: { xs: 2.5, md: 3 }, border: '1px solid', borderColor: 'divider' }}>
        <LoadingState variant="skeleton" rows={4} />
      </Paper>
    )
  }

  if (query.isError) {
    return (
      <Paper elevation={0} sx={{ p: { xs: 2.5, md: 3 }, border: '1px solid', borderColor: 'divider' }}>
        <ErrorState
          message={getErrorMessage(query.error)}
          onRetry={() => void query.refetch()}
        />
      </Paper>
    )
  }

  const s = query.data
  if (!s) return null
  const currency = s.currency || 'RWF'
  const money = (v: string | number | null | undefined) =>
    formatMoney(v ?? 0, { currency })

  return (
    <Paper elevation={0} sx={{ p: { xs: 2.5, md: 3 }, border: '1px solid', borderColor: 'divider' }}>
      <Typography variant="h6" gutterBottom>
        {t('members.financialSummary.title')}
      </Typography>
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr', md: '1fr 1fr 1fr' },
          gap: 2,
        }}
      >
        <InfoRow label={t('members.financialSummary.regular')} value={money(s.regularContributions)} />
        <InfoRow label={t('members.financialSummary.special')} value={money(s.specialContributions)} />
        <InfoRow label={t('members.financialSummary.actual')} value={money(s.actualContributions)} />
        <InfoRow label={t('members.financialSummary.expected')} value={money(s.expectedContributions)} />
        <InfoRow
          label={t('members.financialSummary.outstandingContributions')}
          value={money(s.outstandingContributions)}
        />
        <InfoRow
          label={t('members.financialSummary.contributionPercentage')}
          value={
            s.contributionPercentage != null && s.contributionPercentage !== ''
              ? `${Number(s.contributionPercentage).toFixed(2)}%`
              : '—'
          }
        />
        <InfoRow label={t('members.financialSummary.loansReceived')} value={money(s.loansReceived)} />
        <InfoRow
          label={t('members.financialSummary.outstandingPrincipal')}
          value={money(s.outstandingLoanPrincipal)}
        />
        <InfoRow
          label={t('members.financialSummary.outstandingInterest')}
          value={money(s.outstandingLoanInterest)}
        />
        <InfoRow
          label={t('members.financialSummary.loanRepayments')}
          value={money(s.totalLoanRepayments)}
        />
        <InfoRow label={t('members.financialSummary.totalFines')} value={money(s.totalFines)} />
        <InfoRow label={t('members.financialSummary.unpaidFines')} value={money(s.unpaidFines)} />
        <InfoRow
          label={t('members.financialSummary.approvedFinePayments')}
          value={money(s.approvedFinePayments)}
        />
        <InfoRow
          label={t('members.financialSummary.socialContributions')}
          value={money(s.socialContributions)}
        />
        <InfoRow
          label={t('members.financialSummary.payoutTotal')}
          value={money(s.recentPayoutTotal)}
        />
        <InfoRow
          label={t('members.financialSummary.sharesHeld')}
          value={s.sharesHeld != null ? String(s.sharesHeld) : '—'}
        />
        <InfoRow
          label={t('members.financialSummary.currentShareValue')}
          value={money(s.currentShareValue)}
        />
        <InfoRow
          label={t('members.financialSummary.totalShareValue')}
          value={money(s.totalShareValue)}
        />
      </Box>
    </Paper>
  )
}

function MemberPayoutsSection({
  embeddedPayouts,
}: {
  cooperativeId: string
  memberUserId: string
  embeddedPayouts?: unknown[] | null
}) {
  const { t } = useTranslation()
  const navigate = useNavigate()

  const rows = useMemo(() => {
    if (!Array.isArray(embeddedPayouts)) return []
    try {
      return embeddedPayouts.map((item) => mapPayoutLine(item as PayoutLine))
    } catch {
      return []
    }
  }, [embeddedPayouts])

  const columns: TableColumn<PayoutLine>[] = useMemo(
    () => [
      {
        id: 'period',
        label: t('payouts.fields.period'),
        render: (row) =>
          row.periodFrom && row.periodTo
            ? `${row.periodFrom} → ${row.periodTo}`
            : row.runName || '—',
      },
      {
        id: 'eligible',
        label: t('payouts.fields.eligibleAmount'),
        render: (row) =>
          formatMoney(row.eligibleContributionAmount, { currency: row.currency }),
      },
      {
        id: 'percentage',
        label: t('payouts.fields.percentage'),
        render: (row) => formatPayoutPercentage(row.percentage),
        hideOnMobile: true,
      },
      {
        id: 'payout',
        label: t('payouts.fields.payoutAmount'),
        render: (row) => formatMoney(row.payoutAmount, { currency: row.currency }),
      },
      {
        id: 'status',
        label: t('payouts.fields.status'),
        render: (row) => (
          <Chip
            size="small"
            color={payoutStatusColor(String(row.runStatus || row.status))}
            label={t(`payouts.status.${row.runStatus || row.status}`, {
              defaultValue: String(row.runStatus || row.status),
            })}
          />
        ),
      },
    ],
    [t],
  )

  return (
    <Box>
      <Typography variant="h6" gutterBottom>
        {t('nav.payouts')}
      </Typography>
      <ResponsiveTable
        columns={columns}
        rows={rows}
        getRowId={(row) => row.id}
        emptyTitle={t('payouts.myEmptyTitle')}
        emptyDescription={t('payouts.memberHistoryEmptyDescription')}
        onRowClick={(row) => {
          const runId = row.payoutRunId || row.runId
          if (runId) navigate(ROUTES.payoutDetail(runId))
        }}
      />
    </Box>
  )
}

function MemberContributionsSection({
  cooperativeId,
  memberUserId,
}: {
  cooperativeId: string
  memberUserId: string
}) {
  const { t } = useTranslation()
  const query = useQuery({
    queryKey: ['contributions', 'member', cooperativeId, memberUserId],
    queryFn: () =>
      fetchContributions(cooperativeId, {
        memberUserId,
        page: 0,
        size: 20,
        sort: 'year,desc',
      }),
    enabled: Boolean(cooperativeId && memberUserId),
  })

  const columns: TableColumn<Contribution>[] = useMemo(
    () => [
      {
        id: 'period',
        label: t('contributions.fields.period'),
        render: (row) => `${row.year}-${String(row.month).padStart(2, '0')}`,
      },
      {
        id: 'paid',
        label: t('contributions.fields.paid'),
        render: (row) => formatMoney(row.paidAmount),
      },
      {
        id: 'outstanding',
        label: t('contributions.fields.outstanding'),
        render: (row) => formatMoney(row.outstandingAmount),
        hideOnMobile: true,
      },
      {
        id: 'status',
        label: t('contributions.fields.status'),
        render: (row) => (
          <Chip
            size="small"
            color={contributionStatusColor(String(row.status))}
            label={t(`contributions.status.${row.status}`, { defaultValue: row.status })}
          />
        ),
      },
    ],
    [t],
  )

  return (
    <Box>
      <Typography variant="h6" gutterBottom>
        {t('nav.contributions')}
      </Typography>
      {query.isLoading ? <LoadingState variant="skeleton" rows={3} /> : null}
      {query.isError ? (
        <ErrorState
          message={getErrorMessage(query.error)}
          onRetry={() => void query.refetch()}
        />
      ) : null}
      {!query.isLoading && !query.isError ? (
        <ResponsiveTable
          columns={columns}
          rows={query.data?.content ?? []}
          getRowId={(row) => row.id}
          emptyTitle={t('contributions.historyEmptyTitle')}
          emptyDescription={t('contributions.memberHistoryEmptyDescription')}
        />
      ) : null}
    </Box>
  )
}

function MemberLoansSection({
  cooperativeId,
  memberUserId,
  embeddedLoans,
}: {
  cooperativeId: string
  memberUserId: string
  embeddedLoans?: unknown[] | null
}) {
  const { t } = useTranslation()
  const navigate = useNavigate()

  const embedded = useMemo(() => {
    if (!Array.isArray(embeddedLoans)) return undefined
    try {
      return embeddedLoans.map((item) => mapLoan(item as Loan))
    } catch {
      return undefined
    }
  }, [embeddedLoans])

  const query = useQuery({
    queryKey: ['loans', 'member', cooperativeId, memberUserId],
    queryFn: () =>
      fetchLoans(cooperativeId, {
        memberUserId,
        page: 0,
        size: 20,
        sort: 'createdAt,desc',
      }),
    enabled: Boolean(cooperativeId && memberUserId) && embedded === undefined,
  })

  const rows = embedded ?? query.data?.content ?? []
  const loading = embedded === undefined && query.isLoading
  const error = embedded === undefined && query.isError

  const columns: TableColumn<Loan>[] = useMemo(
    () => [
      {
        id: 'amount',
        label: t('loans.fields.amount'),
        render: (row) =>
          formatMoney(row.principalAmount ?? row.approvedAmount ?? row.requestedAmount),
      },
      {
        id: 'outstanding',
        label: t('loans.fields.outstanding'),
        render: (row) =>
          formatMoney(
            (Number(row.outstandingPrincipal) || 0) + (Number(row.outstandingInterest) || 0),
          ),
        hideOnMobile: true,
      },
      {
        id: 'status',
        label: t('loans.fields.status'),
        render: (row) => (
          <Chip
            size="small"
            color={loanStatusColor(String(row.status))}
            label={t(`loans.status.${row.status}`, { defaultValue: row.status })}
          />
        ),
      },
      {
        id: 'dueDate',
        label: t('loans.fields.maturityDate'),
        render: (row) => row.dueDate || '—',
        hideOnMobile: true,
      },
    ],
    [t],
  )

  return (
    <Box>
      <Typography variant="h6" gutterBottom>
        {t('nav.loans')}
      </Typography>
      {loading ? <LoadingState variant="skeleton" rows={3} /> : null}
      {error ? (
        <ErrorState
          message={getErrorMessage(query.error)}
          onRetry={() => void query.refetch()}
        />
      ) : null}
      {!loading && !error ? (
        <ResponsiveTable
          columns={columns}
          rows={rows}
          getRowId={(row) => row.id}
          emptyTitle={t('loans.emptyTitle')}
          emptyDescription={t('loans.memberHistoryEmptyDescription')}
          onRowClick={(row) => navigate(ROUTES.loanDetail(row.id))}
        />
      ) : null}
    </Box>
  )
}

function MemberFinesSection({
  cooperativeId,
  memberUserId,
  embeddedFines,
}: {
  cooperativeId: string
  memberUserId: string
  embeddedFines?: unknown[] | null
}) {
  const { t } = useTranslation()
  const navigate = useNavigate()

  const embedded = useMemo(() => {
    if (!Array.isArray(embeddedFines)) return undefined
    try {
      return embeddedFines.map((item) => mapFine(item as Fine))
    } catch {
      return undefined
    }
  }, [embeddedFines])

  const query = useQuery({
    queryKey: ['fines', 'member', cooperativeId, memberUserId],
    queryFn: () =>
      fetchFines(cooperativeId, {
        memberUserId,
        page: 0,
        size: 20,
        sort: 'createdAt,desc',
      }),
    enabled: Boolean(cooperativeId && memberUserId) && embedded === undefined,
  })

  const rows = embedded ?? query.data?.content ?? []
  const loading = embedded === undefined && query.isLoading
  const error = embedded === undefined && query.isError

  const columns: TableColumn<Fine>[] = useMemo(
    () => [
      {
        id: 'total',
        label: t('fines.fields.totalAmount'),
        render: (row) => formatMoney(row.totalAmount),
      },
      {
        id: 'outstanding',
        label: t('fines.fields.outstanding'),
        render: (row) => formatMoney(row.outstandingAmount ?? 0),
        hideOnMobile: true,
      },
      {
        id: 'status',
        label: t('fines.fields.status'),
        render: (row) => (
          <Chip
            size="small"
            color={fineStatusColor(String(row.status))}
            label={t(`fines.status.${row.status}`, { defaultValue: row.status })}
          />
        ),
      },
      {
        id: 'issuedDate',
        label: t('fines.fields.issuedDate'),
        render: (row) => row.issuedDate || '—',
        hideOnMobile: true,
      },
    ],
    [t],
  )

  return (
    <Box>
      <Typography variant="h6" gutterBottom>
        {t('nav.fines')}
      </Typography>
      {loading ? <LoadingState variant="skeleton" rows={3} /> : null}
      {error ? (
        <ErrorState
          message={getErrorMessage(query.error)}
          onRetry={() => void query.refetch()}
        />
      ) : null}
      {!loading && !error ? (
        <ResponsiveTable
          columns={columns}
          rows={rows}
          getRowId={(row) => row.id}
          emptyTitle={t('fines.emptyTitle')}
          emptyDescription={t('fines.memberHistoryEmptyDescription')}
          onRowClick={(row) => navigate(ROUTES.fineDetail(row.id))}
        />
      ) : null}
    </Box>
  )
}

function MemberSocialFundSection({
  cooperativeId,
  memberUserId,
  embeddedSocial,
}: {
  cooperativeId: string
  memberUserId: string
  embeddedSocial?: unknown[] | null
}) {
  const { t } = useTranslation()

  const embedded = useMemo(() => {
    if (!Array.isArray(embeddedSocial)) return undefined
    try {
      return embeddedSocial.map((item) => mapSocialContribution(item as SocialContribution))
    } catch {
      return undefined
    }
  }, [embeddedSocial])

  const query = useQuery({
    queryKey: ['social-fund', 'contributions', 'member', cooperativeId, memberUserId],
    queryFn: () =>
      fetchSocialContributions(cooperativeId, {
        memberUserId,
        page: 0,
        size: 20,
        sort: 'createdAt,desc',
      }),
    enabled: Boolean(cooperativeId && memberUserId) && embedded === undefined,
  })

  const rows = embedded ?? query.data?.content ?? []
  const loading = embedded === undefined && query.isLoading
  const error = embedded === undefined && query.isError

  const columns: TableColumn<SocialContribution>[] = useMemo(
    () => [
      {
        id: 'amount',
        label: t('socialFund.fields.amount'),
        render: (row) => formatMoney(row.amount),
      },
      {
        id: 'date',
        label: t('socialFund.fields.contributionDate'),
        render: (row) => row.contributionDate || '—',
        hideOnMobile: true,
      },
      {
        id: 'status',
        label: t('socialFund.fields.status'),
        render: (row) => (
          <Chip
            size="small"
            color={socialStatusColor(String(row.status))}
            label={t(`socialFund.status.${row.status}`, { defaultValue: row.status })}
          />
        ),
      },
      {
        id: 'notes',
        label: t('socialFund.fields.notes'),
        render: (row) => row.notes || row.paymentReference || '—',
        hideOnMobile: true,
      },
    ],
    [t],
  )

  return (
    <Box>
      <Typography variant="h6" gutterBottom>
        {t('nav.socialFund')}
      </Typography>
      {loading ? <LoadingState variant="skeleton" rows={3} /> : null}
      {error ? (
        <ErrorState
          message={getErrorMessage(query.error)}
          onRetry={() => void query.refetch()}
        />
      ) : null}
      {!loading && !error ? (
        <ResponsiveTable
          columns={columns}
          rows={rows}
          getRowId={(row) => row.id}
          emptyTitle={t('socialFund.contributions.emptyTitle')}
          emptyDescription={t('socialFund.contributions.memberHistoryEmptyDescription')}
        />
      ) : null}
    </Box>
  )
}

export function MemberDetailPage() {
  const { userId = '' } = useParams()
  const location = useLocation()
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const { enqueueSnackbar } = useSnackbar()
  const cooperativeId = useAppSelector((s) => s.auth.selectedCooperativeId)
  const currentUser = useAppSelector((s) => s.auth.user)
  const canChangeStatus = useAppSelector(selectIsLeadership)
  const canRecord = useAppSelector(selectCanRecordContributions)
  const [editOpen, setEditOpen] = useState(false)
  const [removalOpen, setRemovalOpen] = useState(false)
  const [buySharesOpen, setBuySharesOpen] = useState(false)
  const [statusTarget, setStatusTarget] = useState<MembershipStatus | null>(null)
  const locationPassword = (location.state as { temporaryPassword?: string } | null)
    ?.temporaryPassword
  const [oneTimePassword, setOneTimePassword] = useState<string | null>(
    locationPassword ?? null,
  )

  const query = useQuery({
    queryKey: ['members', cooperativeId, userId],
    queryFn: () => fetchMember(cooperativeId!, userId),
    enabled: Boolean(cooperativeId && userId),
  })

  const updateMutation = useMutation({
    mutationFn: (payload: MemberUpdateRequest) =>
      updateMember(cooperativeId!, userId, payload),
    onSuccess: () => {
      enqueueSnackbar(t('members.updateSuccess'), { variant: 'success' })
      setEditOpen(false)
      void queryClient.invalidateQueries({ queryKey: ['members', cooperativeId] })
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const statusMutation = useMutation({
    mutationFn: (membershipStatus: MembershipStatus) =>
      updateMemberStatus(cooperativeId!, userId, { membershipStatus }),
    onSuccess: () => {
      enqueueSnackbar(t('members.statusUpdated'), { variant: 'success' })
      setStatusTarget(null)
      void queryClient.invalidateQueries({ queryKey: ['members', cooperativeId] })
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const imageMutation = useMutation({
    mutationFn: (file: File) => uploadMemberProfileImage(cooperativeId!, userId, file),
    onSuccess: () => {
      enqueueSnackbar(t('members.profileImageUpdated'), { variant: 'success' })
      void queryClient.invalidateQueries({ queryKey: ['members', cooperativeId, userId] })
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  if (!cooperativeId) {
    return (
      <Box>
        <PageHeader title={t('pages.members.title')} />
        <EmptyState
          title={t('members.selectCooperativeTitle')}
          description={t('members.selectCooperativeDescription')}
        />
      </Box>
    )
  }

  const member = query.data

  return (
    <Box>
      <Button
        component={RouterLink}
        to={ROUTES.members}
        startIcon={<ArrowBackIcon />}
        sx={{ mb: 1 }}
      >
        {t('members.backToList')}
      </Button>

      <PageHeader
        title={member ? memberDisplayName(member) : t('pages.members.title')}
        description={t('members.detailDescription')}
        hideBack
        actions={
          member ? (
            <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
              {currentUser?.id === member.userId || canRecord ? (
                <Button variant="outlined" onClick={() => setBuySharesOpen(true)}>
                  {t('shares.buy.action')}
                </Button>
              ) : null}
              <Button variant="contained" startIcon={<EditIcon />} onClick={() => setEditOpen(true)}>
                {t('common.edit')}
              </Button>
              {canChangeStatus && currentUser?.id !== member.userId ? (
                <Button variant="outlined" color="error" onClick={() => setRemovalOpen(true)}>
                  {t('members.removal.action')}
                </Button>
              ) : null}
            </Stack>
          ) : null
        }
      />

      {oneTimePassword ? (
        <Alert severity="warning" sx={{ mb: 2 }} onClose={() => setOneTimePassword(null)}>
          {t('members.oneTimePasswordAlert', { password: oneTimePassword })}
        </Alert>
      ) : null}

      {query.isLoading ? <LoadingState /> : null}
      {query.isError ? (
        <ErrorState
          message={getErrorMessage(query.error)}
          onRetry={() => void query.refetch()}
        />
      ) : null}

      {member ? (
        <Stack spacing={2.5}>
          <Paper
            elevation={0}
            sx={{ p: { xs: 2.5, md: 3.5 }, border: '1px solid', borderColor: 'divider' }}
          >
            <Stack direction="row" spacing={1} sx={{ mb: 2, flexWrap: 'wrap' }} useFlexGap>
              <Chip size="small" label={t(`status.${member.membershipStatus}`)} color="primary" />
              <Chip
                size="small"
                variant="outlined"
                label={t(`status.${member.accountStatus}`)}
              />
              <Chip
                size="small"
                variant="outlined"
                label={t(`members.roles.${member.roleInCooperative}`, {
                  defaultValue: member.roleInCooperative,
                })}
              />
            </Stack>
            <Box sx={{ mb: 2 }}>
              <RoleDutiesNote role={member.roleInCooperative} compact />
            </Box>

            <Stack spacing={2}>
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                <InfoRow label={t('members.fields.username')} value={member.username} />
                <InfoRow label={t('members.fields.email')} value={member.email} />
              </Stack>
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                <InfoRow label={t('members.fields.phone')} value={member.phone ?? ''} />
                <InfoRow label={t('members.fields.nationalId')} value={member.nationalId ?? ''} />
              </Stack>
              <InfoRow label={t('members.fields.address')} value={member.address ?? ''} />
              <InfoRow
                label={t('members.fields.membershipDate')}
                value={member.membershipDate ?? ''}
              />
              <InfoRow
                label={t('members.fields.shareCount')}
                value={member.shareCount != null ? String(member.shareCount) : '0'}
              />
            </Stack>

            <Box sx={{ mt: 2.5 }}>
              <Typography variant="subtitle2" gutterBottom>
                {t('members.profileImage')}
              </Typography>
              {member.profileImageUrl ? (
                <Box
                  component="img"
                  src={member.profileImageUrl}
                  alt={memberDisplayName(member)}
                  sx={{
                    width: 72,
                    height: 72,
                    borderRadius: '50%',
                    objectFit: 'cover',
                    mb: 1.5,
                    border: '1px solid',
                    borderColor: 'divider',
                  }}
                />
              ) : null}
              <Button variant="outlined" component="label" disabled={imageMutation.isPending}>
                {t('members.uploadProfileImage')}
                <input
                  hidden
                  type="file"
                  accept="image/jpeg,image/png,image/webp"
                  onChange={(e) => {
                    const file = e.target.files?.[0]
                    if (file) imageMutation.mutate(file)
                    e.target.value = ''
                  }}
                />
              </Button>
            </Box>
          </Paper>

          <MemberFinancialSummaryPanel cooperativeId={cooperativeId!} userId={userId} />

          {canChangeStatus ? (
          <Paper
            elevation={0}
            sx={{ p: { xs: 2.5, md: 3 }, border: '1px solid', borderColor: 'divider' }}
          >
            <Typography variant="h6" gutterBottom>
              {t('members.statusActions')}
            </Typography>
            <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
              {MEMBERSHIP_STATUSES.filter((s) => s !== member.membershipStatus && s !== 'INACTIVE').map((next) => (
                <Button
                  key={next}
                  variant="outlined"
                  size="small"
                  onClick={() => setStatusTarget(next)}
                >
                  {t(`status.${next}`)}
                </Button>
              ))}
            </Stack>
          </Paper>
          ) : null}

          <SharePurchaseHistoryPanel cooperativeId={cooperativeId} memberUserId={userId} />
          <MemberContributionsSection cooperativeId={cooperativeId} memberUserId={userId} />
          <MemberLoansSection
            cooperativeId={cooperativeId}
            memberUserId={userId}
            embeddedLoans={member.loans}
          />
          <MemberFinesSection
            cooperativeId={cooperativeId}
            memberUserId={userId}
            embeddedFines={member.fines}
          />
          <MemberSocialFundSection
            cooperativeId={cooperativeId}
            memberUserId={userId}
            embeddedSocial={
              member.socialContributions ?? member.socialFundHistory ?? member.social
            }
          />
          <MemberPayoutsSection
            cooperativeId={cooperativeId}
            memberUserId={userId}
            embeddedPayouts={member.payouts}
          />
        </Stack>
      ) : null}

      {cooperativeId ? (
        <BuySharesDialog
          open={buySharesOpen}
          cooperativeId={cooperativeId}
          memberUserId={
            currentUser?.id === userId || canRecord ? userId : undefined
          }
          onClose={() => setBuySharesOpen(false)}
        />
      ) : null}

      <MemberFormDialog
        key={member?.userId ?? 'member-edit'}
        open={editOpen}
        mode="edit"
        initial={member}
        loading={updateMutation.isPending}
        onClose={() => setEditOpen(false)}
        onUpdate={(payload) => updateMutation.mutate(payload)}
      />

      {member && cooperativeId ? (
        <MemberRemovalDialog
          cooperativeId={cooperativeId}
          userId={member.userId}
          memberName={memberDisplayName(member)}
          open={removalOpen}
          onClose={() => setRemovalOpen(false)}
          leaveAfterDelete
        />
      ) : null}

      <ConfirmDialog
        open={Boolean(statusTarget)}
        title={t('members.confirmStatusTitle')}
        message={
          statusTarget && member
            ? t('members.confirmStatusMessage', {
                name: memberDisplayName(member),
                status: t(`status.${statusTarget}`),
              })
            : ''
        }
        loading={statusMutation.isPending}
        onCancel={() => setStatusTarget(null)}
        onConfirm={() => {
          if (!statusTarget) return
          statusMutation.mutate(statusTarget)
        }}
      />
    </Box>
  )
}
