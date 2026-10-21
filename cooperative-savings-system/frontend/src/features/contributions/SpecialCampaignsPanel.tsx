import AddIcon from '@mui/icons-material/Add'
import ArrowBackIcon from '@mui/icons-material/ArrowBack'
import {
  Box,
  Button,
  Chip,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate, useParams } from 'react-router-dom'
import { getErrorMessage } from '@/shared/api/client'
import {
  approveSpecialContribution,
  createSpecialCampaign,
  fetchSpecialCampaign,
  fetchSpecialCampaigns,
  fetchSpecialContributions,
  rejectSpecialContribution,
  submitSpecialContribution,
  updateSpecialCampaignStatus,
} from '@/shared/api/specialCampaigns'
import { ConfirmDialog } from '@/shared/components/ConfirmDialog'
import { EmptyState } from '@/shared/components/EmptyState'
import { ErrorState } from '@/shared/components/ErrorState'
import { ListQueryBody } from '@/shared/components/ListQueryBody'
import { LoadingState } from '@/shared/components/LoadingState'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import { ROUTES } from '@/shared/constants/routes'
import type { SpecialCampaign } from '@/shared/types/specialContribution'
import type { SpecialContribution } from '@/shared/types/specialContribution'
import { SPECIAL_CAMPAIGN_STATUSES } from '@/shared/types/specialContribution'
import { formatMoney } from '@/shared/utils/formatMoney'
import { CampaignFormDialog } from './CampaignFormDialog'
import { contributionStatusColor } from './contributionHelpers'
import { SubmitSpecialDialog } from './SubmitSpecialDialog'

function asCampaignList(
  data: Awaited<ReturnType<typeof fetchSpecialCampaigns>> | undefined,
): SpecialCampaign[] {
  if (!data) return []
  return Array.isArray(data) ? data : (data.content ?? [])
}

function asContributionList(
  data: Awaited<ReturnType<typeof fetchSpecialContributions>> | undefined,
): SpecialContribution[] {
  if (!data) return []
  return Array.isArray(data) ? data : (data.content ?? [])
}

interface SpecialCampaignsPanelProps {
  cooperativeId: string
  canWrite: boolean
  campaignId?: string
}

export function SpecialCampaignsPanel({
  cooperativeId,
  canWrite,
  campaignId: campaignIdProp,
}: SpecialCampaignsPanelProps) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const params = useParams()
  const campaignId = campaignIdProp ?? params.campaignId
  const queryClient = useQueryClient()
  const { enqueueSnackbar } = useSnackbar()

  const [createOpen, setCreateOpen] = useState(false)
  const [submitOpen, setSubmitOpen] = useState(false)
  const [statusFilter, setStatusFilter] = useState('')
  const [reviewTarget, setReviewTarget] = useState<{
    id: string
    action: 'approve' | 'reject'
  } | null>(null)

  const listQuery = useQuery({
    queryKey: ['special-campaigns', cooperativeId, statusFilter],
    queryFn: () =>
      fetchSpecialCampaigns(cooperativeId, {
        status: statusFilter || undefined,
        size: 50,
      }),
    enabled: Boolean(cooperativeId) && !campaignId,
  })

  const detailQuery = useQuery({
    queryKey: ['special-campaigns', cooperativeId, campaignId],
    queryFn: () => fetchSpecialCampaign(cooperativeId, campaignId!),
    enabled: Boolean(cooperativeId && campaignId),
  })

  const contributionsQuery = useQuery({
    queryKey: ['special-contributions', cooperativeId, campaignId],
    queryFn: () => fetchSpecialContributions(cooperativeId, campaignId!, { size: 100 }),
    enabled: Boolean(cooperativeId && campaignId),
  })

  const createMutation = useMutation({
    mutationFn: (payload: Parameters<typeof createSpecialCampaign>[1]) =>
      createSpecialCampaign(cooperativeId, payload),
    onSuccess: (created) => {
      enqueueSnackbar(t('contributions.campaigns.createSuccess'), { variant: 'success' })
      setCreateOpen(false)
      void queryClient.invalidateQueries({ queryKey: ['special-campaigns', cooperativeId] })
      navigate(ROUTES.specialCampaign(created.id))
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const statusMutation = useMutation({
    mutationFn: (status: 'DRAFT' | 'ACTIVE' | 'CLOSED' | 'CANCELLED') =>
      updateSpecialCampaignStatus(cooperativeId, campaignId!, { status }),
    onSuccess: () => {
      enqueueSnackbar(t('contributions.campaigns.statusUpdated'), { variant: 'success' })
      void queryClient.invalidateQueries({
        queryKey: ['special-campaigns', cooperativeId, campaignId],
      })
      void queryClient.invalidateQueries({ queryKey: ['special-campaigns', cooperativeId] })
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const submitMutation = useMutation({
    mutationFn: (payload: Parameters<typeof submitSpecialContribution>[2]) =>
      submitSpecialContribution(cooperativeId, campaignId!, payload),
    onSuccess: () => {
      enqueueSnackbar(t('contributions.campaigns.submitSuccess'), { variant: 'success' })
      setSubmitOpen(false)
      void queryClient.invalidateQueries({
        queryKey: ['special-contributions', cooperativeId, campaignId],
      })
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const reviewMutation = useMutation({
    mutationFn: () => {
      if (!reviewTarget || !campaignId) throw new Error('Missing review target')
      return reviewTarget.action === 'approve'
        ? approveSpecialContribution(cooperativeId, campaignId, reviewTarget.id)
        : rejectSpecialContribution(cooperativeId, campaignId, reviewTarget.id)
    },
    onSuccess: () => {
      enqueueSnackbar(
        reviewTarget?.action === 'approve'
          ? t('contributions.campaigns.approveSuccess')
          : t('contributions.campaigns.rejectSuccess'),
        { variant: 'success' },
      )
      setReviewTarget(null)
      void queryClient.invalidateQueries({
        queryKey: ['special-contributions', cooperativeId, campaignId],
      })
      void queryClient.invalidateQueries({ queryKey: ['dashboard', cooperativeId] })
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const campaignColumns: TableColumn<SpecialCampaign>[] = useMemo(
    () => [
      {
        id: 'name',
        label: t('contributions.campaigns.fields.name'),
        render: (row) => row.name,
      },
      {
        id: 'status',
        label: t('contributions.fields.status'),
        render: (row) => (
          <Chip
            size="small"
            color={contributionStatusColor(String(row.status))}
            label={t(`contributions.campaignStatus.${row.status}`, {
              defaultValue: row.status,
            })}
          />
        ),
      },
      {
        id: 'target',
        label: t('contributions.campaigns.fields.targetAmount'),
        render: (row) =>
          row.targetAmount != null ? formatMoney(row.targetAmount) : '—',
        hideOnMobile: true,
      },
      {
        id: 'dates',
        label: t('contributions.campaigns.fields.dates'),
        render: (row) =>
          [row.startDate, row.endDate].filter(Boolean).join(' → ') || '—',
        hideOnMobile: true,
      },
    ],
    [t],
  )

  const contributionColumns: TableColumn<SpecialContribution>[] = useMemo(
    () => [
      {
        id: 'member',
        label: t('contributions.fields.member'),
        // Never fall back to the UUID: it means nothing to the reader.
        render: (row) => row.fullName || row.username || t('contributions.unnamedMember'),
      },
      {
        id: 'amount',
        label: t('contributions.fields.paid'),
        render: (row) => formatMoney(row.amount),
      },
      {
        id: 'date',
        label: t('contributions.fields.paymentDate'),
        render: (row) => row.contributionDate || '—',
        hideOnMobile: true,
      },
      {
        id: 'status',
        label: t('contributions.fields.status'),
        render: (row) => (
          <Chip
            size="small"
            color={contributionStatusColor(String(row.status))}
            label={t(`contributions.specialStatus.${row.status}`, {
              defaultValue: row.status,
            })}
          />
        ),
      },
      ...(canWrite
        ? [
            {
              id: 'actions',
              label: t('common.actions'),
              render: (row: SpecialContribution) =>
                row.status === 'PENDING' ? (
                  <Stack direction="row" spacing={1}>
                    <Button
                      size="small"
                      variant="contained"
                      onClick={(e) => {
                        e.stopPropagation()
                        setReviewTarget({ id: row.id, action: 'approve' })
                      }}
                    >
                      {t('contributions.campaigns.approve')}
                    </Button>
                    <Button
                      size="small"
                      color="error"
                      variant="outlined"
                      onClick={(e) => {
                        e.stopPropagation()
                        setReviewTarget({ id: row.id, action: 'reject' })
                      }}
                    >
                      {t('contributions.campaigns.reject')}
                    </Button>
                  </Stack>
                ) : (
                  '—'
                ),
            },
          ]
        : []),
    ],
    [canWrite, t],
  )

  if (campaignId) {
    if (detailQuery.isLoading || contributionsQuery.isLoading) {
      return <LoadingState variant="skeleton" rows={4} />
    }
    if (detailQuery.isError) {
      return (
        <ErrorState
          message={getErrorMessage(detailQuery.error)}
          onRetry={() => void detailQuery.refetch()}
        />
      )
    }

    const campaign = detailQuery.data
    const contributions = asContributionList(contributionsQuery.data)

    return (
      <Box>
        <Button
          startIcon={<ArrowBackIcon />}
          onClick={() => navigate(ROUTES.contributions)}
          sx={{ mb: 2 }}
        >
          {t('contributions.campaigns.backToList')}
        </Button>

        {campaign ? (
          <Stack spacing={2}>
            <Stack
              direction={{ xs: 'column', sm: 'row' }}
              spacing={1.5}
              sx={{ justifyContent: 'space-between', alignItems: { sm: 'flex-start' } }}
            >
              <Box>
                <Typography variant="h5" gutterBottom>
                  {campaign.name}
                </Typography>
                <Chip
                  size="small"
                  color={contributionStatusColor(String(campaign.status))}
                  label={t(`contributions.campaignStatus.${campaign.status}`, {
                    defaultValue: campaign.status,
                  })}
                />
                {campaign.purpose ? (
                  <Typography color="text.secondary" sx={{ mt: 1 }}>
                    {campaign.purpose}
                  </Typography>
                ) : null}
                {campaign.description ? (
                  <Typography variant="body2" sx={{ mt: 1 }}>
                    {campaign.description}
                  </Typography>
                ) : null}
                <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
                  {t('contributions.campaigns.fields.suggestedAmount')}:{' '}
                  {campaign.suggestedAmount != null
                    ? formatMoney(campaign.suggestedAmount)
                    : '—'}
                </Typography>
              </Box>
              <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
                {campaign.status === 'ACTIVE' ? (
                  <Button variant="contained" onClick={() => setSubmitOpen(true)}>
                    {t('contributions.campaigns.submit')}
                  </Button>
                ) : null}
                {canWrite && campaign.status === 'DRAFT' ? (
                  <Button
                    variant="outlined"
                    onClick={() => statusMutation.mutate('ACTIVE')}
                    disabled={statusMutation.isPending}
                  >
                    {t('contributions.campaigns.activate')}
                  </Button>
                ) : null}
                {canWrite && campaign.status === 'ACTIVE' ? (
                  <Button
                    variant="outlined"
                    onClick={() => statusMutation.mutate('CLOSED')}
                    disabled={statusMutation.isPending}
                  >
                    {t('contributions.campaigns.close')}
                  </Button>
                ) : null}
                {canWrite &&
                (campaign.status === 'DRAFT' || campaign.status === 'ACTIVE') ? (
                  <Button
                    color="error"
                    variant="outlined"
                    onClick={() => statusMutation.mutate('CANCELLED')}
                    disabled={statusMutation.isPending}
                  >
                    {t('contributions.campaigns.cancel')}
                  </Button>
                ) : null}
              </Stack>
            </Stack>

            <Typography variant="h6">{t('contributions.campaigns.contributions')}</Typography>
            {contributionsQuery.isError ? (
              <ErrorState
                message={getErrorMessage(contributionsQuery.error)}
                onRetry={() => void contributionsQuery.refetch()}
              />
            ) : (
              <ResponsiveTable
                columns={contributionColumns}
                rows={contributions}
                getRowId={(row) => row.id}
                emptyTitle={t('contributions.campaigns.contributionsEmptyTitle')}
                emptyDescription={t('contributions.campaigns.contributionsEmptyDescription')}
              />
            )}
          </Stack>
        ) : null}

        <SubmitSpecialDialog
          open={submitOpen}
          loading={submitMutation.isPending}
          suggestedAmount={campaign?.suggestedAmount}
          onClose={() => setSubmitOpen(false)}
          onSubmit={(payload) => submitMutation.mutate(payload)}
        />

        <ConfirmDialog
          open={Boolean(reviewTarget)}
          title={
            reviewTarget?.action === 'approve'
              ? t('contributions.campaigns.confirmApproveTitle')
              : t('contributions.campaigns.confirmRejectTitle')
          }
          message={
            reviewTarget?.action === 'approve'
              ? t('contributions.campaigns.confirmApproveMessage')
              : t('contributions.campaigns.confirmRejectMessage')
          }
          loading={reviewMutation.isPending}
          onCancel={() => setReviewTarget(null)}
          onConfirm={() => reviewMutation.mutate()}
        />
      </Box>
    )
  }

  const campaigns = asCampaignList(listQuery.data)

  return (
    <Box>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={1.5}
        sx={{ mb: 2, justifyContent: 'space-between' }}
      >
        <TextField
          select
          size="small"
          label={t('contributions.fields.status')}
          value={statusFilter}
          onChange={(e) => setStatusFilter(e.target.value)}
          sx={{ minWidth: 160 }}
        >
          <MenuItem value="">{t('common.all')}</MenuItem>
          {SPECIAL_CAMPAIGN_STATUSES.map((s) => (
            <MenuItem key={s} value={s}>
              {t(`contributions.campaignStatus.${s}`)}
            </MenuItem>
          ))}
        </TextField>
        {canWrite ? (
          <Button variant="contained" startIcon={<AddIcon />} onClick={() => setCreateOpen(true)}>
            {t('contributions.campaigns.create')}
          </Button>
        ) : null}
      </Stack>

      <ListQueryBody
        isLoading={listQuery.isLoading}
        isError={listQuery.isError}
        error={listQuery.error}
        onRetry={() => void listQuery.refetch()}
      >
      {campaigns.length === 0 ? (
        <EmptyState
          title={t('contributions.campaigns.emptyTitle')}
          description={t('contributions.campaigns.emptyDescription')}
        />
      ) : (
        <ResponsiveTable
          columns={campaignColumns}
          rows={campaigns}
          getRowId={(row) => row.id}
          onRowClick={(row) => navigate(ROUTES.specialCampaign(row.id))}
        />
      )}
      </ListQueryBody>

      <CampaignFormDialog
        open={createOpen}
        loading={createMutation.isPending}
        onClose={() => setCreateOpen(false)}
        onSubmit={(payload) => createMutation.mutate(payload)}
      />
    </Box>
  )
}
