import { Chip, Stack, Typography } from '@mui/material'
import { useTranslation } from 'react-i18next'
import type { ContributionObligationFields } from '@/shared/types/contribution'
import { formatMoney } from '@/shared/utils/formatMoney'
import { bpsLabel, hasExpectedSplit, hasOverdue } from './obligationHelpers'

/**
 * Due date, overdue state and the expected rotating/retained split of one obligation. These are expectations from the
 * period's snapshot, so the split is labelled "expected", never "received".
 */
export function ObligationDetails({ fields }: { fields: ContributionObligationFields }) {
  const { t } = useTranslation()
  const overdue = hasOverdue(fields)
  const split = hasExpectedSplit(fields)
  if (!fields.dueDate && !overdue && !split) return null

  return (
    <Stack spacing={0.25} data-testid="contribution-obligation">
      {fields.dueDate ? (
        <Typography variant="caption" color="text.secondary" data-testid="contribution-due-date">
          {t('contributions.obligation.dueDate', { date: fields.dueDate })}
        </Typography>
      ) : null}
      {overdue ? (
        <Chip
          size="small"
          color="error"
          variant="outlined"
          sx={{ alignSelf: 'flex-start' }}
          data-testid="contribution-overdue"
          label={t('contributions.obligation.overdue', { amount: formatMoney(fields.overdueAmount ?? 0) })}
        />
      ) : null}
      {split ? (
        <Typography variant="caption" color="text.secondary" data-testid="contribution-expected-split">
          {t('contributions.obligation.expectedSplit', {
            rotating: formatMoney(fields.expectedRotatingAmount ?? 0),
            rotatingPercent: bpsLabel(fields.rotatingBps),
            retained: formatMoney(fields.expectedRetainedAmount ?? 0),
            retainedPercent: bpsLabel(fields.retainedBps),
          })}
        </Typography>
      ) : null}
    </Stack>
  )
}
