import { useEffect, useRef, type ReactNode } from 'react'
import { clearAuth, setAuthStatus, setCredentials } from '@/app/store/authSlice'
import { useAppDispatch, useAppSelector } from '@/app/store/hooks'
import { refresh } from '@/shared/api/auth'

/**
 * Restores a session from the httpOnly refresh cookie when Redux has no access token.
 */
export function AuthBootstrap({ children }: { children: ReactNode }) {
  const dispatch = useAppDispatch()
  const accessToken = useAppSelector((s) => s.auth.accessToken)
  const started = useRef(false)

  useEffect(() => {
    if (started.current) return
    started.current = true

    if (accessToken) {
      dispatch(setAuthStatus('authenticated'))
      return
    }

    dispatch(setAuthStatus('loading'))
    void refresh()
      .then((data) => {
        dispatch(
          setCredentials({
            user: data.user,
            accessToken: data.accessToken,
          }),
        )
      })
      .catch(() => {
        dispatch(clearAuth())
      })
  }, [accessToken, dispatch])

  return children
}
