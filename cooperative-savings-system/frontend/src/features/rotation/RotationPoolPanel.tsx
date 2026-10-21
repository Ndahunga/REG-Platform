import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useAppSelector } from '@/app/store/hooks'
import { ErrorNotice } from '@/features/payments/PaymentParts'
import { isPositiveAmount } from '@/features/payments/paymentHelpers'
import { getErrorMessage } from '@/shared/api/client'
import { fetchRotationPool, fetchRotationPoolEntries, recordRotationPoolEntry } from '@/shared/api/rotationPayouts'
import { ErrorState } from '@/shared/components/ErrorState'
import { FinancialActionButton } from '@/shared/components/FinancialActionButton'
import { LoadingState } from '@/shared/components/LoadingState'
import { ResponsiveTable, type TableColumn } from '@/shared/components/ResponsiveTable'
import { hasPermission } from '@/shared/types/auth'
import type { PoolEntryType, RotationPoolEntry } from '@/shared/types/rotationPayout'
import { formatMoney } from '@/shared/utils/formatMoney'

const TYPES: PoolEntryType[] = ['OPENING', 'TOP_UP', 'EXPENSE']

function newKey(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `pool-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

/**
 * The rotating pool, as the backend computes it: what was collected (verified), what was set aside beforehand, what
 * is available and what part of the set-aside reserve is already covering a payout. The pool is an earmark inside the
 * scheme's funds, never a cash movement, so recording an entry changes no balance.
 */
export function RotationPoolPanel({ cooperativeId }: { cooperativeId: string }) {
  const { t } = useTranslation()
  const user = useAppSelector((s) => s.auth.user)
  const canRecord = hasPermission(user, 'FUND_AUTHORIZE')
  const [recording, setRecording] = useState(false)
  const pool = useQuery({ queryKey: ['rotation', cooperativeId, 'pool'], queryFn: () => fetchRotationPool(cooperativeId) })
  const entries = useQuery({
    queryKey: ['rotation', cooperativeId, 'pool', 'entries'],
    queryFn: () => fetchRotationPoolEntries(cooperativeId),
  })

  if (pool.isLoading || entries.isLoading) return <LoadingState variant="skeleton" rows={3} />
  if (pool.isError || !pool.data) {
    return <ErrorState message={getErrorMessage(pool.error, t('errors.generic'))} onRetry={() => void pool.refetch()} />
  }
  const p = pool.data
  const metrics: { key: string; value: string | number }[] = [
    { key: 'collectedPool', value: p.collectedPool },
    { key: 'openingPool', value: p.openingPool },
    { key: 'otherReceipts', value: p.otherReceipts },
    { key: 'expenses', value: p.expenses },
    { key: 'previousPayouts', value: p.previousPayouts },
    { key: 'availablePool', value: p.availablePool },
    { key: 'unusedReserve', value: p.unusedReserve },
    { key: 'reserveInUse', value: p.reserveInUse },
  ]
  const columns: TableColumn<RotationPoolEntry>[] = [
    { id: 'date', label: t('rotation.pool.entryDate'), render: (e) => e.effectiveDate },
    { id: 'type', label: t('rotation.pool.entryType'), render: (e) => t(`rotation.pool.types.${e.type}`) },
    { id: 'amount', label: t('rotation.pool.entryAmount'), render: (e) => formatMoney(e.amount) },
    { id: 'reason', label: t('rotation.pool.entryReason'), render: (e) => e.reason, hideOnMobile: true },
  ]

  return (
    <Stack spacing={2} data-testid="rotation-pool">
      <Alert severity="info">{t('rotation.pool.explain')}</Alert>
      <Box sx={{ display: 'grid', gap: 1.5, gridTemplateColumns: { xs: '1fr 1fr', md: 'repeat(4, 1fr)' } }}>
        {metrics.map((m) => (
          <Card key={m.key} variant="outlined">
            <CardContent>
              <Typography variant="caption" color="text.secondary">
                {t(`rotation.pool.metrics.${m.key}`)}
              </Typography>
              <Typography variant="h6" data-testid={`pool-${m.key}`}>
                {formatMoney(m.value)}
              </Typography>
            </CardContent>
          </Card>
        ))}
      </Box>
      {canRecord ? (
        <Box>
          <Button variant="outlined" onClick={() => setRecording(true)} sx={{ minHeight: 44 }}>
            {t('rotation.pool.record')}
          </Button>
        </Box>
      ) : null}
      <ResponsiveTable
        columns={columns}
        rows={entries.data ?? []}
        getRowId={(e) => e.id}
        emptyTitle={t('rotation.pool.empty')}
        emptyDescription=""
      />
      {recording ? <RecordEntryDialog cooperativeId={cooperativeId} onClose={() => setRecording(false)} /> : null}
    </Stack>
  )
}

function RecordEntryDialog({ cooperativeId, onClose }: { cooperativeId: string; onClose: () => void }) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const queryClient = useQueryClient()
  const [type, setType] = useState<PoolEntryType>('OPENING')
  const [amount, setAmount] = useState('')
  const [reason, setReason] = useState('')
  const [attempted, setAttempted] = useState(false)
  const key = useRef(newKey())
  const amountInvalid = !isPositiveAmount(amount.trim())
  const reasonInvalid = reason.trim().length < 5

  const mutation = useMutation({
    mutationFn: () => recordRotationPoolEntry(cooperativeId, { type, amount: amount.trim(), reason: reason.trim() }, key.current),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['rotation', cooperativeId] })
      enqueueSnackbar(t('rotation.pool.recorded'), { variant: 'success' })
      onClose()
    },
  })

  return (
    <Dialog open onClose={mutation.isPending ? undefined : onClose} fullWidth maxWidth="sm" aria-label={t('rotation.pool.record')}>
      <DialogTitle>{t('rotation.pool.record')}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <Alert severity="info">{t('rotation.pool.recordExplain')}</Alert>
          <TextField select label={t('rotation.pool.entryType')} value={type} onChange={(e) => setType(e.target.value as PoolEntryType)}>
            {TYPES.map((v) => (
              <MenuItem key={v} value={v}>
                {t(`rotation.pool.types.${v}`)}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            label={t('rotation.pool.entryAmount')}
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            error={attempted && amountInvalid}
            helperText={attempted && amountInvalid ? t('payments.validation.invalidAllocationAmount') : ' '}
            slotProps={{ htmlInput: { inputMode: 'decimal' } }}
          />
          <TextField
            label={t('rotation.pool.entryReason')}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            error={attempted && reasonInvalid}
            helperText={attempted && reasonInvalid ? t('rotation.pool.reasonRequired') : ' '}
            multiline
            minRows={2}
          />
          {mutation.isError ? <ErrorNotice error={mutation.error} /> : null}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose} disabled={mutation.isPending}>
          {t('common.cancel')}
        </Button>
        <FinancialActionButton
          variant="contained"
          disabled={mutation.isPending}
          onClick={() => {
            setAttempted(true)
            if (!amountInvalid && !reasonInvalid) mutation.mutate()
          }}
        >
          {t('rotation.pool.recordSubmit')}
        </FinancialActionButton>
      </DialogActions>
    </Dialog>
  )
}
