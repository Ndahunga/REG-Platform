import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAppSelector } from '@/app/store/hooks'
import { LoadingState } from '@/shared/components/LoadingState'
import { ROUTES } from '@/shared/constants/routes'

export function ProtectedRoute() {
  const status = useAppSelector((s) => s.auth.status)
  const location = useLocation()

  if (status === 'loading') {
    return <LoadingState />
  }

  if (status !== 'authenticated') {
    return <Navigate to={ROUTES.login} replace state={{ from: location }} />
  }

  return <Outlet />
}
