import AddIcon from '@mui/icons-material/Add'
import DeleteOutlinedIcon from '@mui/icons-material/DeleteOutlined'
import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  MenuItem,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { fetchCooperative } from '@/shared/api/cooperatives'
import { fetchMembers } from '@/shared/api/members'
import type { RotationImportRequest, RotationTurnMode, ShareCycleCreateRequest } from '@/shared/types/rotation'
import { firstSelectableMonth, memberLabel, newIdempotencyKey, parseMonthInput } from './rotationHelpers'
import { autoArrange, checkTurns, draftKey, money, monthOf, type DraftTurn, type ShareHolder } from './shareCycle'
import { MoneyField } from '@/shared/components/MoneyField'

const TURN_MODES: RotationTurnMode[] = ['ONE_SHARE_PER_TURN', 'ALL_SHARES_TOGETHER', 'GROUPED_BY_MONTH']

function currentMonth(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

interface ShareCycleDialogProps {
  open: boolean
  cooperativeId: string
  /** create: a new cycle starting in a month that has not begun. import: a cycle already running (migration). */
  mode: 'create' | 'import'
  model: 'ROTATING' | 'HYBRID'
  loading?: boolean
  onClose: () => void
  onCreate?: (payload: ShareCycleCreateRequest, idempotencyKey: string) => void
  onImport?: (payload: RotationImportRequest) => void
}

/**
 * Builds a share-based cycle: every share has one turn worth a fixed payout, a month may hold several turns, and a
 * member with several shares can be paid share by share, all at once, or grouped as the members agree. In import mode
 * it also records what each turn already received and what each member already paid into the rotation.
 */
export function ShareCycleDialog({ open, cooperativeId, mode, model, loading, onClose, onCreate, onImport }: ShareCycleDialogProps) {
  const { t } = useTranslation()
  const importing = mode === 'import'
  const minMonth = firstSelectableMonth()
  const [start, setStart] = useState(importing ? '' : minMonth)
  const [months, setMonths] = useState('12')
  const [turnMode, setTurnMode] = useState<RotationTurnMode>('ONE_SHARE_PER_TURN')
  const [rotated, setRotated] = useState('')
  const [perShare, setPerShare] = useState('')
  const [turns, setTurns] = useState<DraftTurn[]>([])
  const [paidIn, setPaidIn] = useState<Record<string, string>>({})
  const [cash, setCash] = useState('')
  const [submitted, setSubmitted] = useState(false)
  const keyRef = useRef(newIdempotencyKey())

  const schemeQuery = useQuery({
    queryKey: ['cooperatives', cooperativeId],
    queryFn: () => fetchCooperative(cooperativeId),
    enabled: open,
  })
  const membersQuery = useQuery({
    queryKey: ['rotation', cooperativeId, 'members'],
    queryFn: () => fetchMembers(cooperativeId, { size: 500 }),
    enabled: open,
  })
  const unit = Number(schemeQuery.data?.monthlyContributionAmount ?? 0)
  const holders: ShareHolder[] = useMemo(
    () =>
      (membersQuery.data?.content ?? [])
        .filter((m) => m.membershipStatus === 'ACTIVE')
        .map((m) => ({ userId: m.userId, label: memberLabel(m), shares: Number(m.shareCount ?? 1) })),
    [membersQuery.data],
  )
  const names = useMemo(() => new Map(holders.map((h) => [h.userId, h.label])), [holders])
  const monthCount = Math.max(0, Math.floor(Number(months)))
  const rotatedPerShare = model === 'ROTATING' ? unit : Number(rotated.replace(/[,\s]/g, ''))
  const totalShares = holders.reduce((a, h) => a + h.shares, 0)

  // Sensible defaults: as many months as shares, rotated amount x months per share.
  useEffect(() => {
    if (open && totalShares > 0 && months === '12' && turns.length === 0) setMonths(String(Math.min(totalShares, 12)))
  }, [open, totalShares]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (rotatedPerShare > 0 && monthCount > 0) setPerShare(String(rotatedPerShare * monthCount))
  }, [rotatedPerShare, monthCount])

  const payout = Number(perShare.replace(/[,\s]/g, ''))
  const check = checkTurns(turns, holders, monthCount, turnMode)
  const parsedStart = parseMonthInput(start)
  const startProblem = !parsedStart || (!importing && start < minMonth) || (importing && start > currentMonth())
  const splitProblem = model === 'HYBRID' && (!(rotatedPerShare > 0) || (unit > 0 && rotatedPerShare >= unit))
  const payoutProblem = !(payout > 0)
  const collections = rotatedPerShare * totalShares * monthCount
  const payouts = payout * check.sharesPlaced

  const close = () => {
    setTurns([])
    setSubmitted(false)
    setPaidIn({})
    setCash('')
    keyRef.current = newIdempotencyKey()
    onClose()
  }

  const update = (key: string, patch: Partial<DraftTurn>) =>
    setTurns((all) => all.map((turn) => (turn.key === key ? { ...turn, ...patch } : turn)))

  const submit = () => {
    setSubmitted(true)
    if (startProblem || payoutProblem || splitProblem || check.problems.length > 0 || !parsedStart || monthCount < 1) return
    const cycle: ShareCycleCreateRequest = {
      startYear: parsedStart.year,
      startMonth: parsedStart.month,
      months: monthCount,
      payoutPerShare: payout,
      turnMode,
      turns: turns.map(({ monthIndex, memberUserId, shares, openingReceivedAmount }) => ({
        monthIndex,
        memberUserId,
        shares,
        openingReceivedAmount: importing && openingReceivedAmount ? openingReceivedAmount : undefined,
      })),
    }
    if (!importing) {
      onCreate?.(cycle, keyRef.current)
      return
    }
    const now = currentMonth().split('-').map(Number)
    onImport?.({
      cutoverYear: now[0],
      cutoverMonth: now[1],
      rotatingUnitAmount: model === 'HYBRID' ? rotatedPerShare : undefined,
      cycle,
      members: holders.map((h) => ({ memberUserId: h.userId, rotatedPaid: Number((paidIn[h.userId] ?? '0').replace(/[,\s]/g, '')) || 0 })),
      rotationCashAtCutover: cash.trim() ? Number(cash.replace(/[,\s]/g, '')) : undefined,
    })
  }

  // Import: what each member should have paid into the rotation by now if they were up to date.
  const elapsed = parsedStart
    ? Math.max(0, (new Date().getFullYear() - parsedStart.year) * 12 + new Date().getMonth() + 1 - parsedStart.month)
    : 0
  const fillPaidIn = () =>
    setPaidIn(Object.fromEntries(holders.map((h) => [h.userId, String(rotatedPerShare * h.shares * elapsed)])))
  const fillReceived = () =>
    setTurns((all) =>
      all.map((turn) => ({
        ...turn,
        openingReceivedAmount: monthOf(start, turn.monthIndex) < currentMonth() ? payout * turn.shares : turn.openingReceivedAmount,
      })),
    )

  return (
    <Dialog open={open} onClose={loading ? undefined : close} fullWidth maxWidth="md">
      <DialogTitle>{importing ? t('rotation.share.importTitle') : t('rotation.share.createTitle')}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <Alert severity="info">{importing ? t('rotation.share.importHelp') : t('rotation.share.createHelp')}</Alert>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label={t('rotation.share.startMonth')}
              type="month"
              required
              value={start}
              onChange={(e) => setStart(e.target.value)}
              error={submitted && startProblem}
              helperText={importing ? t('rotation.share.startHintImport') : t('rotation.share.startHint', { month: minMonth })}
              slotProps={{ inputLabel: { shrink: true } }}
              fullWidth
            />
            <TextField
              label={t('rotation.share.months')}
              value={months}
              onChange={(e) => setMonths(e.target.value)}
              helperText={t('rotation.share.monthsHint', { shares: totalShares })}
              slotProps={{ htmlInput: { inputMode: 'numeric' } }}
              fullWidth
            />
            <TextField
              select
              label={t('rotation.share.turnMode')}
              value={turnMode}
              onChange={(e) => setTurnMode(e.target.value as RotationTurnMode)}
              helperText={t(`rotation.share.turnModeHelp.${turnMode}`)}
              fullWidth
            >
              {TURN_MODES.map((m) => (
                <MenuItem key={m} value={m}>
                  {t(`rotation.share.turnModes.${m}`)}
                </MenuItem>
              ))}
            </TextField>
          </Stack>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            {model === 'HYBRID' ? (
              <TextField
                label={t('rotation.share.rotatedPerShare')}
                value={rotated}
                onChange={(e) => setRotated(e.target.value)}
                error={submitted && splitProblem}
                helperText={
                  rotatedPerShare > 0 && unit > rotatedPerShare
                    ? t('rotation.share.retainedPerShare', { amount: money(unit - rotatedPerShare), unit: money(unit) })
                    : t('rotation.share.rotatedPerShareHint', { unit: money(unit) })
                }
                slotProps={{ htmlInput: { inputMode: 'numeric' } }}
                fullWidth
              />
            ) : null}
            <MoneyField
              label={t('rotation.share.payoutPerShare')}
              value={perShare}
              onChange={(e) => setPerShare(e.target.value)}
              error={submitted && payoutProblem}
              helperText={t('rotation.share.payoutPerShareHint')}
              slotProps={{ htmlInput: { inputMode: 'numeric' } }}
              fullWidth
            />
          </Stack>

          <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', alignItems: 'center' }}>
            <Button variant="outlined" onClick={() => setTurns(autoArrange(holders, monthCount, turnMode))} disabled={holders.length === 0}>
              {t('rotation.share.autoArrange')}
            </Button>
            <Button
              startIcon={<AddIcon />}
              onClick={() =>
                setTurns((all) => [...all, { key: draftKey(), monthIndex: 1, memberUserId: holders[0]?.userId ?? '', shares: 1 }])
              }
              disabled={holders.length === 0}
            >
              {t('rotation.share.addTurn')}
            </Button>
            {importing ? (
              <Button onClick={fillReceived} disabled={!parsedStart || turns.length === 0}>
                {t('rotation.share.fillReceived')}
              </Button>
            ) : null}
            <Typography variant="body2" color="text.secondary">
              {t('rotation.share.sharesPlaced', { placed: check.sharesPlaced, total: totalShares })}
            </Typography>
          </Stack>

          {turns.length > 0 ? (
            <Box sx={{ overflowX: 'auto', border: '1px solid', borderColor: 'divider', borderRadius: 1 }}>
              <Table size="small" data-testid="share-turns">
                <TableHead>
                  <TableRow>
                    <TableCell>{t('rotation.share.month')}</TableCell>
                    <TableCell>{t('rotation.share.member')}</TableCell>
                    <TableCell>{t('rotation.share.shares')}</TableCell>
                    <TableCell align="right">{t('rotation.share.payout')}</TableCell>
                    {importing ? <TableCell>{t('rotation.share.received')}</TableCell> : null}
                    <TableCell />
                  </TableRow>
                </TableHead>
                <TableBody>
                  {[...turns]
                    .sort((a, b) => a.monthIndex - b.monthIndex)
                    .map((turn) => (
                      <TableRow key={turn.key}>
                        <TableCell sx={{ minWidth: 130 }}>
                          <TextField
                            select
                            size="small"
                            value={turn.monthIndex}
                            onChange={(e) => update(turn.key, { monthIndex: Number(e.target.value) })}
                            fullWidth
                          >
                            {Array.from({ length: monthCount }, (_, i) => i + 1).map((m) => (
                              <MenuItem key={m} value={m}>
                                {parsedStart ? monthOf(start, m) : `#${m}`}
                              </MenuItem>
                            ))}
                          </TextField>
                        </TableCell>
                        <TableCell sx={{ minWidth: 180 }}>
                          <TextField
                            select
                            size="small"
                            value={turn.memberUserId}
                            onChange={(e) => update(turn.key, { memberUserId: e.target.value })}
                            fullWidth
                          >
                            {holders.map((h) => (
                              <MenuItem key={h.userId} value={h.userId}>
                                {h.label} ({h.shares})
                              </MenuItem>
                            ))}
                          </TextField>
                        </TableCell>
                        <TableCell sx={{ width: 90 }}>
                          <TextField
                            size="small"
                            value={turn.shares}
                            onChange={(e) => update(turn.key, { shares: Math.max(1, Number(e.target.value) || 1) })}
                            slotProps={{ htmlInput: { inputMode: 'numeric' } }}
                          />
                        </TableCell>
                        <TableCell align="right">{money(payout * turn.shares)}</TableCell>
                        {importing ? (
                          <TableCell sx={{ width: 140 }}>
                            <MoneyField
                              size="small"
                              value={turn.openingReceivedAmount ?? ''}
                              onChange={(e) =>
                                update(turn.key, {
                                  openingReceivedAmount: e.target.value ? Number(e.target.value.replace(/[,\s]/g, '')) : undefined,
                                })
                              }
                              slotProps={{ htmlInput: { inputMode: 'numeric' } }}
                            />
                          </TableCell>
                        ) : null}
                        <TableCell>
                          <IconButton
                            size="small"
                            aria-label={t('rotation.share.removeTurn')}
                            onClick={() => setTurns((all) => all.filter((x) => x.key !== turn.key))}
                          >
                            <DeleteOutlinedIcon fontSize="small" />
                          </IconButton>
                        </TableCell>
                      </TableRow>
                    ))}
                </TableBody>
              </Table>
            </Box>
          ) : (
            <Typography variant="body2" color="text.secondary">
              {t('rotation.share.noTurns')}
            </Typography>
          )}

          {submitted && check.problems.length > 0 ? (
            <Alert severity="error">
              {check.problems.slice(0, 5).map((p, i) => (
                <div key={i}>
                  {t(`rotation.share.problem.${p.code}`, {
                    month: 'month' in p && parsedStart ? monthOf(start, p.month) : '',
                    name: 'userId' in p ? names.get(p.userId) ?? '' : '',
                    held: 'held' in p ? p.held : '',
                    placed: 'placed' in p ? p.placed : '',
                  })}
                </div>
              ))}
            </Alert>
          ) : null}
          {check.unplaced.length > 0 && turns.length > 0 ? (
            <Alert severity="warning">
              {t('rotation.share.unplaced', {
                list: check.unplaced.map((u) => `${names.get(u.userId) ?? ''} (${u.missing})`).join(', '),
              })}
            </Alert>
          ) : null}
          {rotatedPerShare > 0 && payout > 0 && turns.length > 0 ? (
            <Alert severity={collections === payouts ? 'success' : 'warning'} data-testid="share-funding">
              {t('rotation.share.funding', { collections: money(collections), payouts: money(payouts) })}
            </Alert>
          ) : null}

          {importing ? (
            <Box>
              <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1 }}>
                <Typography variant="subtitle1">{t('rotation.share.paidInTitle')}</Typography>
                <Button size="small" onClick={fillPaidIn} disabled={!parsedStart || !(rotatedPerShare > 0)}>
                  {t('rotation.share.fillPaidIn', { months: elapsed })}
                </Button>
              </Stack>
              <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
                {t('rotation.share.paidInHelp')}
              </Typography>
              <Stack spacing={1}>
                {holders.map((h) => (
                  <Stack key={h.userId} direction="row" spacing={2} sx={{ alignItems: 'center' }}>
                    <Typography sx={{ flex: 1 }}>
                      {h.label} ({h.shares})
                    </Typography>
                    <MoneyField
                      size="small"
                      label={t('rotation.share.paidIn')}
                      value={paidIn[h.userId] ?? ''}
                      onChange={(e) => setPaidIn((all) => ({ ...all, [h.userId]: e.target.value }))}
                      slotProps={{ htmlInput: { inputMode: 'numeric' } }}
                    />
                  </Stack>
                ))}
              </Stack>
              <MoneyField
                sx={{ mt: 2 }}
                label={t('rotation.share.cashAtCutover')}
                helperText={t('rotation.share.cashAtCutoverHint')}
                value={cash}
                onChange={(e) => setCash(e.target.value)}
                slotProps={{ htmlInput: { inputMode: 'numeric' } }}
                fullWidth
              />
            </Box>
          ) : null}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={close} disabled={loading}>
          {t('common.cancel')}
        </Button>
        <Button variant="contained" onClick={submit} disabled={loading}>
          {importing ? t('rotation.share.import') : t('rotation.share.create')}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
