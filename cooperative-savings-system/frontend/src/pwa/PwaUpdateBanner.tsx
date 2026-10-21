import { Alert, Button, Snackbar } from '@mui/material'
import { useTranslation } from 'react-i18next'
import { usePwaUpdate } from './usePwaUpdate'

export function PwaUpdateBanner() {
  const { t } = useTranslation()
  const { needRefresh, reload, dismiss } = usePwaUpdate()

  return (
    <Snackbar
      open={needRefresh}
      anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      sx={{ bottom: { xs: 72, sm: 24 } }}
    >
      <Alert
        severity="info"
        variant="filled"
        onClose={dismiss}
        action={
          <Button color="inherit" size="small" onClick={reload}>
            {t('pwa.reload')}
          </Button>
        }
        sx={{ width: '100%', alignItems: 'center' }}
      >
        {t('pwa.updateAvailable')}
      </Alert>
    </Snackbar>
  )
}
