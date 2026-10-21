import BusinessIcon from '@mui/icons-material/Business'
import { Box, Skeleton, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { useAppSelector } from '@/app/store/hooks'
import { selectIsSuperAdmin } from '@/app/store/authSlice'
import { fetchCooperative, fetchMyCooperatives } from '@/shared/api/cooperatives'

interface CurrentSchemeNameProps {
  /** The current cooperative from the existing auth/selection context (never chosen here). */
  cooperativeId: string
}

/**
 * Read-only display of the Saving Scheme the dashboard is showing. Replaces the in-page scheme dropdown.
 *
 * Name sources (same queries/keys the rest of the app already uses, so they share the cache):
 * - members and officers: their own schemes (`/cooperatives/mine`), matched by the current cooperative id;
 * - SUPER_ADMIN in the support workspace: the scheme record that `SupportWorkspaceRoute` already verified.
 * It never falls back to "the first scheme in the list".
 *
 * Tenant isolation: the id is only a lookup key, never proof of access. For non-super-admins the name is shown
 * only when the id is also one of the authenticated user's own cooperatives (`user.cooperativeIds`), so a stale
 * cache entry or a manipulated id cannot surface another cooperative's name. The backend still authorizes
 * every request independently.
 */
export function CurrentSchemeName({ cooperativeId }: CurrentSchemeNameProps) {
  const { t } = useTranslation()
  const authStatus = useAppSelector((s) => s.auth.status)
  const isSuperAdmin = useAppSelector(selectIsSuperAdmin)
  const ownCooperativeIds = useAppSelector((s) => s.auth.user?.cooperativeIds)

  const mineQuery = useQuery({
    queryKey: ['cooperatives', 'mine'],
    queryFn: fetchMyCooperatives,
    enabled: authStatus === 'authenticated' && !isSuperAdmin,
    staleTime: 60_000,
  })
  const schemeQuery = useQuery({
    queryKey: ['cooperatives', cooperativeId],
    queryFn: () => fetchCooperative(cooperativeId),
    enabled: authStatus === 'authenticated' && isSuperAdmin,
    staleTime: 60_000,
  })

  const query = isSuperAdmin ? schemeQuery : mineQuery
  const authorized = isSuperAdmin || Boolean(ownCooperativeIds?.includes(cooperativeId))
  const name = authorized
    ? (
        isSuperAdmin ? schemeQuery.data?.name : mineQuery.data?.find((c) => c.id === cooperativeId)?.name
      )?.trim()
    : undefined

  return (
    <Box
      data-testid="dashboard-scheme-name"
      aria-label={t('dashboard.currentSchemeLabel')}
      sx={{ display: 'flex', alignItems: 'center', gap: 0.75, mt: 0.5, minWidth: 0 }}
    >
      <BusinessIcon fontSize="small" sx={{ color: 'text.secondary', flexShrink: 0 }} />
      {authorized && query.isLoading ? (
        <Skeleton variant="text" width={180} sx={{ fontSize: '1rem' }} aria-label={t('common.loading')} />
      ) : name ? (
        <Typography variant="body1" component="p" sx={{ fontWeight: 600, overflowWrap: 'anywhere' }}>
          {name}
        </Typography>
      ) : (
        <Typography variant="body2" component="p" color="text.secondary">
          {t('dashboard.schemeNameUnavailable')}
        </Typography>
      )}
    </Box>
  )
}
