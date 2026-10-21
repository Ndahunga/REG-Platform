import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { getErrorMessage } from '@/shared/api/client'
import { fetchMembers } from '@/shared/api/members'
import {
  activateRotationCycle,
  cancelRotationCycle,
  changeRotationRecipient,
  createRotationCycle,
  fetchRotationCycles,
  fetchRotationRecipientChanges,
} from '@/shared/api/rotation'
import { EmptyState } from '@/shared/components/EmptyState'
import { LoadingState } from '@/shared/components/LoadingState'
import type { RotationCycle, RotationCycleStatus, RotationPeriod } from '@/shared/types/rotation'
import { CycleCreateDialog } from './CycleCreateDialog'
import { ReasonDialog, type RecipientOption } from './ReasonDialog'
import { memberLabel, monthKey } from './rotationHelpers'

interface RotationSchedulePanelProps {
  cooperativeId: string
  canManage: boolean
}

function statusColor(status: RotationCycleStatus): 'default' | 'success' | 'warning' | 'error' {
  switch (status) {
    case 'ACTIVE':
      return 'success'
    case 'DRAFT':
      return 'warning'
    case 'CANCELLED':
      return 'error'
    default:
      return 'default'
  }
}

/**
 * The monthly rotation schedule: one scheduled recipient per month, in the order set when the cycle was created.
 * Every active member can read it; only administrators see the actions. Nothing here moves money.
 */
export function RotationSchedulePanel({ cooperativeId, canManage }: RotationSchedulePanelProps) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)
  const [cancelTarget, setCancelTarget] = useState<RotationCycle | null>(null)
  const [reassignTarget, setReassignTarget] = useState<{ cycle: RotationCycle; period: RotationPeriod } | null>(null)
  const [historyTarget, setHistoryTarget] = useState<RotationPeriod | null>(null)

  const cyclesQuery = useQuery({
    queryKey: ['rotation', cooperativeId, 'cycles'],
    queryFn: () => fetchRotationCycles(cooperativeId),
  })
  const membersQuery = useQuery({
    queryKey: ['rotation', cooperativeId, 'members'],
    queryFn: () => fetchMembers(cooperativeId, { size: 500 }),
    enabled: canManage && reassignTarget !== null,
  })
  const historyQuery = useQuery({
    queryKey: ['rotation', cooperativeId, 'history', historyTarget?.id],
    queryFn: () => fetchRotationRecipientChanges(cooperativeId, historyTarget!.id),
    enabled: historyTarget !== null,
  })

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['rotation', cooperativeId] })
  const onError = (error: unknown) =>
    enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })

  const createMutation = useMutation({
    mutationFn: (args: { payload: Parameters<typeof createRotationCycle>[1]; key: string }) =>
      createRotationCycle(cooperativeId, args.payload, args.key),
    onSuccess: () => {
      enqueueSnackbar(t('rotation.cycle.created'), { variant: 'success' })
      setCreateOpen(false)
      void refresh()
    },
    onError,
  })
  const activateMutation = useMutation({
    mutationFn: (cycleId: string) => activateRotationCycle(cooperativeId, cycleId),
    onSuccess: () => {
      enqueueSnackbar(t('rotation.cycle.activated'), { variant: 'success' })
      void refresh()
    },
    onError,
  })
  const cancelMutation = useMutation({
    mutationFn: (args: { cycleId: string; reason: string }) =>
      cancelRotationCycle(cooperativeId, args.cycleId, args.reason),
    onSuccess: () => {
      enqueueSnackbar(t('rotation.cycle.cancelled'), { variant: 'success' })
      setCancelTarget(null)
      void refresh()
    },
    onError,
  })
  const reassignMutation = useMutation({
    mutationFn: (args: { periodId: string; recipientUserId: string; reason: string }) =>
      changeRotationRecipient(cooperativeId, args.periodId, args.recipientUserId, args.reason),
    onSuccess: () => {
      enqueueSnackbar(t('rotation.recipient.changed'), { variant: 'success' })
      setReassignTarget(null)
      void refresh()
    },
    onError,
  })

  const reassignOptions: RecipientOption[] = useMemo(() => {
    if (!reassignTarget) return []
    const taken = new Set(reassignTarget.cycle.periods.map((p) => p.recipientUserId))
    return (membersQuery.data?.content ?? [])
      .filter((m) => m.membershipStatus === 'ACTIVE' && !taken.has(m.userId))
      .map((m) => ({ userId: m.userId, label: memberLabel(m) }))
  }, [membersQuery.data, reassignTarget])

  const cycles = cyclesQuery.data ?? []

  return (
    <Stack spacing={2.5}>
      {canManage ? (
        <Box>
          <Button variant="contained" onClick={() => setCreateOpen(true)}>
            {t('rotation.cycle.new')}
          </Button>
        </Box>
      ) : null}

      {cyclesQuery.isLoading ? <LoadingState /> : null}
      {cyclesQuery.isSuccess && cycles.length === 0 ? (
        <EmptyState title={t('rotation.schedule.emptyTitle')} description={t('rotation.schedule.emptyDescription')} />
      ) : null}

      {cycles.map((cycle) => {
        const editable = cycle.status === 'DRAFT' || cycle.status === 'ACTIVE'
        const firstStarted = cycle.periods[0]?.started ?? false
        return (
          <Paper
            key={cycle.id}
            elevation={0}
            data-testid="rotation-cycle"
            sx={{ p: { xs: 2, md: 3 }, border: '1px solid', borderColor: 'divider' }}
          >
            <Stack direction="row" spacing={1} sx={{ mb: 1.5, alignItems: 'center', flexWrap: 'wrap' }} useFlexGap>
              <Typography variant="h6">{t('rotation.cycle.title', { number: cycle.cycleNumber })}</Typography>
              <Chip size="small" color={statusColor(cycle.status)} label={t(`rotation.cycle.status.${cycle.status}`)} />
              <Typography variant="body2" color="text.secondary">
                {t('rotation.cycle.startsAt', { month: monthKey(cycle.startYear, cycle.startMonth) })}
              </Typography>
            </Stack>
            {cycle.status === 'CANCELLED' && cycle.cancelReason ? (
              <Alert severity="warning" sx={{ mb: 1.5 }}>
                {t('rotation.cycle.cancelReasonShown', { reason: cycle.cancelReason })}
              </Alert>
            ) : null}
            <Box sx={{ overflowX: 'auto' }}>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>{t('rotation.schedule.turn')}</TableCell>
                    <TableCell>{t('rotation.schedule.month')}</TableCell>
                    <TableCell>{t('rotation.schedule.recipient')}</TableCell>
                    <TableCell align="right">{t('rotation.schedule.actions')}</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {cycle.periods.map((period) => (
                    <TableRow key={period.id} data-testid="rotation-period">
                      <TableCell>{period.sequenceNumber}</TableCell>
                      <TableCell>{monthKey(period.year, period.month)}</TableCell>
                      <TableCell>
                        {period.recipientName ?? period.recipientUserId}
                        {period.started ? (
                          <Chip size="small" sx={{ ml: 1 }} label={t('rotation.schedule.started')} />
                        ) : null}
                      </TableCell>
                      <TableCell align="right">
                        <Button size="small" onClick={() => setHistoryTarget(period)}>
                          {t('rotation.recipient.history')}
                        </Button>
                        {canManage && editable && !period.started ? (
                          <Button size="small" onClick={() => setReassignTarget({ cycle, period })}>
                            {t('rotation.recipient.change')}
                          </Button>
                        ) : null}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </Box>
            {canManage && editable ? (
              <Stack direction="row" spacing={1} sx={{ mt: 2 }}>
                {cycle.status === 'DRAFT' ? (
                  <Button
                    variant="contained"
                    size="small"
                    disabled={activateMutation.isPending}
                    onClick={() => activateMutation.mutate(cycle.id)}
                  >
                    {t('rotation.cycle.activate')}
                  </Button>
                ) : null}
                {!firstStarted ? (
                  <Button variant="outlined" color="error" size="small" onClick={() => setCancelTarget(cycle)}>
                    {t('rotation.cycle.cancel')}
                  </Button>
                ) : null}
              </Stack>
            ) : null}
          </Paper>
        )
      })}

      <CycleCreateDialog
        open={createOpen}
        cooperativeId={cooperativeId}
        loading={createMutation.isPending}
        onClose={() => setCreateOpen(false)}
        onSubmit={(payload, key) => createMutation.mutate({ payload, key })}
      />
      <ReasonDialog
        open={cancelTarget !== null}
        title={t('rotation.cycle.cancelTitle')}
        description={t('rotation.cycle.cancelHelp')}
        submitLabel={t('rotation.cycle.cancelConfirm')}
        loading={cancelMutation.isPending}
        onClose={() => setCancelTarget(null)}
        onSubmit={(reason) => cancelTarget && cancelMutation.mutate({ cycleId: cancelTarget.id, reason })}
      />
      <ReasonDialog
        open={reassignTarget !== null}
        title={t('rotation.recipient.changeTitle')}
        description={t('rotation.recipient.changeHelp')}
        submitLabel={t('rotation.recipient.changeConfirm')}
        loading={reassignMutation.isPending}
        recipientOptions={reassignOptions}
        onClose={() => setReassignTarget(null)}
        onSubmit={(reason, recipientUserId) =>
          reassignTarget &&
          recipientUserId &&
          reassignMutation.mutate({ periodId: reassignTarget.period.id, recipientUserId, reason })
        }
      />
      <Dialog open={historyTarget !== null} onClose={() => setHistoryTarget(null)} fullWidth maxWidth="sm">
        <DialogTitle>{t('rotation.recipient.historyTitle')}</DialogTitle>
        <DialogContent>
          {historyQuery.isLoading ? <LoadingState /> : null}
          {historyQuery.isSuccess && (historyQuery.data ?? []).length === 0 ? (
            <Typography color="text.secondary">{t('rotation.recipient.historyNone')}</Typography>
          ) : null}
          <Stack spacing={1.5}>
            {(historyQuery.data ?? []).map((change) => (
              <Box key={change.id} data-testid="rotation-change">
                <Typography variant="body2" sx={{ fontWeight: 600 }}>
                  {change.previousRecipientName ?? change.previousRecipientUserId} →{' '}
                  {change.newRecipientName ?? change.newRecipientUserId}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  {change.reason}
                  {change.changedAt ? ` — ${change.changedAt.slice(0, 10)}` : ''}
                </Typography>
              </Box>
            ))}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setHistoryTarget(null)}>{t('common.close')}</Button>
        </DialogActions>
      </Dialog>
    </Stack>
  )
}
