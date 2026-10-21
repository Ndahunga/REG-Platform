import ArrowDownwardIcon from '@mui/icons-material/ArrowDownward'
import ArrowUpwardIcon from '@mui/icons-material/ArrowUpward'
import DeleteOutlinedIcon from '@mui/icons-material/DeleteOutlined'
import {
  Alert,
  Autocomplete,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  List,
  ListItem,
  ListItemText,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { fetchMembers } from '@/shared/api/members'
import type { Member } from '@/shared/types/member'
import { cycleMonths, firstSelectableMonth, memberLabel, newIdempotencyKey, parseMonthInput } from './rotationHelpers'

interface CycleCreateDialogProps {
  open: boolean
  cooperativeId: string
  loading?: boolean
  onClose: () => void
  /** `idempotencyKey` stays the same while this dialog is open, so a double click cannot create two drafts. */
  onSubmit: (payload: { startYear: number; startMonth: number; recipientUserIds: string[] }, idempotencyKey: string) => void
}

/**
 * Builds a cycle: a start month that has not begun and a manually ordered list of active members, one turn each. The
 * schedule (one recipient per month, in this order) is previewed before anything is saved.
 */
export function CycleCreateDialog({ open, cooperativeId, loading, onClose, onSubmit }: CycleCreateDialogProps) {
  const { t } = useTranslation()
  const minMonth = firstSelectableMonth()
  const [start, setStart] = useState(minMonth)
  const [order, setOrder] = useState<Member[]>([])
  const [submitted, setSubmitted] = useState(false)
  const keyRef = useRef<string>(newIdempotencyKey())

  const membersQuery = useQuery({
    queryKey: ['rotation', cooperativeId, 'members'],
    queryFn: () => fetchMembers(cooperativeId, { size: 500 }),
    enabled: open,
  })
  const available = useMemo(() => {
    const chosen = new Set(order.map((m) => m.userId))
    return (membersQuery.data?.content ?? []).filter((m) => m.membershipStatus === 'ACTIVE' && !chosen.has(m.userId))
  }, [membersQuery.data, order])

  const months = cycleMonths(start, order.length)
  const startProblem = !parseMonthInput(start) || start < minMonth
  const orderProblem = order.length === 0

  const close = () => {
    setOrder([])
    setStart(minMonth)
    setSubmitted(false)
    keyRef.current = newIdempotencyKey()
    onClose()
  }

  const move = (index: number, delta: number) => {
    const target = index + delta
    if (target < 0 || target >= order.length) return
    const next = [...order]
    ;[next[index], next[target]] = [next[target], next[index]]
    setOrder(next)
  }

  const submit = () => {
    setSubmitted(true)
    const parsed = parseMonthInput(start)
    if (startProblem || orderProblem || !parsed) return
    onSubmit(
      { startYear: parsed.year, startMonth: parsed.month, recipientUserIds: order.map((m) => m.userId) },
      keyRef.current,
    )
  }

  return (
    <Dialog open={open} onClose={loading ? undefined : close} fullWidth maxWidth="sm">
      <DialogTitle>{t('rotation.cycle.createTitle')}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <Alert severity="info">{t('rotation.cycle.createHelp')}</Alert>
          <TextField
            label={t('rotation.cycle.startMonth')}
            type="month"
            required
            value={start}
            onChange={(e) => setStart(e.target.value)}
            error={submitted && startProblem}
            helperText={
              submitted && startProblem
                ? t('rotation.cycle.startInvalid')
                : t('rotation.cycle.startHint', { month: minMonth })
            }
            slotProps={{ inputLabel: { shrink: true }, htmlInput: { min: minMonth } }}
          />
          <Autocomplete
            options={available}
            value={null}
            blurOnSelect
            clearOnBlur
            getOptionLabel={memberLabel}
            loading={membersQuery.isLoading}
            onChange={(_, member) => {
              if (member) setOrder((current) => [...current, member])
            }}
            renderInput={(params) => (
              <TextField
                {...params}
                label={t('rotation.cycle.addMember')}
                error={submitted && orderProblem}
                helperText={submitted && orderProblem ? t('rotation.cycle.orderRequired') : t('rotation.cycle.addMemberHint')}
              />
            )}
          />
          {order.length > 0 ? (
            <List dense data-testid="rotation-order" sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 1 }}>
              {order.map((member, index) => (
                <ListItem
                  key={member.userId}
                  secondaryAction={
                    <Stack direction="row">
                      <IconButton
                        size="small"
                        aria-label={t('rotation.cycle.moveUp', { name: memberLabel(member) })}
                        disabled={index === 0}
                        onClick={() => move(index, -1)}
                      >
                        <ArrowUpwardIcon fontSize="small" />
                      </IconButton>
                      <IconButton
                        size="small"
                        aria-label={t('rotation.cycle.moveDown', { name: memberLabel(member) })}
                        disabled={index === order.length - 1}
                        onClick={() => move(index, 1)}
                      >
                        <ArrowDownwardIcon fontSize="small" />
                      </IconButton>
                      <IconButton
                        size="small"
                        aria-label={t('rotation.cycle.remove', { name: memberLabel(member) })}
                        onClick={() => setOrder(order.filter((m) => m.userId !== member.userId))}
                      >
                        <DeleteOutlinedIcon fontSize="small" />
                      </IconButton>
                    </Stack>
                  }
                >
                  <ListItemText primary={`${index + 1}. ${memberLabel(member)}`} secondary={months[index] ?? ''} />
                </ListItem>
              ))}
            </List>
          ) : (
            <Typography variant="body2" color="text.secondary">
              {t('rotation.cycle.noneChosen')}
            </Typography>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={close} disabled={loading}>
          {t('common.cancel')}
        </Button>
        <Button variant="contained" onClick={submit} disabled={loading}>
          {t('rotation.cycle.create')}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
