import {
  Alert,
  Box,
  Button,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import dayjs from 'dayjs'
import { useSnackbar } from 'notistack'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { getErrorMessage } from '@/shared/api/client'
import { generateAutomaticFines } from '@/shared/api/fines'

interface FineGeneratePanelProps {
  cooperativeId: string
}

export function FineGeneratePanel({ cooperativeId }: FineGeneratePanelProps) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const { enqueueSnackbar } = useSnackbar()
  const [year, setYear] = useState(String(dayjs().year()))
  const [month, setMonth] = useState(String(dayjs().month() + 1))

  const yearOptions = useMemo(() => {
    const current = dayjs().year()
    return Array.from({ length: 6 }, (_, i) => current - 3 + i)
  }, [])

  const mutation = useMutation({
    mutationFn: () =>
      generateAutomaticFines(cooperativeId, {
        year: year ? Number(year) : undefined,
        month: month ? Number(month) : undefined,
      }),
    onSuccess: (result) => {
      enqueueSnackbar(
        t('fines.generate.success', {
          created: result.createdCount,
          skipped: result.skippedDuplicates,
        }),
        { variant: 'success' },
      )
      void queryClient.invalidateQueries({ queryKey: ['fines'] })
      void queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  return (
    <Box
      sx={{
        p: { xs: 2, sm: 2.5 },
        border: '1px solid',
        borderColor: 'divider',
        borderRadius: 1,
        mb: 2.5,
      }}
    >
      <Typography variant="h6" gutterBottom>
        {t('fines.generate.title')}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {t('fines.generate.description')}
      </Typography>

      <Alert severity="info" sx={{ mb: 2 }}>
        {t('fines.generate.hint')}
      </Alert>

      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={1.5}
        useFlexGap
        sx={{ flexWrap: 'wrap', alignItems: { sm: 'center' } }}
      >
        <TextField
          select
          size="small"
          label={t('fines.fields.year')}
          value={year}
          onChange={(e) => setYear(e.target.value)}
          sx={{ minWidth: 110 }}
        >
          {yearOptions.map((y) => (
            <MenuItem key={y} value={String(y)}>
              {y}
            </MenuItem>
          ))}
        </TextField>
        <TextField
          select
          size="small"
          label={t('fines.fields.month')}
          value={month}
          onChange={(e) => setMonth(e.target.value)}
          sx={{ minWidth: 120 }}
        >
          {Array.from({ length: 12 }, (_, i) => i + 1).map((m) => (
            <MenuItem key={m} value={String(m)}>
              {dayjs().month(m - 1).format('MMMM')}
            </MenuItem>
          ))}
        </TextField>
        <Button
          variant="contained"
          disabled={mutation.isPending}
          onClick={() => mutation.mutate()}
        >
          {t('fines.generate.submit')}
        </Button>
      </Stack>
    </Box>
  )
}
