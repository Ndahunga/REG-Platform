import { Alert, Card, CardContent, Chip, Stack, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import dayjs from 'dayjs'
import { useTranslation } from 'react-i18next'
import { getErrorMessage } from '@/shared/api/client'
import { fetchMemberObligations, fetchMyObligation } from '@/shared/api/rotationPayouts'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import type { MemberObligation } from '@/shared/types/rotationPayout'
import { formatMoney } from '@/shared/utils/formatMoney'

const deadline = (value?: string | null) => (value ? dayjs(value).format('YYYY-MM-DD') : '-')

/**
 * What a member of a rotating scheme has paid in, been paid out and still has to contribute. A member paid early
 * keeps contributing for the months that remain; the advance is the same money seen from the other side and is never
 * added to what is owed. Officers see everyone, a member sees only their own position.
 */
export function RotationObligationsPanel({ cooperativeId, staff }: { cooperativeId: string; staff: boolean }) {
  const { t } = useTranslation()
  const query = useQuery({
    queryKey: ['rotation', cooperativeId, 'obligations', staff ? 'all' : 'mine'],
    queryFn: async () => (staff ? fetchMemberObligations(cooperativeId) : [await fetchMyObligation(cooperativeId)]),
  })
  if (query.isLoading) return <LoadingState variant="skeleton" rows={3} />
  if (query.isError) {
    return <ErrorState message={getErrorMessage(query.error, t('errors.generic'))} onRetry={() => void query.refetch()} />
  }
  const rows = query.data ?? []

  const columns: TableColumn<MemberObligation>[] = [
    { id: 'member', label: t('rotation.obligations.member'), render: (r) => r.memberName || r.memberUserId },
    { id: 'paid', label: t('rotation.obligations.paid'), render: (r) => formatMoney(r.paidContributions) },
    { id: 'received', label: t('rotation.obligations.received'), render: (r) => formatMoney(r.payoutReceived) },
    { id: 'remaining', label: t('rotation.obligations.remaining'), render: (r) => formatMoney(r.remainingScheduled) },
    {
      id: 'overdue',
      label: t('rotation.obligations.overdue'),
      render: (r) => (
        <Typography variant="body2" color={Number(r.overdue) > 0 ? 'error' : 'text.primary'} data-testid="obligation-overdue">
          {formatMoney(r.overdue)}
        </Typography>
      ),
    },
    { id: 'next', label: t('rotation.obligations.nextDeadline'), render: (r) => deadline(r.nextDeadline), hideOnMobile: true },
    {
      id: 'advance',
      label: t('rotation.obligations.advance'),
      render: (r) => (
        <Stack spacing={0.5}>
          <Typography variant="body2" data-testid="obligation-advance">
            {formatMoney(r.rotationalAdvance)}
          </Typography>
          {r.paidOutAndStillContributing ? <Chip size="small" color="info" label={t('rotation.obligations.paidOutEarly')} /> : null}
        </Stack>
      ),
    },
  ]

  if (!staff && rows[0]) {
    const me = rows[0]
    return (
      <Stack spacing={2} data-testid="rotation-obligations">
        <Alert severity="info">{t('rotation.obligations.explain')}</Alert>
        <Card variant="outlined">
          <CardContent>
            <Stack spacing={0.5}>
              {columns.slice(1).map((c) => (
                <Typography key={c.id} variant="body2">
                  {c.label}: {c.render(me)}
                </Typography>
              ))}
            </Stack>
          </CardContent>
        </Card>
      </Stack>
    )
  }

  return (
    <Stack spacing={2} data-testid="rotation-obligations">
      <Alert severity="info">{t('rotation.obligations.explain')}</Alert>
      <ResponsiveTable
        columns={columns}
        rows={rows}
        getRowId={(r) => r.memberUserId}
        emptyTitle={t('rotation.obligations.empty')}
        emptyDescription=""
      />
    </Stack>
  )
}
