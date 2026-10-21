import AccountBalanceIcon from '@mui/icons-material/AccountBalance'
import AdminPanelSettingsIcon from '@mui/icons-material/AdminPanelSettings'
import HealthAndSafetyIcon from '@mui/icons-material/HealthAndSafety'
import { Box, Button, Stack, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Link as RouterLink } from 'react-router-dom'
import { SuperAdminDashboard } from '@/features/dashboard/SuperAdminDashboard'
import { MigrationReviewsCard } from '@/features/superAdmin/MigrationReviewsCard'
import { dashboardPageTitleSx } from '@/features/dashboard/dashboardTypography'
import { SUPER_ADMINS_QUERY_KEY, fetchSuperAdmins } from '@/shared/api/superAdmins'
import { SUPER_ADMIN_ROUTES } from '@/shared/constants/routes'

/**
 * Landing page of the system-administration portal: quick links plus the platform-wide overview and
 * Saving Scheme list. Opening a scheme there enters its support workspace.
 */
export function SuperAdminHomePage() {
  const { t } = useTranslation()
  const adminsQuery = useQuery({ queryKey: SUPER_ADMINS_QUERY_KEY, queryFn: fetchSuperAdmins })

  return (
    <Box>
      <Box sx={{ mb: 3 }}>
        <Typography variant="h4" component="h1" sx={dashboardPageTitleSx}>
          {t('superAdmin.home.title')}
        </Typography>
        <Typography variant="body1" color="text.secondary" sx={{ mt: 0.5, maxWidth: 720 }}>
          {t('superAdmin.home.subtitle')}
        </Typography>
      </Box>

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} useFlexGap sx={{ mb: 3, flexWrap: 'wrap' }}>
        <Button
          component={RouterLink}
          to={SUPER_ADMIN_ROUTES.admins}
          variant="contained"
          startIcon={<AdminPanelSettingsIcon />}
        >
          {t('superAdmin.home.manageAdmins')}
        </Button>
        <Button
          component={RouterLink}
          to={SUPER_ADMIN_ROUTES.cooperatives}
          variant="outlined"
          startIcon={<AccountBalanceIcon />}
        >
          {t('superAdmin.home.manageSchemes')}
        </Button>
        <Button
          component={RouterLink}
          to={SUPER_ADMIN_ROUTES.system}
          variant="outlined"
          startIcon={<HealthAndSafetyIcon />}
        >
          {t('superAdmin.home.systemHealth')}
        </Button>
      </Stack>

      <MigrationReviewsCard />

      <SuperAdminDashboard showHeader={false} superAdminCount={adminsQuery.data?.length} />
    </Box>
  )
}
