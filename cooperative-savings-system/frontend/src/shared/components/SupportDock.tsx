import EmailOutlinedIcon from '@mui/icons-material/EmailOutlined'
import WhatsAppIcon from '@mui/icons-material/WhatsApp'
import { Box, IconButton, Tooltip } from '@mui/material'
import { useTranslation } from 'react-i18next'
import { SUPPORT_CONTACTS, supportMailtoHref } from '@/shared/constants/supportContacts'

const BUTTON_SX = {
  width: 44,
  height: 44,
  color: '#FFFFFF',
  boxShadow: '0 4px 14px rgba(15, 23, 42, 0.28)',
  '&:hover': { boxShadow: '0 6px 18px rgba(15, 23, 42, 0.36)' },
  '&:focus-visible': { outline: '3px solid', outlineColor: 'primary.light', outlineOffset: 2 },
} as const

/**
 * Floating help buttons (email + WhatsApp). Rendered once per root layout, never per page. Compact and
 * kept clear of the bottom edge so it does not sit on top of submit buttons or navigation.
 */
export function SupportDock() {
  const { t } = useTranslation()

  return (
    <Box
      component="aside"
      role="complementary"
      aria-label={t('support.dockAria')}
      data-testid="support-dock"
      sx={{
        position: 'fixed',
        right: { xs: 12, sm: 20 },
        bottom: { xs: 'calc(12px + env(safe-area-inset-bottom, 0px))', sm: 20 },
        zIndex: (theme) => theme.zIndex.speedDial,
        display: 'flex',
        // a short row on phones keeps it low and out of the way, a column on larger screens
        flexDirection: { xs: 'row', sm: 'column' },
        gap: 1,
      }}
    >
      <Tooltip title={t('support.emailSupport')} placement="left" arrow>
        <IconButton
          component="a"
          href={supportMailtoHref(t('support.mailSubject'), t('support.mailBody'))}
          aria-label={t('support.emailSupport')}
          sx={{ ...BUTTON_SX, bgcolor: 'primary.main', '&:hover': { ...BUTTON_SX['&:hover'], bgcolor: 'primary.dark' } }}
        >
          <EmailOutlinedIcon fontSize="small" />
        </IconButton>
      </Tooltip>
      <Tooltip title={t('support.chatWhatsApp')} placement="left" arrow>
        <IconButton
          component="a"
          href={SUPPORT_CONTACTS.whatsappHref}
          target="_blank"
          rel="noopener noreferrer"
          aria-label={t('support.chatWhatsApp')}
          sx={{ ...BUTTON_SX, bgcolor: '#1FA855', '&:hover': { ...BUTTON_SX['&:hover'], bgcolor: '#188A45' } }}
        >
          <WhatsAppIcon fontSize="small" />
        </IconButton>
      </Tooltip>
    </Box>
  )
}
