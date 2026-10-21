import { Alert, Box, Button, Chip, Paper, Stack, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSnackbar } from 'notistack'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { classifyCooperativeOperatingModel, fetchCooperative } from '@/shared/api/cooperatives'
import { getErrorMessage } from '@/shared/api/client'
import type { SchemeOperatingModel } from '@/shared/types/cooperative'
import { OperatingModelClassifyDialog } from './OperatingModelClassifyDialog'

interface OperatingModelCardProps {
  cooperativeId: string
  /** President / Vice President (or Super Admin) holding COOPERATIVE_WRITE. */
  canManage: boolean
}

/**
 * Shows the scheme's operating model on the Settings page and lets its leadership choose it when it is not set yet.
 * Before this card the choice was only reachable from the platform administrators' scheme page, so schemes created
 * before operating models existed stayed "not configured" and their Rotation screen stayed locked.
 */
export function OperatingModelCard({ cooperativeId, canManage }: OperatingModelCardProps) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const { enqueueSnackbar } = useSnackbar()
  const [open, setOpen] = useState(false)

  const query = useQuery({
    queryKey: ['cooperatives', cooperativeId],
    queryFn: () => fetchCooperative(cooperativeId),
    enabled: Boolean(cooperativeId),
  })

  const mutation = useMutation({
    mutationFn: (model: SchemeOperatingModel) =>
      classifyCooperativeOperatingModel(cooperativeId, { operatingModel: model, confirmed: true }),
    onSuccess: () => {
      enqueueSnackbar(t('operatingModel.classify.success'), { variant: 'success' })
      setOpen(false)
      void queryClient.invalidateQueries({ queryKey: ['cooperatives'] })
      void queryClient.invalidateQueries({ queryKey: ['rotation'] })
    },
    onError: (error) => {
      enqueueSnackbar(getErrorMessage(error, t('errors.generic')), { variant: 'error' })
    },
  })

  const model = query.data?.operatingModel ?? null

  return (
    <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 }, mt: 3 }} data-testid="operating-model-card">
      <Stack spacing={1.5}>
        <Typography variant="h6">{t('operatingModel.label')}</Typography>
        {query.isLoading ? null : model ? (
          <Box>
            <Chip color="primary" label={t(`operatingModel.options.${model}.title`)} sx={{ mb: 1 }} />
            <Typography variant="body2" color="text.secondary">
              {t(`operatingModel.options.${model}.description`)}
            </Typography>
          </Box>
        ) : (
          <Alert
            severity="warning"
            action={
              canManage ? (
                <Button color="inherit" size="small" onClick={() => setOpen(true)}>
                  {t('operatingModel.classify.action')}
                </Button>
              ) : undefined
            }
          >
            <strong>{t('operatingModel.notConfigured')}</strong> {t('operatingModel.notConfiguredHelp')}
          </Alert>
        )}
      </Stack>
      <OperatingModelClassifyDialog
        open={open}
        loading={mutation.isPending}
        onClose={() => setOpen(false)}
        onSubmit={(next) => mutation.mutate(next)}
      />
    </Paper>
  )
}
