import CheckCircleOutlinedIcon from '@mui/icons-material/CheckCircleOutlined'
import {
  Alert,
  Button,
  Chip,
  Paper,
  Stack,
  Typography,
} from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { fetchHealth, fetchSystemInfo } from '@/shared/api/health'
import { getErrorMessage } from '@/shared/api/client'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { PageHeader } from '@/shared/components/PageHeader'

export function SystemHealthPage() {
  const { t } = useTranslation()

  const healthQuery = useQuery({
    queryKey: ['public-health'],
    queryFn: fetchHealth,
    retry: 1,
  })

  const infoQuery = useQuery({
    queryKey: ['system-info'],
    queryFn: fetchSystemInfo,
    retry: 1,
  })

  const refetchAll = () => {
    void healthQuery.refetch()
    void infoQuery.refetch()
  }

  const isLoading = healthQuery.isLoading && infoQuery.isLoading

  return (
    <>
      <PageHeader
        title={t('pages.system.title')}
        description={t('pages.system.description')}
        actions={
          <Button
            variant="outlined"
            onClick={refetchAll}
            disabled={healthQuery.isFetching || infoQuery.isFetching}
            sx={{ minHeight: 44 }}
          >
            {t('common.retry')}
          </Button>
        }
      />

      {isLoading ? <LoadingState /> : null}

      <Stack spacing={2.5}>
        {healthQuery.isError ? (
          <ErrorState
            message={getErrorMessage(healthQuery.error)}
            onRetry={() => void healthQuery.refetch()}
          />
        ) : null}

        {healthQuery.data ? (
          <Paper
            elevation={0}
            sx={{ p: { xs: 2.5, md: 3.5 }, border: '1px solid', borderColor: 'divider' }}
          >
            <Stack direction="row" spacing={1} sx={{ mb: 2, alignItems: 'center' }}>
              <CheckCircleOutlinedIcon color="success" />
              <Typography variant="h6">{t('system.publicHealthTitle')}</Typography>
              <Chip
                size="small"
                color={
                  healthQuery.data.status?.toLowerCase() === 'up' ||
                  healthQuery.data.status?.toLowerCase() === 'ok'
                    ? 'success'
                    : 'default'
                }
                label={healthQuery.data.status || 'unknown'}
              />
            </Stack>
            <Stack spacing={1}>
              {healthQuery.data.service ? (
                <Typography variant="body2">
                  {t('system.service')}: {healthQuery.data.service}
                </Typography>
              ) : null}
              {healthQuery.data.version ? (
                <Typography variant="body2">
                  {t('system.version')}: {healthQuery.data.version}
                </Typography>
              ) : null}
              {healthQuery.data.timestamp ? (
                <Typography variant="body2">
                  {t('system.timestamp')}: {healthQuery.data.timestamp}
                </Typography>
              ) : null}
            </Stack>
          </Paper>
        ) : null}

        {infoQuery.isError ? (
          <Alert severity="warning">
            {t('system.infoUnavailable')}: {getErrorMessage(infoQuery.error)}
          </Alert>
        ) : null}

        {infoQuery.data ? (
          <Paper
            elevation={0}
            sx={{ p: { xs: 2.5, md: 3.5 }, border: '1px solid', borderColor: 'divider' }}
          >
            <Typography variant="h6" sx={{ mb: 2 }}>
              {t('system.infoTitle')}
            </Typography>
            <Stack spacing={1}>
              {infoQuery.data.name != null ? (
                <Typography variant="body2">
                  {t('system.name')}: {String(infoQuery.data.name)}
                </Typography>
              ) : null}
              {infoQuery.data.version != null ? (
                <Typography variant="body2">
                  {t('system.version')}: {String(infoQuery.data.version)}
                </Typography>
              ) : null}
              {infoQuery.data.profiles != null ? (
                <Typography variant="body2">
                  {t('system.profiles')}:{' '}
                  {Array.isArray(infoQuery.data.profiles)
                    ? infoQuery.data.profiles.join(', ') || '—'
                    : String(infoQuery.data.profiles)}
                </Typography>
              ) : null}
              {infoQuery.data.javaVersion != null ? (
                <Typography variant="body2">
                  {t('system.javaVersion')}: {String(infoQuery.data.javaVersion)}
                </Typography>
              ) : null}
              {infoQuery.data.dbReachable != null ? (
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                  <Typography variant="body2">{t('system.dbReachable')}:</Typography>
                  <Chip
                    size="small"
                    color={infoQuery.data.dbReachable ? 'success' : 'error'}
                    label={
                      infoQuery.data.dbReachable ? t('common.yes') : t('common.no')
                    }
                  />
                </Stack>
              ) : null}
              {infoQuery.data.flywayVersion != null ? (
                <Typography variant="body2">
                  {t('system.flywayVersion')}: {String(infoQuery.data.flywayVersion)}
                </Typography>
              ) : null}
              {infoQuery.data.timestamp != null ? (
                <Typography variant="body2">
                  {t('system.timestamp')}: {String(infoQuery.data.timestamp)}
                </Typography>
              ) : null}
            </Stack>
          </Paper>
        ) : null}
      </Stack>
    </>
  )
}
