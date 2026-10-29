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
import { fetchRotationPayouts } from '@/shared/api/rotationPayouts'
import {
  activateRotationCycle,
  cancelRotationCycle,
  changeRotationRecipient,
  createRotationCycle,
  createShareCycle,
  fetchRotationCycles,
  fetchSharePool,
  fetchTurnEligibility,
  importRotation,
  reassignRotationTurn,
  fetchRotationRecipientChanges,
} from '@/shared/api/rotation'
import { EmptyState } from '@/shared/components/EmptyState'
import { LoadingState } from '@/shared/components/LoadingState'
import type { RotationCycle, RotationCycleStatus, RotationPeriod, RotationTurn } from '@/shared/types/rotation'
import { ShareCycleDialog } from './ShareCycleDialog'
import { money } from './shareCycle'
import { TurnPayAction } from './TurnPayAction'
import { CycleCreateDialog } from './CycleCreateDialog'
import { ReasonDialog, type RecipientOption } from './ReasonDialog'
import { memberLabel, monthKey } from './rotationHelpers'

interface RotationSchedulePanelProps {
  cooperativeId: string
  canManage: boolean
  model?: 'ROTATING' | 'HYBRID'
  /** The officer may handle payouts: each due turn gets its Pay / Approve / Confirm paid step right here. */
  payoutStaff?: boolean
}

function turnColor(state: RotationTurn['state']): 'default' | 'success' | 'warning' | 'info' {
  switch (state) {
    case 'PAID':
      return 'success'
    case 'PARTLY_PAID':
      return 'warning'
    case 'DUE':
      return 'info'
    default:
      return 'default'
  }
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
export function RotationSchedulePanel({ cooperativeId, canManage, model = 'HYBRID', payoutStaff = false }: RotationSchedulePanelProps) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)
  const [cancelTarget, setCancelTarget] = useState<RotationCycle | null>(null)
  const [reassignTarget, setReassignTarget] = useState<{ cycle: RotationCycle; period: RotationPeriod } | null>(null)
  const [historyTarget, setHistoryTarget] = useState<RotationPeriod | null>(null)
  const [shareOpen, setShareOpen] = useState<'create' | 'import' | null>(null)
  const [turnTarget, setTurnTarget] = useState<RotationTurn | null>(null)

  const cyclesQuery = useQuery({
    queryKey: ['rotation', cooperativeId, 'cycles'],
    queryFn: () => fetchRotationCycles(cooperativeId),
  })
  const membersQuery = useQuery({
    queryKey: ['rotation', cooperativeId, 'members'],
    queryFn: () => fetchMembers(cooperativeId, { size: 500 }),
    enabled: canManage && (reassignTarget !== null || turnTarget !== null),
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

  const shareMutation = useMutation({
    mutationFn: (args: { payload: Parameters<typeof createShareCycle>[1]; key: string }) =>
      createShareCycle(cooperativeId, args.payload, args.key),
    onSuccess: () => {
      enqueueSnackbar(t('rotation.cycle.created'), { variant: 'success' })
      setShareOpen(null)
      void refresh()
    },
    onError,
  })
  const importMutation = useMutation({
    mutationFn: (payload: Parameters<typeof importRotation>[1]) => importRotation(cooperativeId, payload),
    onSuccess: (result) => {
      const diff = Number(result.difference ?? 0)
      enqueueSnackbar(
        diff === 0
          ? t('rotation.share.imported')
          : t('rotation.share.importedWithDifference', { amount: money(diff) }),
        { variant: diff === 0 ? 'success' : 'warning' },
      )
      setShareOpen(null)
      void refresh()
    },
    onError,
  })
  const turnMutation = useMutation({
    mutationFn: (args: { turnId: string; memberUserId: string; reason: string }) =>
      reassignRotationTurn(cooperativeId, args.turnId, args.memberUserId, args.reason),
    onSuccess: () => {
      enqueueSnackbar(t('rotation.recipient.changed'), { variant: 'success' })
      setTurnTarget(null)
      void refresh()
    },
    onError,
  })
  const turnOptions: RecipientOption[] = useMemo(
    () =>
      (membersQuery.data?.content ?? [])
        .filter((m) => m.membershipStatus === 'ACTIVE' && m.userId !== turnTarget?.memberUserId)
        .map((m) => ({ userId: m.userId, label: memberLabel(m) })),
    [membersQuery.data, turnTarget],
  )

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
        <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
          <Button variant="contained" onClick={() => setShareOpen('create')}>
            {t('rotation.share.new')}
          </Button>
          <Button variant="outlined" onClick={() => setShareOpen('import')}>
            {t('rotation.share.importButton')}
          </Button>
          <Button onClick={() => setCreateOpen(true)}>{t('rotation.cycle.new')}</Button>
        </Stack>
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
            {cycle.payoutMode === 'FIXED_PER_SHARE' ? (
              <ShareTurns
                cooperativeId={cooperativeId}
                payoutStaff={payoutStaff && cycle.status === 'ACTIVE'}
                cycle={cycle}
                canReassign={canManage && editable}
                onReassign={(turn) => setTurnTarget(turn)}
              />
            ) : (
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
            )}
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
      <ShareCycleDialog
        open={shareOpen !== null}
        cooperativeId={cooperativeId}
        mode={shareOpen ?? 'create'}
        model={model}
        loading={shareMutation.isPending || importMutation.isPending}
        onClose={() => setShareOpen(null)}
        onCreate={(payload, key) => shareMutation.mutate({ payload, key })}
        onImport={(payload) => importMutation.mutate(payload)}
      />
      <ReasonDialog
        open={turnTarget !== null}
        title={t('rotation.share.reassignTitle')}
        description={t('rotation.share.reassignHelp')}
        submitLabel={t('rotation.recipient.changeConfirm')}
        loading={turnMutation.isPending}
        recipientOptions={turnOptions}
        onClose={() => setTurnTarget(null)}
        onSubmit={(reason, memberUserId) =>
          turnTarget && memberUserId && turnMutation.mutate({ turnId: turnTarget.id, memberUserId, reason })
        }
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

/** The turns of a share-based cycle: who is paid in which month, how much, and what is still to pay. */
function ShareTurns({
  cooperativeId,
  payoutStaff,
  cycle,
  canReassign,
  onReassign,
}: {
  cooperativeId: string
  payoutStaff: boolean
  cycle: RotationCycle
  canReassign: boolean
  onReassign: (turn: RotationTurn) => void
}) {
  const { t } = useTranslation()
  const eligibility = useQuery({
    queryKey: ['rotation', cooperativeId, 'turn-eligibility'],
    queryFn: () => fetchTurnEligibility(cooperativeId),
    enabled: payoutStaff,
  })
  const payouts = useQuery({
    queryKey: ['rotation', cooperativeId, 'payouts', 'all'],
    queryFn: () => fetchRotationPayouts(cooperativeId),
    enabled: payoutStaff,
  })
  const pool = useQuery({
    queryKey: ['rotation', cooperativeId, 'share-pool'],
    queryFn: () => fetchSharePool(cooperativeId),
    enabled: payoutStaff,
  })
  const eligibilityOf = (turnId: string) => (eligibility.data ?? []).find((e) => e.turnId === turnId)
  const openPayoutOf = (turnId: string) =>
    (payouts.data ?? []).find((p) => p.rotationTurnId === turnId && (p.status === 'REQUESTED' || p.status === 'APPROVED'))
  const turns = cycle.turns ?? []
  const check = cycle.fundingCheck
  const next = turns.find((turn) => turn.state !== 'PAID')
  return (
    <Stack spacing={1.5}>
      <Typography variant="body2" color="text.secondary">
        {t('rotation.share.summary', {
          perShare: money(cycle.payoutPerShare),
          months: cycle.periodCount,
          mode: cycle.turnMode ? t(`rotation.share.turnModes.${cycle.turnMode}`) : '',
        })}
      </Typography>
      {next ? (
        <Alert severity="info" data-testid="share-next">
          {t('rotation.share.next', {
            name: next.memberName ?? '',
            month: monthKey(next.year, next.month),
            amount: money(next.outstanding),
          })}
        </Alert>
      ) : null}
      {check && check.warnings.length > 0 ? (
        <Alert severity="warning">
          {check.warnings.map((w) => {
            const [code, name, count] = w.split(':')
            return <div key={w}>{t(`rotation.share.warning.${code}`, { name, count })}</div>
          })}
        </Alert>
      ) : null}
      <Box sx={{ overflowX: 'auto' }}>
        <Table size="small" data-testid="share-turn-table">
          <TableHead>
            <TableRow>
              <TableCell>{t('rotation.share.month')}</TableCell>
              <TableCell>{t('rotation.share.member')}</TableCell>
              <TableCell>{t('rotation.share.shares')}</TableCell>
              <TableCell align="right">{t('rotation.share.payout')}</TableCell>
              <TableCell align="right">{t('rotation.share.paid')}</TableCell>
              <TableCell align="right">{t('rotation.share.outstanding')}</TableCell>
              <TableCell>{t('rotation.share.state')}</TableCell>
              <TableCell />
            </TableRow>
          </TableHead>
          <TableBody>
            {turns.map((turn) => (
              <TableRow key={turn.id}>
                <TableCell>{monthKey(turn.year, turn.month)}</TableCell>
                <TableCell>{turn.memberName}</TableCell>
                <TableCell>{turn.shares}</TableCell>
                <TableCell align="right">{money(turn.payoutAmount)}</TableCell>
                <TableCell align="right">{money(Number(turn.paidOut) + Number(turn.openingReceived))}</TableCell>
                <TableCell align="right">{money(turn.outstanding)}</TableCell>
                <TableCell>
                  <Chip size="small" color={turnColor(turn.state)} label={t(`rotation.share.states.${turn.state}`)} />
                </TableCell>
                <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                  {payoutStaff ? (
                    <TurnPayAction
                      cooperativeId={cooperativeId}
                      turn={turn}
                      eligibility={eligibilityOf(turn.id)}
                      openPayout={openPayoutOf(turn.id)}
                      pool={pool.data}
                    />
                  ) : null}
                  {canReassign && turn.state === 'UPCOMING' ? (
                    <Button size="small" onClick={() => onReassign(turn)}>
                      {t('rotation.recipient.change')}
                    </Button>
                  ) : null}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Box>
    </Stack>
  )
}
