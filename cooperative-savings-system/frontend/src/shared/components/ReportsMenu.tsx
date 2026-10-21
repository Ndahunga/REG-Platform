import AssessmentIcon from '@mui/icons-material/Assessment'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import {
  Box,
  Button,
  Divider,
  ListItemIcon,
  ListItemText,
  Menu,
  MenuItem,
  Typography,
} from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { useAppSelector } from '@/app/store/hooks'
import { fetchReportTypes } from '@/shared/api/reports'
import { ROUTES } from '@/shared/constants/routes'
import { MEMBER_PRIMARY_REPORTS, STAFF_PRIMARY_REPORTS } from '@/shared/types/report'
import { reportTypeLabelKey } from '@/features/reports/reportHelpers'

interface ReportsMenuProps {
  buttonVariant?: 'contained' | 'outlined' | 'text'
  size?: 'small' | 'medium'
}

export function ReportsMenu({ buttonVariant = 'outlined', size = 'medium' }: ReportsMenuProps) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const cooperativeId = useAppSelector((s) => s.auth.selectedCooperativeId)
  const [anchor, setAnchor] = useState<null | HTMLElement>(null)

  const typesQuery = useQuery({
    queryKey: ['reports', 'types', cooperativeId],
    queryFn: () => fetchReportTypes(cooperativeId!),
    enabled: Boolean(cooperativeId),
  })

  const types = typesQuery.data ?? []
  const selfScoped = types.some((item) => item.selfScoped)
  const allowed = new Set(types.map((item) => String(item.type)))
  const primaryIds = (selfScoped ? MEMBER_PRIMARY_REPORTS : STAFF_PRIMARY_REPORTS).filter((type) =>
    allowed.has(type),
  )
  const primarySet = new Set<string>(primaryIds)
  const advanced = types.filter((item) => !primarySet.has(String(item.type)))

  const go = (type: string) => {
    setAnchor(null)
    navigate(`${ROUTES.reports}?type=${type}`)
  }

  return (
    <>
      <Button
        variant={buttonVariant}
        size={size}
        endIcon={<ExpandMoreIcon />}
        onClick={(e) => setAnchor(e.currentTarget)}
        aria-haspopup="menu"
        aria-expanded={Boolean(anchor)}
        disabled={!cooperativeId}
      >
        {t('nav.reports')}
      </Button>
      <Menu
        anchorEl={anchor}
        open={Boolean(anchor)}
        onClose={() => setAnchor(null)}
        slotProps={{ paper: { sx: { minWidth: 280, maxHeight: 480 } } }}
      >
        {primaryIds.length ? (
          <>
            <Box sx={{ px: 2, pt: 1.5, pb: 0.5 }}>
              <Typography variant="overline" color="text.secondary">
                {t('reports.primaryTitle')}
              </Typography>
            </Box>
            {primaryIds.map((type) => (
              <MenuItem key={type} onClick={() => go(type)}>
                <ListItemIcon>
                  <AssessmentIcon fontSize="small" />
                </ListItemIcon>
                <ListItemText>
                  {t(reportTypeLabelKey(type), { defaultValue: type })}
                </ListItemText>
              </MenuItem>
            ))}
          </>
        ) : null}
        {advanced.length ? (
          <>
            {primaryIds.length ? <Divider sx={{ my: 1 }} /> : null}
            <Box sx={{ px: 2, pt: 0.5, pb: 0.5 }}>
              <Typography variant="overline" color="text.secondary">
                {t('reports.advancedTitle')}
              </Typography>
            </Box>
            {advanced.map((item) => (
              <MenuItem key={item.type} onClick={() => go(String(item.type))}>
                <ListItemText>
                  {t(reportTypeLabelKey(String(item.type)), { defaultValue: item.label ?? item.type })}
                </ListItemText>
              </MenuItem>
            ))}
          </>
        ) : null}
        <Divider />
        <MenuItem
          onClick={() => {
            setAnchor(null)
            navigate(ROUTES.reports)
          }}
        >
          <ListItemText>{t('reports.openAll')}</ListItemText>
        </MenuItem>
      </Menu>
    </>
  )
}
