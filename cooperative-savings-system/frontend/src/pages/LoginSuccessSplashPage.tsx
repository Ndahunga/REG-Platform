import { useCallback, useEffect, useRef, useState } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { OuWealthSplash } from '@/features/branding/OuWealthSplash'
import {
  isCoarsePointer,
  isContinueKey,
  isLoginSuccessLocationState,
  OU_WEALTH_SPLASH_ENTRANCE_MS,
  OU_WEALTH_SPLASH_EXIT_MS,
  OU_WEALTH_SPLASH_REDUCED_MOTION_READY_MS,
  prefersReducedMotion,
  resolvePostLoginDestination,
} from '@/features/branding/loginSuccessSplash'
import { ROUTES } from '@/shared/constants/routes'

const SPLASH_BG = '#100e0c'

export function LoginSuccessSplashPage() {
  const location = useLocation()
  const navigate = useNavigate()
  const fromLogin = isLoginSuccessLocationState(location.state)
  const next = resolvePostLoginDestination(
    isLoginSuccessLocationState(location.state) ? location.state.next : undefined,
  )

  const [isReadyToContinue, setIsReadyToContinue] = useState(false)
  const [exiting, setExiting] = useState(false)
  const continued = useRef(false)
  const exitTimer = useRef<number | null>(null)

  const continueToDestination = useCallback(() => {
    if (continued.current) return
    continued.current = true
    setExiting(true)
    exitTimer.current = window.setTimeout(() => {
      navigate(next, { replace: true })
    }, OU_WEALTH_SPLASH_EXIT_MS)
  }, [navigate, next])

  useEffect(() => {
    return () => {
      if (exitTimer.current !== null) window.clearTimeout(exitTimer.current)
    }
  }, [])

  useEffect(() => {
    if (!fromLogin) return undefined

    const html = document.documentElement
    const body = document.body
    const prevHtmlBg = html.style.backgroundColor
    const prevBodyBg = body.style.backgroundColor
    html.style.backgroundColor = SPLASH_BG
    body.style.backgroundColor = SPLASH_BG

    return () => {
      html.style.backgroundColor = prevHtmlBg
      body.style.backgroundColor = prevBodyBg
    }
  }, [fromLogin])

  useEffect(() => {
    if (!fromLogin) return undefined

    const delay = prefersReducedMotion()
      ? OU_WEALTH_SPLASH_REDUCED_MOTION_READY_MS
      : OU_WEALTH_SPLASH_ENTRANCE_MS
    const id = window.setTimeout(() => {
      setIsReadyToContinue(true)
      continueToDestination()
    }, delay)
    return () => window.clearTimeout(id)
  }, [fromLogin, continueToDestination])

  useEffect(() => {
    if (!fromLogin || !isReadyToContinue) return undefined

    const onKeyDown = (event: KeyboardEvent) => {
      if (!isContinueKey(event)) return
      event.preventDefault()
      continueToDestination()
    }

    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [fromLogin, isReadyToContinue, continueToDestination])

  if (!fromLogin) {
    return <Navigate to={ROUTES.dashboard} replace />
  }

  return (
    <OuWealthSplash
      ready={isReadyToContinue}
      exiting={exiting}
      continueLabel={
        isCoarsePointer() ? 'TAP ANYWHERE TO CONTINUE' : 'PRESS ANY KEY TO CONTINUE'
      }
      onContinue={continueToDestination}
    />
  )
}
