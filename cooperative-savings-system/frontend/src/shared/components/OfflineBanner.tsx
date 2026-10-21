import CloudOffIcon from '@mui/icons-material/CloudOff'
import { Alert, Slide } from '@mui/material'
import { useTranslation } from 'react-i18next'
import { useOnlineStatus } from '@/shared/hooks/useOnlineStatus'

export function OfflineBanner() {
  const { t } = useTranslation()
  const online = useOnlineStatus()

  return (
    <Slide direction="up" in={!online} mountOnEnter unmountOnExit>
      <Alert
        severity="warning"
        icon={<CloudOffIcon fontSize="inherit" />}
        sx={{
          position: 'fixed',
          left: 0,
          right: 0,
          bottom: 0,
          zIndex: (theme) => theme.zIndex.snackbar,
          borderRadius: 0,
        }}
      >
        {t('pwa.offlineBanner')}
      </Alert>
    </Slide>
  )
}
