import UploadFileIcon from '@mui/icons-material/UploadFile'
import {
  Alert,
  Box,
  Button,
  Checkbox,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import axios from 'axios'
import dayjs from 'dayjs'
import { useSnackbar } from 'notistack'
import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link as RouterLink } from 'react-router-dom'
import {
  EXPENSE_TYPES,
  approveExpense,
  fetchBankChargeLines,
  fetchExpenses,
  recordBankCharges,
  recordExpense,
  rejectExpense,
  type Expense,
} from '@/shared/api/expenses'
import { AuthenticatedFileLink } from '@/shared/components/AuthenticatedFileLink'
import { LoadingState } from '@/shared/components/LoadingState'
import { MoneyField } from '@/shared/components/MoneyField'
import { ROUTES } from '@/shared/constants/routes'
import { checkMoney } from '@/shared/utils/formValidation'
import { localizedApiError } from '@/shared/utils/localizedApiError'
import { todayInKigaliIso } from '@/shared/utils/rwandaCooperative'

const money = (v: number | string | null | undefined) => Math.round(Number(v ?? 0)).toLocaleString()
const STATUS_COLOR = { PENDING: 'warning', APPROVED: 'success', REJECTED: 'error' } as const
const CHECK_COLOR = { MATCH: 'success', MISMATCH: 'error', UNREADABLE: 'warning', STATEMENT_LINE: 'info' } as const

/**
 * Scheme expenses: record what the group spends with its supporting document (read and compared with the amount), record
 * bank charges straight from the uploaded statement, and approve or reject what is pending.
 */
export function ExpensesWorkspace({ cooperativeId }: { cooperativeId: string }) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const queryClient = useQueryClient()
  const [rejectFor, setRejectFor] = useState<Expense | null>(null)
  const overview = useQuery({ queryKey: ['expenses', cooperativeId], queryFn: () => fetchExpenses(cooperativeId) })
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['expenses', cooperativeId] })
    void queryClient.invalidateQueries({ queryKey: ['reconciliation', cooperativeId] })
  }
  const onError = (error: unknown) => enqueueSnackbar(localizedApiError(t, error, 'expenses.errors'), { variant: 'error' })
  const approve = useMutation({
    mutationFn: (id: string) => approveExpense(cooperativeId, id),
    onSuccess: () => {
      enqueueSnackbar(t('expenses.approved'), { variant: 'success' })
      refresh()
    },
    onError,
  })

  if (overview.isLoading) return <LoadingState />
  if (overview.isError || !overview.data) {
    return <Alert severity="error">{localizedApiError(t, overview.error, 'expenses.errors')}</Alert>
  }
  const o = overview.data

  return (
    <Stack spacing={2}>
      <Box sx={{ display: 'grid', gap: 1.5, gridTemplateColumns: { xs: '1fr', sm: 'repeat(3, 1fr)' } }}>
        <Card label={t('expenses.pending', { count: o.pendingCount })} value={`${money(o.pendingAmount)} RWF`} />
        <Card label={t('expenses.thisMonth')} value={`${money(o.approvedThisMonth)} RWF`} />
        <Card
          label={t('expenses.thisYear')}
          value={`${money(Object.values(o.byTypeThisYear).reduce((a, b) => a + Number(b), 0))} RWF`}
        />
      </Box>

      {o.canRecord ? <RecordCard cooperativeId={cooperativeId} onDone={refresh} /> : null}
      {o.canRecord ? <BankChargesCard cooperativeId={cooperativeId} count={o.bankChargeLinesToRecord} onDone={refresh} /> : null}

      <Paper elevation={0} sx={{ border: '1px solid', borderColor: 'divider', overflowX: 'auto' }}>
        <Table size="small" data-testid="expenses-table" sx={{ minWidth: 900 }}>
          <TableHead>
            <TableRow>
              <TableCell>{t('expenses.fields.date')}</TableCell>
              <TableCell>{t('expenses.fields.type')}</TableCell>
              <TableCell>{t('expenses.fields.description')}</TableCell>
              <TableCell align="right">{t('expenses.fields.amount')}</TableCell>
              <TableCell>{t('expenses.fields.document')}</TableCell>
              <TableCell>{t('expenses.fields.status')}</TableCell>
              <TableCell />
            </TableRow>
          </TableHead>
          <TableBody>
            {o.expenses.map((e) => (
              <TableRow key={e.id} sx={{ verticalAlign: 'top' }}>
                <TableCell sx={{ whiteSpace: 'nowrap' }}>{dayjs(e.date).format('D MMM YYYY')}</TableCell>
                <TableCell>{t(`expenses.types.${e.expenseType}`, { defaultValue: e.expenseType })}</TableCell>
                <TableCell sx={{ maxWidth: 320 }}>
                  <Typography variant="body2">{e.description}</Typography>
                  {e.recordedBy ? (
                    <Typography variant="caption" color="text.secondary" component="div">
                      {t('expenses.recordedBy', { name: e.recordedBy })}
                      {e.approvedBy ? ` · ${t('expenses.approvedBy', { name: e.approvedBy })}` : ''}
                    </Typography>
                  ) : null}
                  {e.rejectionReason ? (
                    <Typography variant="caption" color="error" component="div">
                      {e.rejectionReason}
                    </Typography>
                  ) : null}
                </TableCell>
                <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                  {money(e.amount)}
                </TableCell>
                <TableCell sx={{ maxWidth: 260 }}>
                  {e.evidenceCheck ? (
                    <Chip
                      size="small"
                      color={CHECK_COLOR[e.evidenceCheck]}
                      label={t(`expenses.check.${e.evidenceCheck}`, { amount: money(e.evidenceAmountRead) })}
                    />
                  ) : null}
                  {e.evidenceNote ? (
                    <Typography variant="caption" component="div" color="text.secondary">
                      {e.evidenceNote}
                    </Typography>
                  ) : null}
                  {e.evidenceKey ? (
                    <AuthenticatedFileLink storageKey={e.evidenceKey}>{e.evidenceFileName || t('expenses.viewDocument')}</AuthenticatedFileLink>
                  ) : null}
                </TableCell>
                <TableCell>
                  <Chip size="small" color={STATUS_COLOR[e.status]} label={t(`expenses.status.${e.status}`)} />
                </TableCell>
                <TableCell sx={{ whiteSpace: 'nowrap' }}>
                  {e.status === 'PENDING' && o.canApprove ? (
                    <>
                      <Button size="small" variant="contained" disabled={approve.isPending} onClick={() => approve.mutate(e.id)}>
                        {t('expenses.approve')}
                      </Button>
                      <Button size="small" color="error" onClick={() => setRejectFor(e)}>
                        {t('expenses.reject')}
                      </Button>
                    </>
                  ) : null}
                </TableCell>
              </TableRow>
            ))}
            {o.expenses.length === 0 ? (
              <TableRow>
                <TableCell colSpan={7}>{t('expenses.none')}</TableCell>
              </TableRow>
            ) : null}
          </TableBody>
        </Table>
      </Paper>
      {rejectFor ? (
        <RejectDialog
          cooperativeId={cooperativeId}
          expense={rejectFor}
          onClose={() => setRejectFor(null)}
          onDone={() => {
            setRejectFor(null)
            refresh()
          }}
        />
      ) : null}
    </Stack>
  )
}

function Card({ label, value }: { label: string; value: string }) {
  return (
    <Paper elevation={0} sx={{ p: 1.5, border: '1px solid', borderColor: 'divider' }}>
      <Typography variant="caption" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="h6">{value}</Typography>
    </Paper>
  )
}

function RecordCard({ cooperativeId, onDone }: { cooperativeId: string; onDone: () => void }) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const input = useRef<HTMLInputElement>(null)
  const [type, setType] = useState<string>('MEETING')
  const [amount, setAmount] = useState('')
  const [date, setDate] = useState(todayInKigaliIso())
  const [description, setDescription] = useState('')
  const [reference, setReference] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [mismatch, setMismatch] = useState<string | null>(null)
  const [explanation, setExplanation] = useState('')
  const reset = () => {
    setAmount('')
    setDescription('')
    setReference('')
    setFile(null)
    setMismatch(null)
    setExplanation('')
  }
  const save = useMutation({
    mutationFn: () =>
      recordExpense(cooperativeId, {
        file: file!,
        expenseType: type,
        amount,
        date,
        description: description.trim(),
        reference: reference.trim() || undefined,
        explanation: mismatch ? explanation.trim() : undefined,
      }),
    onSuccess: (e) => {
      enqueueSnackbar(t(e.evidenceCheck === 'MATCH' ? 'expenses.recordedMatch' : 'expenses.recorded'), { variant: 'success' })
      reset()
      onDone()
    },
    onError: (error) => {
      const data = axios.isAxiosError(error) ? (error.response?.data as { code?: string; message?: string } | undefined) : undefined
      if (data?.code === 'EVIDENCE_AMOUNT_MISMATCH') {
        setMismatch(data.message ?? '')
        return
      }
      enqueueSnackbar(localizedApiError(t, error, 'expenses.errors'), { variant: 'error' })
    },
  })
  const valid =
    checkMoney(amount, { min: '0.01' }) === null &&
    description.trim().length >= 3 &&
    Boolean(file) &&
    (!mismatch || explanation.trim().length >= 5)
  return (
    <Paper elevation={0} sx={{ p: 2, border: '1px solid', borderColor: 'divider' }}>
      <Typography variant="subtitle1" sx={{ fontWeight: 600 }} gutterBottom>
        {t('expenses.recordTitle')}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
        {t('expenses.recordHelp')}
      </Typography>
      <Box sx={{ display: 'grid', gap: 1.5, gridTemplateColumns: { xs: '1fr', md: 'repeat(4, 1fr)' } }}>
        <TextField select size="small" label={t('expenses.fields.type')} value={type} onChange={(e) => setType(e.target.value)}>
          {EXPENSE_TYPES.map((x) => (
            <MenuItem key={x} value={x}>
              {t(`expenses.types.${x}`)}
            </MenuItem>
          ))}
        </TextField>
        <MoneyField
          size="small"
          label={t('expenses.fields.amount')}
          value={amount}
          onChange={(e) => {
            setAmount(e.target.value)
            setMismatch(null)
          }}
        />
        <TextField
          size="small"
          type="date"
          label={t('expenses.fields.date')}
          value={date}
          onChange={(e) => setDate(e.target.value)}
          slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: todayInKigaliIso() } }}
        />
        <TextField size="small" label={t('expenses.fields.reference')} value={reference} onChange={(e) => setReference(e.target.value)} />
        <TextField
          size="small"
          label={t('expenses.fields.description')}
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          sx={{ gridColumn: { md: 'span 3' } }}
        />
        <Button variant="outlined" startIcon={<UploadFileIcon />} onClick={() => input.current?.click()}>
          {file ? file.name : t('expenses.attach')}
        </Button>
        <input
          ref={input}
          type="file"
          hidden
          accept=".pdf,.png,.jpg,.jpeg"
          onChange={(e) => {
            setFile(e.target.files?.[0] ?? null)
            setMismatch(null)
            e.target.value = ''
          }}
        />
      </Box>
      {mismatch ? (
        <Alert severity="warning" sx={{ mt: 1.5 }} data-testid="expense-mismatch">
          {mismatch}
          <TextField
            fullWidth
            size="small"
            sx={{ mt: 1 }}
            label={t('expenses.explanation')}
            value={explanation}
            onChange={(e) => setExplanation(e.target.value)}
          />
        </Alert>
      ) : null}
      <Stack direction="row" sx={{ mt: 1.5, justifyContent: 'flex-end' }}>
        <Button variant="contained" disabled={!valid || save.isPending} onClick={() => save.mutate()}>
          {save.isPending ? t('expenses.reading') : mismatch ? t('expenses.saveWithExplanation') : t('expenses.save')}
        </Button>
      </Stack>
    </Paper>
  )
}

function BankChargesCard({ cooperativeId, count, onDone }: { cooperativeId: string; count: number; onDone: () => void }) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const [chosen, setChosen] = useState<Record<string, boolean>>({})
  const lines = useQuery({
    queryKey: ['expenses', cooperativeId, 'bank-charge-lines', count],
    queryFn: () => fetchBankChargeLines(cooperativeId),
  })
  const picked = (lines.data ?? []).filter((l) => chosen[l.lineId])
  const save = useMutation({
    mutationFn: () => recordBankCharges(cooperativeId, picked.map((l) => l.lineId)),
    onSuccess: (r) => {
      enqueueSnackbar(t('expenses.chargesRecorded', { count: r.length }), { variant: 'success' })
      setChosen({})
      onDone()
    },
    onError: (error) => enqueueSnackbar(localizedApiError(t, error, 'expenses.errors'), { variant: 'error' }),
  })
  const all = lines.data ?? []
  return (
    <Paper elevation={0} sx={{ p: 2, border: '1px solid', borderColor: 'divider' }} data-testid="bank-charges">
      <Stack direction="row" spacing={1} useFlexGap sx={{ alignItems: 'center', flexWrap: 'wrap', mb: 1 }}>
        <Typography variant="subtitle1" sx={{ fontWeight: 600, flexGrow: 1 }}>
          {t('expenses.chargesTitle', { count: all.length })}
        </Typography>
        <Button size="small" component={RouterLink} to={ROUTES.reconciliation}>
          {t('expenses.uploadStatement')}
        </Button>
      </Stack>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
        {t('expenses.chargesHelp')}
      </Typography>
      {all.length > 0 ? (
        <>
          <Table size="small">
            <TableBody>
              {all.map((l) => (
                <TableRow
                  key={l.lineId}
                  hover
                  sx={{ cursor: 'pointer' }}
                  onClick={() => setChosen((p) => ({ ...p, [l.lineId]: !p[l.lineId] }))}
                >
                  <TableCell padding="checkbox">
                    <Checkbox checked={Boolean(chosen[l.lineId])} />
                  </TableCell>
                  <TableCell sx={{ whiteSpace: 'nowrap' }}>{dayjs(l.date).format('D MMM YYYY')}</TableCell>
                  <TableCell>{l.narration}</TableCell>
                  <TableCell align="right">{money(l.amount)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <Stack direction="row" spacing={1} sx={{ mt: 1, justifyContent: 'flex-end' }}>
            <Button
              size="small"
              onClick={() => setChosen(Object.fromEntries(all.map((l) => [l.lineId, true])))}
            >
              {t('expenses.selectAll')}
            </Button>
            <Button variant="contained" size="small" disabled={picked.length === 0 || save.isPending} onClick={() => save.mutate()}>
              {t('expenses.recordCharges', {
                count: picked.length,
                amount: money(picked.reduce((a, l) => a + Number(l.amount), 0)),
              })}
            </Button>
          </Stack>
        </>
      ) : (
        <Typography variant="body2">{t('expenses.noCharges')}</Typography>
      )}
    </Paper>
  )
}

function RejectDialog({
  cooperativeId,
  expense,
  onClose,
  onDone,
}: {
  cooperativeId: string
  expense: Expense
  onClose: () => void
  onDone: () => void
}) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const [reason, setReason] = useState('')
  const reject = useMutation({
    mutationFn: () => rejectExpense(cooperativeId, expense.id, reason.trim()),
    onSuccess: onDone,
    onError: (error) => enqueueSnackbar(localizedApiError(t, error, 'expenses.errors'), { variant: 'error' }),
  })
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{t('expenses.rejectTitle')}</DialogTitle>
      <DialogContent>
        <Typography variant="body2" sx={{ mb: 1.5 }}>
          {expense.description} · {money(expense.amount)}
        </Typography>
        <TextField fullWidth multiline minRows={2} label={t('expenses.rejectReason')} value={reason} onChange={(e) => setReason(e.target.value)} />
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose}>{t('common.cancel')}</Button>
        <Button color="error" variant="contained" disabled={reason.trim().length < 5 || reject.isPending} onClick={() => reject.mutate()}>
          {t('expenses.reject')}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
