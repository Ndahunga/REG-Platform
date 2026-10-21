import GetAppIcon from '@mui/icons-material/GetApp'
import { Button } from '@mui/material'
import { useTranslation } from 'react-i18next'
import { useInstallPrompt } from './useInstallPrompt'

export function PwaInstallButton() {
  const { t } = useTranslation()
  const { canInstall, promptInstall } = useInstallPrompt()

  if (!canInstall || import.meta.env.VITE_ENABLE_PWA !== 'true') {
    return null
  }

  return (
    <Button
      color="inherit"
      size="small"
      startIcon={<GetAppIcon />}
      onClick={() => void promptInstall()}
      sx={{ minHeight: 40, displayTransform: 'none' }}
    >
      {t('pwa.install')}
    </Button>
  )
}
