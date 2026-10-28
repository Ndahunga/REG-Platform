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
  Tab,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Tabs,
  TextField,
  Typography,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import dayjs from 'dayjs'
import { useSnackbar } from 'notistack'
import { useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { LoadingState } from '@/shared/components/LoadingState'
import {
  EXPLAIN_CATEGORIES,
  confirmAllSuggestions,
  confirmLine,
  explainLine,
  fetchCandidates,
  fetchLinesByStatus,
  fetchReconciliation,
  fetchStatement,
  linkLine,
  rematch,
  undoLine,
  uploadStatement,
  verifyReconciledPayments,
  type LineStatus,
  type StatementLine,
  type VerifyResult,
} from '@/shared/api/reconciliation'
import { localizedApiError } from '@/shared/utils/localizedApiError'

const money = (v: number | string | null | undefined) => Math.round(Number(v ?? 0)).toLocaleString()
const STATUS_COLOR: Record<LineStatus, 'success' | 'info' | 'warning' | 'default' | 'error'> = {
  MATCHED: 'success',
  CONFIRMED: 'success',
  EXPLAINED: 'info',
  SUGGESTED: 'warning',
  UNMATCHED: 'error',
}

/**
 * Bank reconciliation: upload a statement, see each line matched with the app's records, and settle what was not linked
 * with confidence. Officers see it; the Accountant, the President and the Vice President change it.
 */
export function ReconciliationWorkspace({ cooperativeId }: { cooperativeId: string }) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const queryClient = useQueryClient()
  const [tab, setTab] = useState<'todo' | 'statements' | 'done'>('todo')
  const [statementId, setStatementId] = useState<string | null>(null)
  const [verifyResults, setVerifyResults] = useState<VerifyResult[] | null>(null)

  const overview = useQuery({ queryKey: ['reconciliation', cooperativeId], queryFn: () => fetchReconciliation(cooperativeId) })
  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['reconciliation', cooperativeId] })
  const onError = (error: unknown) =>
    enqueueSnackbar(localizedApiError(t, error, 'reconciliation.errors'), { variant: 'error' })

  const confirmAll = useMutation({
    mutationFn: () => confirmAllSuggestions(cooperativeId),
    onSuccess: (r) => {
      enqueueSnackbar(t('reconciliation.confirmedAll', { count: r.confirmed }), { variant: 'success' })
      refresh()
    },
    onError,
  })
  const again = useMutation({
    mutationFn: () => rematch(cooperativeId),
    onSuccess: () => {
      enqueueSnackbar(t('reconciliation.rematched'), { variant: 'success' })
      refresh()
    },
    onError,
  })
  const verify = useMutation({
    mutationFn: () => verifyReconciledPayments(cooperativeId),
    onSuccess: (r) => {
      setVerifyResults(r)
      refresh()
      void queryClient.invalidateQueries({ queryKey: ['payments'] })
    },
    onError,
  })

  if (overview.isLoading) return <LoadingState />
  if (overview.isError || !overview.data) {
    return <Alert severity="error">{localizedApiError(t, overview.error, 'reconciliation.errors')}</Alert>
  }
  const o = overview.data
  const count = (s: LineStatus) => o.lineCounts[s] ?? 0
  const busy = confirmAll.isPending || again.isPending || verify.isPending

  return (
    <Stack spacing={2}>
      <Box
        sx={{ display: 'grid', gap: 1.5, gridTemplateColumns: { xs: 'repeat(2, 1fr)', md: 'repeat(5, 1fr)' } }}
        data-testid="reconciliation-counts"
      >
        {(['MATCHED', 'CONFIRMED', 'SUGGESTED', 'UNMATCHED', 'EXPLAINED'] as LineStatus[]).map((s) => (
          <Paper key={s} elevation={0} sx={{ p: 1.5, border: '1px solid', borderColor: 'divider' }}>
            <Typography variant="caption" color="text.secondary">
              {t(`reconciliation.status.${s}`)}
            </Typography>
            <Typography variant="h6">{count(s)}</Typography>
            <Typography variant="caption" color="text.secondary">
              {money(o.lineAmounts[s])} RWF
            </Typography>
          </Paper>
        ))}
      </Box>

      {o.balance ? (
        <Alert severity={Math.round(Number(o.balance.difference)) === 0 ? 'success' : 'warning'}>
          {t('reconciliation.balanceCheck', {
            date: dayjs(o.balance.asAt).format('D MMM YYYY'),
            bank: money(o.balance.statementBalance),
            app: money(o.balance.appCash),
            difference: money(o.balance.difference),
          })}
        </Alert>
      ) : null}
      {o.recordsNotOnStatement > 0 ? (
        <Alert severity="info">{t('reconciliation.notOnStatementCount', { count: o.recordsNotOnStatement })}</Alert>
      ) : null}

      {o.canEdit ? <UploadCard cooperativeId={cooperativeId} onDone={refresh} /> : (
        <Alert severity="info">{t('reconciliation.viewOnly')}</Alert>
      )}

      {o.canEdit ? (
        <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
          <Button variant="contained" disabled={busy || count('SUGGESTED') === 0} onClick={() => confirmAll.mutate()}>
            {t('reconciliation.acceptAll', { count: count('SUGGESTED') })}
          </Button>
          <Button variant="outlined" disabled={busy} onClick={() => again.mutate()}>
            {t('reconciliation.matchAgain')}
          </Button>
          <Button variant="outlined" disabled={busy} onClick={() => verify.mutate()}>
            {t('reconciliation.verifyPayments')}
          </Button>
        </Stack>
      ) : null}
      {verifyResults ? (
        <Alert severity={verifyResults.every((r) => r.outcome === 'VERIFIED') ? 'success' : 'warning'} onClose={() => setVerifyResults(null)}>
          {t('reconciliation.verifyResult', {
            verified: verifyResults.filter((r) => r.outcome === 'VERIFIED').length,
            total: verifyResults.length,
          })}
          {verifyResults
            .filter((r) => r.outcome !== 'VERIFIED')
            .slice(0, 5)
            .map((r) => (
              <Typography key={r.paymentId} variant="caption" component="div">
                {r.message}
              </Typography>
            ))}
        </Alert>
      ) : null}

      <Tabs value={tab} onChange={(_, v) => setTab(v)} variant="scrollable" scrollButtons="auto">
        <Tab value="todo" label={t('reconciliation.tabs.todo', { count: count('SUGGESTED') + count('UNMATCHED') })} />
        <Tab value="statements" label={t('reconciliation.tabs.statements', { count: o.statements.length })} />
        <Tab value="done" label={t('reconciliation.tabs.done')} />
      </Tabs>

      {tab === 'todo' ? (
        <StatusLines cooperativeId={cooperativeId} statuses={['SUGGESTED', 'UNMATCHED']} canEdit={o.canEdit} onChange={refresh} />
      ) : null}
      {tab === 'done' ? (
        <StatusLines cooperativeId={cooperativeId} statuses={['MATCHED', 'CONFIRMED', 'EXPLAINED']} canEdit={o.canEdit} onChange={refresh} />
      ) : null}
      {tab === 'statements' ? (
        statementId ? (
          <StatementView
            cooperativeId={cooperativeId}
            statementId={statementId}
            canEdit={o.canEdit}
            onBack={() => setStatementId(null)}
            onChange={refresh}
          />
        ) : (
          <Paper elevation={0} sx={{ border: '1px solid', borderColor: 'divider', overflowX: 'auto' }}>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>{t('reconciliation.fields.file')}</TableCell>
                  <TableCell>{t('reconciliation.fields.period')}</TableCell>
                  <TableCell align="right">{t('reconciliation.fields.in')}</TableCell>
                  <TableCell align="right">{t('reconciliation.fields.out')}</TableCell>
                  <TableCell align="right">{t('reconciliation.fields.closing')}</TableCell>
                  <TableCell>{t('reconciliation.fields.lines')}</TableCell>
                  <TableCell>{t('reconciliation.fields.uploadedBy')}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {o.statements.map((s) => (
                  <TableRow key={s.id} hover sx={{ cursor: 'pointer' }} onClick={() => setStatementId(s.id)}>
                    <TableCell>
                      {s.originalFilename}
                      <Typography variant="caption" component="div" color="text.secondary">
                        {t(`reconciliation.source.${s.source}`)} {s.accountLabel ?? ''}
                      </Typography>
                    </TableCell>
                    <TableCell>
                      {s.periodFrom ? dayjs(s.periodFrom).format('D MMM YYYY') : '—'} – {s.periodTo ? dayjs(s.periodTo).format('D MMM YYYY') : '—'}
                    </TableCell>
                    <TableCell align="right">{money(s.totalIn)}</TableCell>
                    <TableCell align="right">{money(s.totalOut)}</TableCell>
                    <TableCell align="right">{s.closingBalance == null ? '—' : money(s.closingBalance)}</TableCell>
                    <TableCell>
                      {Object.entries(s.byStatus).map(([k, v]) => (
                        <Chip key={k} size="small" sx={{ mr: 0.5, mb: 0.5 }} color={STATUS_COLOR[k as LineStatus]} label={`${t(`reconciliation.status.${k}`)}: ${v}`} />
                      ))}
                    </TableCell>
                    <TableCell>
                      {s.uploadedBy}
                      <Typography variant="caption" component="div" color="text.secondary">
                        {dayjs(s.uploadedAt).format('YYYY-MM-DD HH:mm')}
                      </Typography>
                    </TableCell>
                  </TableRow>
                ))}
                {o.statements.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={7}>{t('reconciliation.noStatements')}</TableCell>
                  </TableRow>
                ) : null}
              </TableBody>
            </Table>
          </Paper>
        )
      ) : null}
    </Stack>
  )
}

function UploadCard({ cooperativeId, onDone }: { cooperativeId: string; onDone: () => void }) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const input = useRef<HTMLInputElement>(null)
  const [source, setSource] = useState<'BANK' | 'MOBILE_MONEY'>('BANK')
  const [label, setLabel] = useState('')
  const upload = useMutation({
    mutationFn: (file: File) => uploadStatement(cooperativeId, file, source, label.trim() || undefined),
    onSuccess: (s) => {
      enqueueSnackbar(t('reconciliation.uploaded', { lines: s.lineCount, fresh: s.newLines }), { variant: 'success' })
      onDone()
    },
    onError: (error) => enqueueSnackbar(localizedApiError(t, error, 'reconciliation.errors'), { variant: 'error' }),
  })
  const last = upload.data
  return (
    <Paper elevation={0} sx={{ p: 2, border: '1px solid', borderColor: 'divider' }}>
      <Typography variant="subtitle1" sx={{ fontWeight: 600 }} gutterBottom>
        {t('reconciliation.uploadTitle')}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
        {t('reconciliation.uploadHelp')}
      </Typography>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ alignItems: { sm: 'center' } }}>
        <TextField select size="small" label={t('reconciliation.fields.source')} value={source} onChange={(e) => setSource(e.target.value as 'BANK' | 'MOBILE_MONEY')} sx={{ minWidth: 180 }}>
          <MenuItem value="BANK">{t('reconciliation.source.BANK')}</MenuItem>
          <MenuItem value="MOBILE_MONEY">{t('reconciliation.source.MOBILE_MONEY')}</MenuItem>
        </TextField>
        <TextField size="small" label={t('reconciliation.fields.account')} value={label} onChange={(e) => setLabel(e.target.value)} />
        <Button startIcon={<UploadFileIcon />} variant="contained" disabled={upload.isPending} onClick={() => input.current?.click()}>
          {upload.isPending ? t('reconciliation.uploading') : t('reconciliation.upload')}
        </Button>
        <input
          ref={input}
          type="file"
          hidden
          accept=".pdf,.xlsx,.xls,.csv"
          onChange={(e) => {
            const f = e.target.files?.[0]
            if (f) upload.mutate(f)
            e.target.value = ''
          }}
        />
      </Stack>
      {last && last.warnings.length > 0 ? (
        <Alert severity="warning" sx={{ mt: 1.5 }}>
          {last.warnings.map((w) => (
            <div key={w}>{w}</div>
          ))}
        </Alert>
      ) : null}
    </Paper>
  )
}

function StatusLines({
  cooperativeId,
  statuses,
  canEdit,
  onChange,
}: {
  cooperativeId: string
  statuses: LineStatus[]
  canEdit: boolean
  onChange: () => void
}) {
  const results = useQuery({
    queryKey: ['reconciliation', cooperativeId, 'lines', statuses.join(',')],
    queryFn: async () => (await Promise.all(statuses.map((s) => fetchLinesByStatus(cooperativeId, s)))).flat(),
  })
  if (results.isLoading) return <LoadingState />
  const lines = [...(results.data ?? [])].sort((a, b) => a.date.localeCompare(b.date) || a.lineNumber - b.lineNumber)
  return <LinesTable cooperativeId={cooperativeId} lines={lines} canEdit={canEdit} onChange={onChange} />
}

function StatementView({
  cooperativeId,
  statementId,
  canEdit,
  onBack,
  onChange,
}: {
  cooperativeId: string
  statementId: string
  canEdit: boolean
  onBack: () => void
  onChange: () => void
}) {
  const { t } = useTranslation()
  const [filter, setFilter] = useState<'ALL' | LineStatus>('ALL')
  const detail = useQuery({
    queryKey: ['reconciliation', cooperativeId, 'statement', statementId],
    queryFn: () => fetchStatement(cooperativeId, statementId),
  })
  if (detail.isLoading || !detail.data) return <LoadingState />
  const d = detail.data
  const lines = filter === 'ALL' ? d.lines : d.lines.filter((l) => l.status === filter)
  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', alignItems: 'center' }}>
        <Button onClick={onBack}>{t('common.back')}</Button>
        <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
          {d.statement.originalFilename}
        </Typography>
        <TextField select size="small" value={filter} onChange={(e) => setFilter(e.target.value as 'ALL' | LineStatus)} sx={{ minWidth: 160 }}>
          <MenuItem value="ALL">{t('common.all')}</MenuItem>
          {(['MATCHED', 'CONFIRMED', 'SUGGESTED', 'UNMATCHED', 'EXPLAINED'] as LineStatus[]).map((s) => (
            <MenuItem key={s} value={s}>
              {t(`reconciliation.status.${s}`)}
            </MenuItem>
          ))}
        </TextField>
      </Stack>
      {d.statement.warnings.length > 0 ? (
        <Alert severity="warning">
          {d.statement.warnings.map((w) => (
            <div key={w}>{w}</div>
          ))}
        </Alert>
      ) : null}
      {d.balance ? (
        <Alert severity={Math.round(Number(d.balance.difference)) === 0 ? 'success' : 'warning'}>
          {t('reconciliation.balanceCheck', {
            date: dayjs(d.balance.asAt).format('D MMM YYYY'),
            bank: money(d.balance.statementBalance),
            app: money(d.balance.appCash),
            difference: money(d.balance.difference),
          })}
        </Alert>
      ) : null}
      <LinesTable cooperativeId={cooperativeId} lines={lines} canEdit={canEdit} onChange={() => { onChange(); void detail.refetch() }} />
      <Typography variant="subtitle2">{t('reconciliation.notOnStatementTitle', { count: d.notOnStatement.length })}</Typography>
      <Typography variant="body2" color="text.secondary">
        {t('reconciliation.notOnStatementHelp')}
      </Typography>
      <Paper elevation={0} sx={{ border: '1px solid', borderColor: 'divider', overflowX: 'auto' }}>
        <Table size="small">
          <TableBody>
            {d.notOnStatement.map((r) => (
              <TableRow key={`${r.kind}-${r.id}`}>
                <TableCell>{dayjs(r.date).format('D MMM YYYY')}</TableCell>
                <TableCell>{r.member || '—'}</TableCell>
                <TableCell>{r.label}</TableCell>
                <TableCell align="right">
                  {r.direction === 'IN' ? '+' : '−'}
                  {money(r.amount)}
                </TableCell>
                <TableCell>{r.status ?? ''}</TableCell>
              </TableRow>
            ))}
            {d.notOnStatement.length === 0 ? (
              <TableRow>
                <TableCell>{t('reconciliation.nothingMissing')}</TableCell>
              </TableRow>
            ) : null}
          </TableBody>
        </Table>
      </Paper>
    </Stack>
  )
}

function LinesTable({
  cooperativeId,
  lines,
  canEdit,
  onChange,
}: {
  cooperativeId: string
  lines: StatementLine[]
  canEdit: boolean
  onChange: () => void
}) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const queryClient = useQueryClient()
  const [linkFor, setLinkFor] = useState<StatementLine | null>(null)
  const [explainFor, setExplainFor] = useState<StatementLine | null>(null)
  const done = () => {
    onChange()
    void queryClient.invalidateQueries({ queryKey: ['reconciliation', cooperativeId] })
  }
  const onError = (error: unknown) => enqueueSnackbar(localizedApiError(t, error, 'reconciliation.errors'), { variant: 'error' })
  const confirm = useMutation({ mutationFn: (id: string) => confirmLine(cooperativeId, id), onSuccess: done, onError })
  const undo = useMutation({ mutationFn: (id: string) => undoLine(cooperativeId, id), onSuccess: done, onError })

  return (
    <>
      <Paper elevation={0} sx={{ border: '1px solid', borderColor: 'divider', overflowX: 'auto' }}>
        <Table size="small" data-testid="reconciliation-lines">
          <TableHead>
            <TableRow>
              <TableCell>{t('reconciliation.fields.date')}</TableCell>
              <TableCell>{t('reconciliation.fields.narration')}</TableCell>
              <TableCell align="right">{t('reconciliation.fields.amount')}</TableCell>
              <TableCell>{t('reconciliation.fields.status')}</TableCell>
              <TableCell>{t('reconciliation.fields.with')}</TableCell>
              {canEdit ? <TableCell>{t('reconciliation.fields.actions')}</TableCell> : null}
            </TableRow>
          </TableHead>
          <TableBody>
            {lines.map((l) => (
              <TableRow key={l.id}>
                <TableCell sx={{ whiteSpace: 'nowrap' }}>{dayjs(l.date).format('D MMM YYYY')}</TableCell>
                <TableCell sx={{ maxWidth: 360 }}>
                  <Typography variant="body2">{l.narration}</Typography>
                </TableCell>
                <TableCell align="right" sx={{ whiteSpace: 'nowrap', color: l.direction === 'IN' ? 'success.main' : 'error.main' }}>
                  {l.direction === 'IN' ? '+' : '−'}
                  {money(l.amount)}
                </TableCell>
                <TableCell>
                  <Chip size="small" color={STATUS_COLOR[l.status]} label={t(`reconciliation.status.${l.status}`)} />
                </TableCell>
                <TableCell sx={{ maxWidth: 320 }}>
                  {l.matches.map((m) => (
                    <Typography key={m.id} variant="caption" component="div">
                      {m.label} ({money(m.amount)}){m.how === 'AUTOMATIC' ? ` · ${t('reconciliation.automatic')}` : ''}
                    </Typography>
                  ))}
                  {l.status === 'SUGGESTED' && l.suggestion ? (
                    <Typography variant="caption" component="div" color="warning.main">
                      {t('reconciliation.suggested')}: {l.suggestion.kind === 'EXPLAIN' ? t(`reconciliation.categories.${l.suggestion.category}`) : l.suggestion.label}
                    </Typography>
                  ) : null}
                  {l.status === 'EXPLAINED' ? (
                    <Typography variant="caption" component="div">
                      {t(`reconciliation.categories.${l.category}`)}
                      {l.note ? `: ${l.note}` : ''}
                    </Typography>
                  ) : null}
                  {l.decidedBy ? (
                    <Typography variant="caption" component="div" color="text.secondary">
                      {l.decidedBy}
                    </Typography>
                  ) : null}
                </TableCell>
                {canEdit ? (
                  <TableCell sx={{ whiteSpace: 'nowrap' }}>
                    {l.status === 'SUGGESTED' ? (
                      <Button size="small" onClick={() => confirm.mutate(l.id)} disabled={confirm.isPending}>
                        {t('reconciliation.confirm')}
                      </Button>
                    ) : null}
                    {l.status === 'SUGGESTED' || l.status === 'UNMATCHED' ? (
                      <>
                        <Button size="small" onClick={() => setLinkFor(l)}>
                          {t('reconciliation.link')}
                        </Button>
                        <Button size="small" onClick={() => setExplainFor(l)}>
                          {t('reconciliation.explain')}
                        </Button>
                      </>
                    ) : (
                      <Button size="small" color="warning" onClick={() => undo.mutate(l.id)} disabled={undo.isPending}>
                        {t('reconciliation.undo')}
                      </Button>
                    )}
                  </TableCell>
                ) : null}
              </TableRow>
            ))}
            {lines.length === 0 ? (
              <TableRow>
                <TableCell colSpan={canEdit ? 6 : 5}>{t('reconciliation.noLines')}</TableCell>
              </TableRow>
            ) : null}
          </TableBody>
        </Table>
      </Paper>
      {linkFor ? (
        <LinkDialog cooperativeId={cooperativeId} line={linkFor} onClose={() => setLinkFor(null)} onDone={() => { setLinkFor(null); done() }} />
      ) : null}
      {explainFor ? (
        <ExplainDialog cooperativeId={cooperativeId} line={explainFor} onClose={() => setExplainFor(null)} onDone={() => { setExplainFor(null); done() }} />
      ) : null}
    </>
  )
}

function LinkDialog({ cooperativeId, line, onClose, onDone }: { cooperativeId: string; line: StatementLine; onClose: () => void; onDone: () => void }) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const [chosen, setChosen] = useState<Record<string, boolean>>({})
  const candidates = useQuery({
    queryKey: ['reconciliation', cooperativeId, 'candidates', line.id],
    queryFn: () => fetchCandidates(cooperativeId, line.id),
  })
  const picked = useMemo(() => (candidates.data ?? []).filter((c) => chosen[`${c.kind}-${c.id}`]), [candidates.data, chosen])
  const sum = picked.reduce((a, c) => a + Number(c.amount), 0)
  const link = useMutation({
    mutationFn: () =>
      linkLine(cooperativeId, line.id, {
        paymentIds: picked.filter((c) => c.kind === 'PAYMENT').map((c) => c.id),
        ledgerEntryIds: picked.filter((c) => c.kind === 'LEDGER').map((c) => c.id),
      }),
    onSuccess: onDone,
    onError: (error) => enqueueSnackbar(localizedApiError(t, error, 'reconciliation.errors'), { variant: 'error' }),
  })
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>{t('reconciliation.linkTitle')}</DialogTitle>
      <DialogContent>
        <Typography variant="body2" sx={{ mb: 1 }}>
          {dayjs(line.date).format('D MMM YYYY')} · {line.narration} · <b>{money(line.amount)}</b>
        </Typography>
        {candidates.isLoading ? <LoadingState /> : null}
        <Table size="small">
          <TableBody>
            {(candidates.data ?? []).map((c) => {
              const key = `${c.kind}-${c.id}`
              return (
                <TableRow key={key} hover onClick={() => setChosen((p) => ({ ...p, [key]: !p[key] }))} sx={{ cursor: 'pointer' }}>
                  <TableCell padding="checkbox">
                    <Checkbox checked={Boolean(chosen[key])} />
                  </TableCell>
                  <TableCell>{c.label}</TableCell>
                  <TableCell align="right">{money(c.amount)}</TableCell>
                </TableRow>
              )
            })}
            {candidates.data && candidates.data.length === 0 ? (
              <TableRow>
                <TableCell>{t('reconciliation.noCandidates')}</TableCell>
              </TableRow>
            ) : null}
          </TableBody>
        </Table>
        <Alert severity={Math.round(sum) === Math.round(Number(line.amount)) ? 'success' : 'info'} sx={{ mt: 1.5 }}>
          {t('reconciliation.linkSum', { chosen: money(sum), line: money(line.amount) })}
        </Alert>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose}>{t('common.cancel')}</Button>
        <Button variant="contained" disabled={link.isPending || picked.length === 0 || Math.round(sum * 100) !== Math.round(Number(line.amount) * 100)} onClick={() => link.mutate()}>
          {t('reconciliation.link')}
        </Button>
      </DialogActions>
    </Dialog>
  )
}

function ExplainDialog({ cooperativeId, line, onClose, onDone }: { cooperativeId: string; line: StatementLine; onClose: () => void; onDone: () => void }) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const [category, setCategory] = useState<string>(line.direction === 'OUT' ? 'BANK_CHARGE' : 'OTHER')
  const [note, setNote] = useState('')
  const explain = useMutation({
    mutationFn: () => explainLine(cooperativeId, line.id, { category, note: note.trim() || undefined }),
    onSuccess: onDone,
    onError: (error) => enqueueSnackbar(localizedApiError(t, error, 'reconciliation.errors'), { variant: 'error' }),
  })
  const needsNote = category === 'OTHER' || category === 'NOT_SCHEME_MONEY'
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{t('reconciliation.explainTitle')}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <Typography variant="body2">
            {dayjs(line.date).format('D MMM YYYY')} · {line.narration} · <b>{money(line.amount)}</b>
          </Typography>
          <TextField select label={t('reconciliation.fields.category')} value={category} onChange={(e) => setCategory(e.target.value)}>
            {EXPLAIN_CATEGORIES.map((c) => (
              <MenuItem key={c} value={c}>
                {t(`reconciliation.categories.${c}`)}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            label={t('reconciliation.fields.note')}
            value={note}
            onChange={(e) => setNote(e.target.value)}
            multiline
            minRows={2}
            helperText={needsNote ? t('reconciliation.noteRequired') : ' '}
            slotProps={{ htmlInput: { maxLength: 500 } }}
          />
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose}>{t('common.cancel')}</Button>
        <Button variant="contained" disabled={explain.isPending || (needsNote && note.trim().length < 5)} onClick={() => explain.mutate()}>
          {t('reconciliation.explain')}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
