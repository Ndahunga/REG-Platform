import DarkModeOutlinedIcon from '@mui/icons-material/DarkModeOutlined'
import LightModeOutlinedIcon from '@mui/icons-material/LightModeOutlined'
import { IconButton } from '@mui/material'
import { useTranslation } from 'react-i18next'
import { useAppDispatch, useAppSelector } from '@/app/store/hooks'
import { setThemePreference } from '@/app/store/uiSlice'

export function ThemeSwitcher({
  onDark = false,
}: {
  compact?: boolean
  onDark?: boolean
}) {
  const { t } = useTranslation()
  const dispatch = useAppDispatch()
  const preference = useAppSelector((s) => s.ui.themePreference)
  const isDark = preference === 'dark'

  return (
    <IconButton
      onClick={() => dispatch(setThemePreference(isDark ? 'light' : 'dark'))}
      aria-label={`${t('theme.label')}: ${isDark ? t('theme.dark') : t('theme.light')}`}
      aria-pressed={isDark}
      sx={{ color: onDark ? '#FFFFFF' : 'inherit' }}
    >
      {isDark ? <LightModeOutlinedIcon /> : <DarkModeOutlinedIcon />}
    </IconButton>
  )
}
