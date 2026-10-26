import { Alert, Box, Button, Chip, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useTranslation } from 'react-i18next'
import { getErrorMessage } from '@/shared/api/client'
import { fetchSharePool, fetchTurnEligibility, requestTurnPayout } from '@/shared/api/rotation'
import { monthKey, newIdempotencyKey } from './rotationHelpers'
import { money } from './shareCycle'

/**
 * Turns of share-based cycles that are due: what each still pays, the member's overdue contributions that will be
 * netted from it (never loans or fines), and a button to request the payout. Hidden when the scheme has no such cycle.
 */
export function TurnPayoutsSection({ cooperativeId }: { cooperativeId: string }) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const queryClient = useQueryClient()
  const turnsQuery = useQuery({
    queryKey: ['rotation', cooperativeId, 'turn-eligibility'],
    queryFn: () => fetchTurnEligibility(cooperativeId),
  })
  const poolQuery = useQuery({
    queryKey: ['rotation', cooperativeId, 'share-pool'],
    queryFn: () => fetchSharePool(cooperativeId),
    enabled: (turnsQuery.data ?? []).length > 0,
  })
  const mutation = useMutation({
    mutationFn: (turnId: string) => requestTurnPayout(cooperativeId, turnId, undefined, newIdempotencyKey()),
    onSuccess: () => {
      enqueueSnackbar(t('rotation.share.payoutRequested'), { variant: 'success' })
      void queryClient.invalidateQueries({ queryKey: ['rotation', cooperativeId] })
    },
    onError: (error) => enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' }),
  })

  const turns = (turnsQuery.data ?? []).filter((x) => Number(x.outstanding) > 0)
  if (!turnsQuery.data || turnsQuery.data.length === 0) return null
  const pool = poolQuery.data

  return (
    <Box sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 1, p: 2 }} data-testid="turn-payouts">
      <Typography variant="h6" gutterBottom>
        {t('rotation.share.turnPayoutsTitle')}
      </Typography>
      {pool ? (
        <Alert severity="info" sx={{ mb: 1.5 }}>
          {t('rotation.share.poolLine', {
            available: money(pool.available),
            collected: money(pool.collected),
            paid: money(pool.paidOut),
          })}
        </Alert>
      ) : null}
      {turns.length === 0 ? (
        <Typography variant="body2" color="text.secondary">
          {t('rotation.share.allTurnsPaid')}
        </Typography>
      ) : (
        <Box sx={{ overflowX: 'auto' }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>{t('rotation.share.month')}</TableCell>
                <TableCell>{t('rotation.share.member')}</TableCell>
                <TableCell align="right">{t('rotation.share.outstanding')}</TableCell>
                <TableCell align="right">{t('rotation.share.arrearsNetted')}</TableCell>
                <TableCell align="right">{t('rotation.share.cashToHand')}</TableCell>
                <TableCell />
              </TableRow>
            </TableHead>
            <TableBody>
              {turns.map((turn) => {
                const max = Number(turn.maxRequestable)
                const netted = Math.min(Number(turn.arrears), max || Number(turn.outstanding))
                return (
                  <TableRow key={turn.turnId}>
                    <TableCell>{monthKey(turn.periodYear, turn.periodMonth)}</TableCell>
                    <TableCell>
                      {turn.memberName} {turn.shares > 1 ? `(${turn.shares})` : ''}
                    </TableCell>
                    <TableCell align="right">{money(turn.outstanding)}</TableCell>
                    <TableCell align="right">{money(netted)}</TableCell>
                    <TableCell align="right">{money((max || Number(turn.outstanding)) - netted)}</TableCell>
                    <TableCell align="right">
                      {turn.canRequestPayout ? (
                        <Button size="small" variant="contained" disabled={mutation.isPending} onClick={() => mutation.mutate(turn.turnId)}>
                          {t('rotation.share.requestPayout', { amount: money(max) })}
                        </Button>
                      ) : (
                        <Chip size="small" label={t(`rotation.share.blocked.${turn.payoutBlockedReason ?? 'OTHER'}`)} />
                      )}
                    </TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        </Box>
      )}
      <Stack sx={{ mt: 1 }}>
        <Typography variant="caption" color="text.secondary">
          {t('rotation.share.nettingNote')}
        </Typography>
      </Stack>
    </Box>
  )
}
