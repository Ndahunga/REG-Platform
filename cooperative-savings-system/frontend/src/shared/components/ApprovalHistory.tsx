import { Paper, Stack, Typography } from '@mui/material'
import { useTranslation } from 'react-i18next'
import type { ApprovalEvent } from '@/shared/types/approval'
import { formatApprovalStamp } from '@/shared/types/approval'

interface ApprovalHistoryProps {
  events?: ApprovalEvent[] | null
}

export function ApprovalHistory({ events }: ApprovalHistoryProps) {
  const { t } = useTranslation()
  const rows = events ?? []

  return (
    <Paper
      elevation={0}
      sx={{ p: { xs: 2, sm: 2.5 }, border: '1px solid', borderColor: 'divider' }}
    >
      <Typography variant="h6" gutterBottom>
        {t('approvals.historyTitle')}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
        {t('approvals.historyHint')}
      </Typography>
      {rows.length === 0 ? (
        <Typography variant="body2" color="text.secondary">
          {t('approvals.empty')}
        </Typography>
      ) : (
        <Stack spacing={1}>
          {rows.map((event) => (
            <Typography key={event.id} variant="body2">
              {t(`approvals.actions.${event.action}`, { defaultValue: event.action })}:{' '}
              {formatApprovalStamp(event)}
              {event.comment ? ` — ${event.comment}` : ''}
            </Typography>
          ))}
        </Stack>
      )}
    </Paper>
  )
}
