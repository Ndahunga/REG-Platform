import { TextField } from '@mui/material'
import { useTranslation } from 'react-i18next'
import {
  filterValidationMessageKey,
  isIsoDate,
  todayIsoDate,
  type DateRangeIssue,
} from '@/shared/utils/filterValidation'

interface DateRangeFieldsProps {
  from: string
  to: string
  onFromChange: (value: string) => void
  onToChange: (value: string) => void
  fromLabel: string
  toLabel: string
  issue: DateRangeIssue | null
}

export function DateRangeFields({
  from,
  to,
  onFromChange,
  onToChange,
  fromLabel,
  toLabel,
  issue,
}: DateRangeFieldsProps) {
  const { t } = useTranslation()
  const today = todayIsoDate()
  const message = issue ? t(filterValidationMessageKey(issue)!) : undefined
  const fromInvalid = Boolean(from) && !isIsoDate(from)
  const toInvalid = Boolean(to) && !isIsoDate(to)
  const fromError = fromInvalid || issue === 'futureFrom' || issue === 'fromAfterTo'
  const toError = toInvalid || issue === 'futureTo' || issue === 'fromAfterTo'
  const fromHelper =
    fromInvalid || issue === 'futureFrom' ? message : undefined
  const toHelper =
    toInvalid || issue === 'futureTo' || issue === 'fromAfterTo' ? message : undefined

  return (
    <>
      <TextField
        size="small"
        type="date"
        label={fromLabel}
        value={from}
        onChange={(e) => onFromChange(e.target.value)}
        error={fromError}
        helperText={fromHelper}
        slotProps={{
          inputLabel: { shrink: true },
          htmlInput: { max: today },
        }}
        sx={{ minWidth: { xs: '100%', sm: 160 } }}
      />
      <TextField
        size="small"
        type="date"
        label={toLabel}
        value={to}
        onChange={(e) => onToChange(e.target.value)}
        error={toError}
        helperText={toHelper}
        slotProps={{
          inputLabel: { shrink: true },
          htmlInput: { max: today, min: from || undefined },
        }}
        sx={{ minWidth: { xs: '100%', sm: 160 } }}
      />
    </>
  )
}
