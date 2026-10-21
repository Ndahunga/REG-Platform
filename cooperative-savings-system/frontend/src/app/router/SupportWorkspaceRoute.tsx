import { Box, Button } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { Link as RouterLink, Outlet, useParams } from 'react-router-dom'
import { setSelectedCooperativeId } from '@/app/store/authSlice'
import { useAppDispatch, useAppSelector } from '@/app/store/hooks'
import { getErrorMessage } from '@/shared/api/client'
import { fetchCooperative } from '@/shared/api/cooperatives'
import { ErrorState } from '@/shared/components/ErrorState'
import { LoadingState } from '@/shared/components/LoadingState'
import { SUPER_ADMIN_ROUTES } from '@/shared/constants/routes'

/**
 * Support workspace for one Saving Scheme, opened by a SUPER_ADMIN under `/superadmin/cooperatives/:id/...`.
 *
 * The URL is the source of truth. The scheme is verified against the backend (so a refreshed, shared or
 * mistyped URL fails cleanly), and only then is it made the selected cooperative the existing pages read
 * from the store. The pages render nothing until the store and the URL agree. The user stays signed in as
 * themselves: nothing here changes who is authenticated.
 */
export function SupportWorkspaceRoute() {
  const { t } = useTranslation()
  const dispatch = useAppDispatch()
  const { cooperativeId = '' } = useParams()
  const selected = useAppSelector((s) => s.auth.selectedCooperativeId)

  const query = useQuery({
    queryKey: ['cooperatives', cooperativeId],
    queryFn: () => fetchCooperative(cooperativeId),
    enabled: Boolean(cooperativeId),
    retry: false,
  })

  useEffect(() => {
    if (query.isSuccess && selected !== cooperativeId) {
      dispatch(setSelectedCooperativeId(cooperativeId))
    }
  }, [query.isSuccess, selected, cooperativeId, dispatch])

  if (query.isError) {
    return (
      <Box data-testid="support-workspace-error">
        <ErrorState
          message={`${t('superAdmin.support.unavailable')} ${getErrorMessage(query.error)}`}
          onRetry={() => void query.refetch()}
        />
        <Button component={RouterLink} to={SUPER_ADMIN_ROUTES.cooperatives} sx={{ mt: 1 }}>
          {t('superAdmin.support.backToSchemes')}
        </Button>
      </Box>
    )
  }

  if (!query.isSuccess || selected !== cooperativeId) {
    return <LoadingState />
  }

  return <Outlet />
}
