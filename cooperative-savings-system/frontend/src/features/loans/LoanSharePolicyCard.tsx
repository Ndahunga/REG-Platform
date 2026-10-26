import { Box, Button, FormControlLabel, Stack, Switch, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { selectCanManageLoans } from '@/app/store/authSlice'
import { useAppSelector } from '@/app/store/hooks'
import { getErrorMessage } from '@/shared/api/client'
import { fetchLoanSettings, updateLoanSettings } from '@/shared/api/loanSettings'
import type { LoanRepaymentComponent, LoanSettingsUpdateRequest } from '@/shared/types/loan'

/** Loans granted by shares: a limit per share held, and whether an overdue contribution blocks a new loan. */
export function LoanSharePolicyCard({ cooperativeId }: { cooperativeId: string }) {
  const { t } = useTranslation()
  const { enqueueSnackbar } = useSnackbar()
  const queryClient = useQueryClient()
  const canEdit = useAppSelector(selectCanManageLoans)
  const query = useQuery({ queryKey: ['loan-settings', cooperativeId], queryFn: () => fetchLoanSettings(cooperativeId) })
  const [perShare, setPerShare] = useState('')
  const [block, setBlock] = useState(false)

  useEffect(() => {
    if (!query.data) return
    setPerShare(query.data.maxLoanPerShare ? String(Number(query.data.maxLoanPerShare)) : '')
    setBlock(Boolean(query.data.blockWhenContributionOverdue))
  }, [query.data])

  const mutation = useMutation({
    mutationFn: () => {
      const s = query.data!
      const amount = Number(perShare.replace(/[,\s]/g, ''))
      const payload: LoanSettingsUpdateRequest = {
        interestRatePercent: s.interestRatePercent,
        interestType: s.interestType,
        maxLoanAmount: s.maxLoanAmount ?? null,
        maxTermMonths: s.maxTermMonths ?? null,
        minMembershipMonths: s.minMembershipMonths ?? null,
        allowMemberRequests: s.allowMemberRequests,
        loanPenaltyEnabled: s.loanPenaltyEnabled,
        repaymentDateModel: s.repaymentDateModel,
        penaltyType: s.penaltyType,
        penaltyRateOrAmount: s.penaltyRateOrAmount,
        penaltyFrequency: s.penaltyFrequency,
        gracePeriodDays: s.gracePeriodDays,
        allocationOrder: s.allocationOrder as LoanRepaymentComponent[] | undefined,
        maxLoanPerShare: amount > 0 ? amount : undefined,
        clearMaxLoanPerShare: amount > 0 ? undefined : true,
        blockWhenContributionOverdue: block,
      }
      return updateLoanSettings(cooperativeId, payload)
    },
    onSuccess: () => {
      enqueueSnackbar(t('loans.sharePolicy.saved'), { variant: 'success' })
      void queryClient.invalidateQueries({ queryKey: ['loan-settings', cooperativeId] })
    },
    onError: (error) => enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' }),
  })

  if (!query.data) return null
  return (
    <Box sx={{ maxWidth: 560, p: { xs: 2, sm: 2.5 }, mt: 2, border: '1px solid', borderColor: 'divider', borderRadius: 1 }} data-testid="loan-share-policy">
      <Typography variant="h6" gutterBottom>
        {t('loans.sharePolicy.title')}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {t('loans.sharePolicy.description')}
      </Typography>
      <Stack spacing={2}>
        <TextField
          label={t('loans.sharePolicy.perShare')}
          value={perShare}
          onChange={(e) => setPerShare(e.target.value)}
          helperText={t('loans.sharePolicy.perShareHint')}
          disabled={!canEdit}
          fullWidth
        />
        <FormControlLabel
          control={<Switch checked={block} onChange={(_, v) => setBlock(v)} disabled={!canEdit} />}
          label={t('loans.sharePolicy.block')}
        />
        <Button variant="contained" sx={{ alignSelf: 'flex-start' }} disabled={!canEdit || mutation.isPending} onClick={() => mutation.mutate()}>
          {t('common.save')}
        </Button>
      </Stack>
    </Box>
  )
}
