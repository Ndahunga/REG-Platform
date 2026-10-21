import SaveIcon from '@mui/icons-material/Save'
import {
  Alert,
  Box,
  Chip,
  Paper,
  MenuItem,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Typography,
  useMediaQuery,
  useTheme,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import dayjs from 'dayjs'
import { useSnackbar } from 'notistack'
import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { getErrorMessage } from '@/shared/api/client'
import {
  fetchContributionPeriod,
  saveContributionPeriod,
} from '@/shared/api/contributions'
import { ConfirmDialog } from '@/shared/components/ConfirmDialog'
import { EmptyState } from '@/shared/components/EmptyState'
import { ErrorState } from '@/shared/components/ErrorState'
import { FinancialActionButton } from '@/shared/components/FinancialActionButton'
import { LoadingState } from '@/shared/components/LoadingState'
import type { ContributionPeriodLine } from '@/shared/types/contribution'
import { formatMoney } from '@/shared/utils/formatMoney'
import { ObligationDetails } from './ObligationDetails'
import { todayInKigaliIso } from '@/shared/utils/rwandaCooperative'
import {
  computeOutstandingAmount,
  contributionEffectivePaid,
  contributionLineLock,
  contributionLineName,
  contributionStatusColor,
  deriveContributionStatus,
  hasContributionLineErrors,
  isContributionLineDirty,
  validateContributionLine,
  type ContributionLineInput,
  type ContributionLineErrors,
} from './contributionHelpers'

interface EditableLine extends ContributionPeriodLine {
  paidAmountInput: string
  paymentDateInput: string
  paymentReferenceInput: string
  notesInput: string
}

function toEditable(line: ContributionPeriodLine): EditableLine {
  return {
    ...line,
    paidAmountInput: String(line.paidAmount ?? '0'),
    paymentDateInput: line.paymentDate ?? '',
    paymentReferenceInput: line.paymentReference ?? '',
    notesInput: line.notes ?? '',
  }
}

type RowErrors = Record<string, ContributionLineErrors>

const inputsOf = (line: EditableLine): ContributionLineInput => ({
  paidAmountInput: line.paidAmountInput,
  paymentDateInput: line.paymentDateInput,
  paymentReferenceInput: line.paymentReferenceInput,
  notesInput: line.notesInput,
})

const isLocked = (line: EditableLine) => contributionLineLock(line) !== null

const AMOUNT_MESSAGE_KEY = {
  required: 'amountInvalid',
  invalid: 'amountInvalid',
  precision: 'amountInvalid',
  notPositive: 'amountInvalid',
  belowMinimum: 'amountInvalid',
  tooLarge: 'amountTooLarge',
  negative: 'nonNegativeAmount',
} as const

interface MonthlyEntryPanelProps {
  cooperativeId: string
  canWrite: boolean
}

export function MonthlyEntryPanel({ cooperativeId, canWrite }: MonthlyEntryPanelProps) {
  const { t } = useTranslation()
  const theme = useTheme()
  const isMobile = useMediaQuery(theme.breakpoints.down('md'))
  const queryClient = useQueryClient()
  const { enqueueSnackbar } = useSnackbar()

  const now = dayjs()
  const [year, setYear] = useState(now.year())
  const [month, setMonth] = useState(now.month() + 1)
  const [lines, setLines] = useState<EditableLine[]>([])
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [rowErrors, setRowErrors] = useState<RowErrors>({})
  // The values each row was loaded with: a row is only saved when it differs from this snapshot.
  const [originals, setOriginals] = useState<Record<string, ContributionLineInput>>({})
  // The changed rows the user confirmed; exactly these are sent.
  const [pendingLines, setPendingLines] = useState<EditableLine[]>([])
  const [focusToken, setFocusToken] = useState(0)
  const todayIso = todayInKigaliIso()

  const query = useQuery({
    queryKey: ['contributions', 'period', cooperativeId, year, month],
    queryFn: () => fetchContributionPeriod(cooperativeId, year, month),
    enabled: Boolean(cooperativeId),
  })

  useEffect(() => {
    if (query.data?.lines) {
      const loaded = query.data.lines.map(toEditable)
      setLines(loaded)
      setOriginals(Object.fromEntries(loaded.map((line) => [line.memberUserId, inputsOf(line)])))
      setRowErrors({})
    }
  }, [query.data])

  // After an invalid save attempt, move focus to the first invalid field.
  useEffect(() => {
    if (focusToken === 0) return
    const target = document.querySelector<HTMLElement>(
      '[data-contribution-row][data-invalid="true"] [aria-invalid="true"]',
    )
    target?.focus()
    target?.scrollIntoView?.({ block: 'center' })
  }, [focusToken])

  const saveMutation = useMutation({
    mutationFn: (toSave: EditableLine[]) =>
      saveContributionPeriod(cooperativeId, year, month, {
        lines: toSave.map((line) => ({
          memberUserId: line.memberUserId,
          paidAmount: line.paidAmountInput.trim() || '0',
          paymentDate: line.paymentDateInput.trim() || null,
          paymentReference: line.paymentReferenceInput.trim() || null,
          notes: line.notesInput.trim() || null,
        })),
      }),
    onSuccess: (_result, toSave) => {
      enqueueSnackbar(t('contributions.saveSuccess'), { variant: 'success' })
      setConfirmOpen(false)
      // The saved rows are the new baseline right away, so they are no longer "changed" even before the
      // refetch below replaces the grid.
      setOriginals((prev) => ({
        ...prev,
        ...Object.fromEntries(toSave.map((line) => [line.memberUserId, inputsOf(line)])),
      }))
      setPendingLines([])
      void queryClient.invalidateQueries({ queryKey: ['contributions', cooperativeId] })
      void queryClient.invalidateQueries({
        queryKey: ['contributions', 'period', cooperativeId, year, month],
      })
      void queryClient.invalidateQueries({ queryKey: ['dashboard', cooperativeId] })
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const yearOptions = useMemo(() => {
    const current = now.year()
    return Array.from({ length: 8 }, (_, i) => current - 3 + i)
  }, [now])

  const updateLine = (memberUserId: string, patch: Partial<EditableLine>) => {
    const current = lines.find((line) => line.memberUserId === memberUserId)
    setLines((prev) =>
      prev.map((line) => (line.memberUserId === memberUserId ? { ...line, ...patch } : line)),
    )
    // Once a row has been flagged, re-check it live so its errors clear as soon as it is fixed.
    if (current && rowErrors[memberUserId]) {
      const next = validateContributionLine({ ...current, ...patch }, todayIso)
      setRowErrors((prev) => {
        const copy = { ...prev }
        if (hasContributionLineErrors(next)) copy[memberUserId] = next
        else delete copy[memberUserId]
        return copy
      })
    }
  }

  const handleSaveClick = () => {
    // Only rows the user actually changed are validated and sent. Locked rows (waived, cancelled, awaiting
    // review) and untouched rows are never part of the request.
    const changed = lines.filter((line) => {
      const original = originals[line.memberUserId]
      return !isLocked(line) && original != null && isContributionLineDirty(original, inputsOf(line))
    })
    if (changed.length === 0) {
      setRowErrors({})
      enqueueSnackbar(t('contributions.noChanges'), { variant: 'info' })
      return
    }
    const errors: RowErrors = {}
    for (const line of changed) {
      const lineErrors = validateContributionLine(line, todayIso)
      if (hasContributionLineErrors(lineErrors)) errors[line.memberUserId] = lineErrors
    }
    setRowErrors(errors)
    if (Object.keys(errors).length > 0) {
      setFocusToken((token) => token + 1)
      return
    }
    setPendingLines(changed)
    setConfirmOpen(true)
  }

  const fieldMessages = (line: EditableLine) => {
    const e = rowErrors[line.memberUserId]
    return {
      paidAmount: e?.paidAmount
        ? t(`contributions.validation.${AMOUNT_MESSAGE_KEY[e.paidAmount]}`)
        : undefined,
      paymentDate: e?.paymentDate
        ? t(e.paymentDate === 'future' ? 'contributions.validation.dateFuture' : 'contributions.validation.dateInvalid')
        : undefined,
      paymentReference: e?.paymentReference ? t('contributions.validation.referenceTooLong') : undefined,
      notes: e?.notes ? t('contributions.validation.notesTooLong') : undefined,
    }
  }

  const memberLabel = (line: EditableLine) =>
    contributionLineName(line) || t('contributions.unnamedMember')

  if (query.isLoading) return <LoadingState variant="skeleton" rows={5} />
  if (query.isError) {
    return (
      <ErrorState
        message={getErrorMessage(query.error)}
        onRetry={() => void query.refetch()}
      />
    )
  }

  if (!lines.length) {
    return (
      <EmptyState
        title={t('contributions.periodEmptyTitle')}
        description={t('contributions.periodEmptyDescription')}
      />
    )
  }

  const invalidLines = lines.filter((line) => rowErrors[line.memberUserId])

  const renderFields = (line: EditableLine) => {
    const outstanding = computeOutstandingAmount(line.expectedAmount, contributionEffectivePaid(line.paidAmountInput))
    const status = deriveContributionStatus(
      line.expectedAmount,
      contributionEffectivePaid(line.paidAmountInput),
      line.status,
    )
    const messages = fieldMessages(line)

    return (
      <Stack spacing={1.5}>
        <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', alignItems: 'center' }}>
          <Typography
            variant="subtitle1"
            sx={{ fontWeight: 600, flex: 1 }}
            data-testid="contribution-member-name"
          >
            {memberLabel(line)}
          </Typography>
          <Chip
            size="small"
            color={contributionStatusColor(status)}
            label={t(`contributions.status.${status}`, { defaultValue: status })}
          />
        </Stack>
        {isLocked(line) ? (
          <Typography variant="caption" color="text.secondary" data-testid="contribution-row-locked">
            {t(`contributions.lock.${contributionLineLock(line)}`, {
              status: t(`contributions.status.${line.status}`, { defaultValue: String(line.status) }),
            })}
          </Typography>
        ) : null}
        <Typography variant="body2" color="text.secondary">
          {t('contributions.fields.expected')}: {formatMoney(line.expectedAmount)}
        </Typography>
        <Typography variant="body2" color="text.secondary">
          {t('contributions.fields.outstanding')}: {formatMoney(outstanding)}
        </Typography>
        <ObligationDetails fields={line} />
        {canWrite && !isLocked(line) ? (
          <>
            <TextField
              size="small"
              label={t('contributions.fields.paid')}
              value={line.paidAmountInput}
              onChange={(e) => updateLine(line.memberUserId, { paidAmountInput: e.target.value })}
              error={Boolean(messages.paidAmount)}
              helperText={messages.paidAmount}
              slotProps={{ htmlInput: { inputMode: 'decimal' } }}
            />
            <TextField
              size="small"
              type="date"
              label={t('contributions.fields.paymentDate')}
              value={line.paymentDateInput}
              onChange={(e) => updateLine(line.memberUserId, { paymentDateInput: e.target.value })}
              error={Boolean(messages.paymentDate)}
              helperText={messages.paymentDate}
              slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: todayIso } }}
            />
            <TextField
              size="small"
              label={t('contributions.fields.reference')}
              value={line.paymentReferenceInput}
              onChange={(e) =>
                updateLine(line.memberUserId, { paymentReferenceInput: e.target.value })
              }
              error={Boolean(messages.paymentReference)}
              helperText={messages.paymentReference}
            />
            <TextField
              size="small"
              label={t('contributions.fields.notes')}
              value={line.notesInput}
              onChange={(e) => updateLine(line.memberUserId, { notesInput: e.target.value })}
              error={Boolean(messages.notes)}
              helperText={messages.notes}
              multiline
              minRows={1}
            />
          </>
        ) : (
          <>
            <Typography variant="body2">
              {t('contributions.fields.paid')}: {formatMoney(line.paidAmount)}
            </Typography>
            <Typography variant="body2">
              {t('contributions.fields.paymentDate')}: {line.paymentDate || '—'}
            </Typography>
            <Typography variant="body2">
              {t('contributions.fields.reference')}: {line.paymentReference || '—'}
            </Typography>
          </>
        )}
      </Stack>
    )
  }

  return (
    <Box>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={1.5}
        sx={{ mb: 2, alignItems: { sm: 'center' } }}
      >
        <TextField
          select
          size="small"
          label={t('contributions.fields.month')}
          value={month}
          onChange={(e) => setMonth(Number(e.target.value))}
          sx={{ minWidth: 140 }}
        >
          {Array.from({ length: 12 }, (_, i) => i + 1).map((m) => (
            <MenuItem key={m} value={m}>
              {dayjs().month(m - 1).format('MMMM')}
            </MenuItem>
          ))}
        </TextField>
        <TextField
          select
          size="small"
          label={t('contributions.fields.year')}
          value={year}
          onChange={(e) => setYear(Number(e.target.value))}
          sx={{ minWidth: 120 }}
        >
          {yearOptions.map((y) => (
            <MenuItem key={y} value={y}>
              {y}
            </MenuItem>
          ))}
        </TextField>
        {canWrite ? (
          <FinancialActionButton
            variant="contained"
            startIcon={<SaveIcon />}
            onClick={handleSaveClick}
            disabled={saveMutation.isPending}
          >
            {t('contributions.savePeriod')}
          </FinancialActionButton>
        ) : null}
      </Stack>

      {invalidLines.length > 0 ? (
        <Alert severity="error" sx={{ mb: 1.5 }} data-testid="contribution-validation-summary">
          {t('contributions.validation.rowsInvalid', {
            rows: invalidLines.length,
            member: memberLabel(invalidLines[0]),
          })}
        </Alert>
      ) : null}

      {isMobile ? (
        <Stack spacing={1.5}>
          {lines.map((line) => (
            <Paper
              key={line.memberUserId}
              elevation={0}
              data-contribution-row
              data-locked={isLocked(line) ? 'true' : 'false'}
              data-invalid={rowErrors[line.memberUserId] ? 'true' : 'false'}
              sx={{ p: 2, border: '1px solid', borderColor: 'divider' }}
            >
              {renderFields(line)}
            </Paper>
          ))}
        </Stack>
      ) : (
        <TableContainer
          component={Paper}
          elevation={0}
          sx={{ border: '1px solid', borderColor: 'divider' }}
        >
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>{t('contributions.fields.member')}</TableCell>
                <TableCell>{t('contributions.fields.expected')}</TableCell>
                <TableCell>{t('contributions.fields.paid')}</TableCell>
                <TableCell>{t('contributions.fields.outstanding')}</TableCell>
                <TableCell>{t('contributions.fields.status')}</TableCell>
                <TableCell>{t('contributions.fields.paymentDate')}</TableCell>
                <TableCell>{t('contributions.fields.reference')}</TableCell>
                <TableCell>{t('contributions.fields.notes')}</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {lines.map((line) => {
                const outstanding = computeOutstandingAmount(
                  line.expectedAmount,
                  contributionEffectivePaid(line.paidAmountInput),
                )
                const status = deriveContributionStatus(
                  line.expectedAmount,
                  contributionEffectivePaid(line.paidAmountInput),
                  line.status,
                )
                const messages = fieldMessages(line)
                const name = memberLabel(line)
                return (
                  <TableRow
                    key={line.memberUserId}
                    data-contribution-row
                    data-locked={isLocked(line) ? 'true' : 'false'}
                    data-invalid={rowErrors[line.memberUserId] ? 'true' : 'false'}
                  >
                    <TableCell>
                      <Typography
                        variant="body2"
                        sx={{ fontWeight: 600 }}
                        data-testid="contribution-member-name"
                      >
                        {name}
                      </Typography>
                      {isLocked(line) ? (
                        <Typography variant="caption" color="text.secondary" data-testid="contribution-row-locked">
                          {t(`contributions.lock.${contributionLineLock(line)}`, {
                            status: t(`contributions.status.${line.status}`, { defaultValue: String(line.status) }),
                          })}
                        </Typography>
                      ) : null}
                      {line.username && line.username !== name ? (
                        <Typography variant="caption" color="text.secondary">
                          {line.username}
                        </Typography>
                      ) : null}
                      <ObligationDetails fields={line} />
                    </TableCell>
                    <TableCell>{formatMoney(line.expectedAmount)}</TableCell>
                    <TableCell sx={{ minWidth: 110 }}>
                      {canWrite && !isLocked(line) ? (
                        <TextField
                          size="small"
                          value={line.paidAmountInput}
                          onChange={(e) =>
                            updateLine(line.memberUserId, { paidAmountInput: e.target.value })
                          }
                          error={Boolean(messages.paidAmount)}
                          helperText={messages.paidAmount}
                          slotProps={{
                            htmlInput: { inputMode: 'decimal', 'aria-label': `${t('contributions.fields.paid')} ${name}` },
                          }}
                        />
                      ) : (
                        formatMoney(line.paidAmount)
                      )}
                    </TableCell>
                    <TableCell>{formatMoney(outstanding)}</TableCell>
                    <TableCell>
                      <Chip
                        size="small"
                        color={contributionStatusColor(status)}
                        label={t(`contributions.status.${status}`, { defaultValue: status })}
                      />
                    </TableCell>
                    <TableCell sx={{ minWidth: 140 }}>
                      {canWrite && !isLocked(line) ? (
                        <TextField
                          size="small"
                          type="date"
                          value={line.paymentDateInput}
                          onChange={(e) =>
                            updateLine(line.memberUserId, { paymentDateInput: e.target.value })
                          }
                          error={Boolean(messages.paymentDate)}
                          helperText={messages.paymentDate}
                          slotProps={{
                            inputLabel: { shrink: true },
                            htmlInput: { max: todayIso, 'aria-label': `${t('contributions.fields.paymentDate')} ${name}` },
                          }}
                        />
                      ) : (
                        line.paymentDate || '—'
                      )}
                    </TableCell>
                    <TableCell sx={{ minWidth: 120 }}>
                      {canWrite && !isLocked(line) ? (
                        <TextField
                          size="small"
                          value={line.paymentReferenceInput}
                          onChange={(e) =>
                            updateLine(line.memberUserId, {
                              paymentReferenceInput: e.target.value,
                            })
                          }
                          error={Boolean(messages.paymentReference)}
                          helperText={messages.paymentReference}
                          slotProps={{ htmlInput: { 'aria-label': `${t('contributions.fields.reference')} ${name}` } }}
                        />
                      ) : (
                        line.paymentReference || '—'
                      )}
                    </TableCell>
                    <TableCell sx={{ minWidth: 140 }}>
                      {canWrite && !isLocked(line) ? (
                        <TextField
                          size="small"
                          value={line.notesInput}
                          onChange={(e) =>
                            updateLine(line.memberUserId, { notesInput: e.target.value })
                          }
                          error={Boolean(messages.notes)}
                          helperText={messages.notes}
                          slotProps={{ htmlInput: { 'aria-label': `${t('contributions.fields.notes')} ${name}` } }}
                        />
                      ) : (
                        line.notes || '—'
                      )}
                    </TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      <ConfirmDialog
        open={confirmOpen}
        title={t('contributions.confirmSaveTitle')}
        message={t('contributions.confirmSaveMessage', { count: pendingLines.length, month, year })}
        loading={saveMutation.isPending}
        onCancel={() => {
          setConfirmOpen(false)
          setPendingLines([])
        }}
        onConfirm={() => {
          if (!saveMutation.isPending && pendingLines.length > 0) saveMutation.mutate(pendingLines)
        }}
      />
    </Box>
  )
}
