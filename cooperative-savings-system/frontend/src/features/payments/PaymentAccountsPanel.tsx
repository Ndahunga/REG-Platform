import { Alert, Box, Button, Chip, MenuItem, Paper, Stack, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  addPaymentAccount,
  deactivatePaymentAccount,
  deletePaymentAccount,
  fetchPaymentAccounts,
} from '@/shared/api/memberPayments'
import { ConfirmDialog } from '@/shared/components/ConfirmDialog'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import type { PaymentAccount, PaymentAccountChannel } from '@/shared/types/memberPayment'
import { ErrorNotice } from './PaymentParts'

const CHANNELS: PaymentAccountChannel[] = ['MTN_MOMO', 'AIRTEL_MONEY', 'BANK_TRANSFER', 'OTHER']

interface PaymentAccountsPanelProps {
  cooperativeId: string
}

/**
 * The accounts the Saving Scheme asks members to pay into. A receipt that names a different account is held for a manual
 * review before the payment can be submitted. Registering an account verifies nothing and moves no money.
 */
export function PaymentAccountsPanel({ cooperativeId }: PaymentAccountsPanelProps) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const queryClient = useQueryClient()
  const [channel, setChannel] = useState<PaymentAccountChannel>('MTN_MOMO')
  const [identifier, setIdentifier] = useState('')
  const [label, setLabel] = useState('')
  const [attempted, setAttempted] = useState(false)
  const [removal, setRemoval] = useState<{ kind: 'delete' | 'deactivate'; account: PaymentAccount } | null>(null)

  const accounts = useQuery({
    queryKey: ['payment-accounts', cooperativeId],
    queryFn: () => fetchPaymentAccounts(cooperativeId),
  })
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['payment-accounts', cooperativeId] })

  const add = useMutation({
    mutationFn: () => addPaymentAccount(cooperativeId, { channel, accountIdentifier: identifier.trim(), label: label.trim() || undefined }),
    onSuccess: () => {
      enqueueSnackbar(t('payments.accounts.added'), { variant: 'success' })
      setIdentifier('')
      setLabel('')
      setAttempted(false)
      void refresh()
    },
  })
  const deactivate = useMutation({
    mutationFn: (id: string) => deactivatePaymentAccount(cooperativeId, id),
    onSuccess: () => {
      enqueueSnackbar(t('payments.accounts.deactivated'), { variant: 'success' })
      setRemoval(null)
      void refresh()
    },
  })
  const remove = useMutation({
    mutationFn: (id: string) => deletePaymentAccount(cooperativeId, id),
    onSuccess: () => {
      enqueueSnackbar(t('payments.accounts.deleted'), { variant: 'success' })
      setRemoval(null)
      void refresh()
    },
  })

  const invalid = identifier.replace(/[^A-Za-z0-9]/g, '').length < 6
  const onAdd = () => {
    setAttempted(true)
    if (invalid || add.isPending) return
    add.mutate()
  }

  return (
    <Box data-testid="payment-accounts-panel">
      <Typography variant="h6">{t('payments.accounts.title')}</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {t('payments.accounts.description')}
      </Typography>

      {accounts.isLoading ? <LoadingState variant="skeleton" rows={2} /> : null}
      {accounts.isError ? <ErrorState message={t('payments.errors.generic')} onRetry={() => void accounts.refetch()} /> : null}
      {accounts.data && accounts.data.length === 0 ? <Alert severity="info">{t('payments.accounts.empty')}</Alert> : null}

      <Stack spacing={1} sx={{ mb: 2 }}>
        {(accounts.data ?? []).map((account) => (
          <Paper
            key={account.id}
            elevation={0}
            sx={{ p: 1.5, border: '1px solid', borderColor: 'divider' }}
            data-testid="payment-account-row"
          >
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap' }}>
              <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
                <Chip size="small" label={t(`payments.method.${account.channel}`)} />
                <Typography variant="body2" sx={{ fontWeight: 600 }}>
                  {account.accountIdentifier}
                </Typography>
                {account.label ? <Typography variant="body2">{account.label}</Typography> : null}
                {!account.active ? <Chip size="small" variant="outlined" label={t('payments.accounts.inactive')} /> : null}
              </Stack>
              <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap' }}>
                {account.active ? (
                  <Button
                    size="small"
                    variant="outlined"
                    aria-label={t('payments.accounts.deactivateNamed', { account: account.accountIdentifier })}
                    onClick={() => setRemoval({ kind: 'deactivate', account })}
                  >
                    {t('payments.accounts.deactivate')}
                  </Button>
                ) : null}
                {account.canDelete ? (
                  <Button
                    size="small"
                    color="error"
                    variant="outlined"
                    aria-label={t('payments.accounts.deleteNamed', { account: account.accountIdentifier })}
                    onClick={() => setRemoval({ kind: 'delete', account })}
                  >
                    {t('payments.accounts.delete')}
                  </Button>
                ) : null}
              </Stack>
            </Stack>
          </Paper>
        ))}
      </Stack>

      <Stack direction={{ xs: 'column', md: 'row' }} spacing={1} sx={{ alignItems: { md: 'flex-start' } }}>
        <TextField
          select
          size="small"
          label={t('payments.accounts.channel')}
          value={channel}
          onChange={(e) => setChannel(e.target.value as PaymentAccountChannel)}
          sx={{ minWidth: 190 }}
        >
          {CHANNELS.map((c) => (
            <MenuItem key={c} value={c}>
              {t(`payments.method.${c}`)}
            </MenuItem>
          ))}
        </TextField>
        <TextField
          size="small"
          label={t('payments.accounts.identifier')}
          value={identifier}
          onChange={(e) => setIdentifier(e.target.value)}
          error={attempted && invalid}
          slotProps={{ htmlInput: { maxLength: 64 } }}
        />
        <TextField
          size="small"
          label={t('payments.accounts.label')}
          value={label}
          onChange={(e) => setLabel(e.target.value)}
          slotProps={{ htmlInput: { maxLength: 100 } }}
        />
        <Button variant="contained" onClick={onAdd} disabled={add.isPending} sx={{ minHeight: 40 }}>
          {add.isPending ? t('payments.accounts.adding') : t('payments.accounts.add')}
        </Button>
      </Stack>
      {add.isError ? (
        <Box sx={{ mt: 1 }}>
          <ErrorNotice error={add.error} />
        </Box>
      ) : null}
      {remove.isError || deactivate.isError ? (
        <Box sx={{ mt: 1 }}>
          <ErrorNotice error={remove.error ?? deactivate.error} />
        </Box>
      ) : null}

      <ConfirmDialog
        open={Boolean(removal)}
        title={removal?.kind === 'delete' ? t('payments.accounts.deleteTitle') : t('payments.accounts.deactivateTitle')}
        message={removal?.kind === 'delete' ? t('payments.accounts.deleteMessage') : t('payments.accounts.deactivateMessage')}
        confirmLabel={removal?.kind === 'delete' ? t('payments.accounts.deleteConfirm') : t('payments.accounts.deactivateConfirm')}
        loading={remove.isPending || deactivate.isPending}
        onCancel={() => setRemoval(null)}
        onConfirm={() => {
          if (!removal) return
          if (removal.kind === 'delete') remove.mutate(removal.account.id)
          else deactivate.mutate(removal.account.id)
        }}
      />
    </Box>
  )
}
