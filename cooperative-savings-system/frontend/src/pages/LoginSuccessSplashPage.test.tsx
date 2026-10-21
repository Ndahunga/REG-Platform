import { ThemeProvider } from '@mui/material'
import { configureStore } from '@reduxjs/toolkit'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { Provider } from 'react-redux'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import authReducer, { clearAuth, setCredentials } from '@/app/store/authSlice'
import uiReducer from '@/app/store/uiSlice'
import { ProtectedRoute } from '@/app/router/ProtectedRoute'
import {
  LOGIN_SUCCESS_STATE,
  OU_WEALTH_SPLASH_ENTRANCE_MS,
  OU_WEALTH_SPLASH_EXIT_MS,
  OU_WEALTH_SPLASH_REDUCED_MOTION_READY_MS,
} from '@/features/branding/loginSuccessSplash'
import { ROUTES } from '@/shared/constants/routes'
import { ROLE_MEMBER, type AuthUser } from '@/shared/types/auth'
import { lightTheme } from '@/theme/theme'
import { LoginSuccessSplashPage } from './LoginSuccessSplashPage'

const testUser: AuthUser = {
  id: 'u1',
  username: 'alice',
  email: 'alice@example.com',
  firstName: 'Alice',
  lastName: 'Member',
  fullName: 'Alice Member',
  roles: [ROLE_MEMBER],
  permissions: [],
  cooperativeIds: ['coop-1'],
}

function stubMatchMedia(options: { reducedMotion?: boolean; coarse?: boolean } = {}) {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    configurable: true,
    value: (query: string) => ({
      matches:
        (query.includes('prefers-reduced-motion: reduce') && Boolean(options.reducedMotion)) ||
        (query.includes('pointer: coarse') && Boolean(options.coarse)),
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }),
  })
}

function createStore(authenticated: boolean) {
  return configureStore({
    reducer: { auth: authReducer, ui: uiReducer },
    preloadedState: {
      auth: authenticated
        ? {
            user: testUser,
            accessToken: 'token-abc',
            selectedCooperativeId: 'coop-1',
            status: 'authenticated' as const,
          }
        : {
            user: null,
            accessToken: null,
            selectedCooperativeId: null,
            status: 'anonymous' as const,
          },
      ui: { sidebarOpen: false, themePreference: 'light' as const },
    },
  })
}

function renderSplash({
  authenticated = true,
  initialPath = ROUTES.loginSuccess,
  state,
}: {
  authenticated?: boolean
  initialPath?: string
  state?: unknown
} = {}) {
  const store = createStore(authenticated)
  const view = render(
    <Provider store={store}>
      <ThemeProvider theme={lightTheme}>
        <MemoryRouter
          initialEntries={[
            {
              pathname: initialPath,
              state,
            },
          ]}
        >
          <Routes>
            <Route element={<ProtectedRoute />}>
              <Route path={ROUTES.loginSuccess} element={<LoginSuccessSplashPage />} />
              <Route path={ROUTES.dashboard} element={<div>dashboard-route</div>} />
            </Route>
            <Route path={ROUTES.login} element={<div>login-route</div>} />
          </Routes>
        </MemoryRouter>
      </ThemeProvider>
    </Provider>,
  )
  return { ...view, store }
}

describe('LoginSuccessSplashPage', () => {
  beforeEach(() => {
    stubMatchMedia()
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.clearAllTimers()
    vi.useRealTimers()
  })

  it('shows the branded splash after a login event and not the dashboard yet', () => {
    renderSplash({ state: LOGIN_SUCCESS_STATE })
    const splash = screen.getByTestId('ouwealth-splash')
    expect(splash).toHaveAttribute('data-ready', 'false')
    expect(screen.getByText('Wealth')).toBeInTheDocument()
    expect(screen.getByText('COMMUNITY')).toBeInTheDocument()
    expect(screen.getByText(/Accumulate your wealth in an/)).toBeInTheDocument()
    expect(screen.getByText('instant')).toBeInTheDocument()
    expect(screen.queryByText('dashboard-route')).not.toBeInTheDocument()
    expect(screen.queryByText('PRESS ANY KEY TO CONTINUE')).not.toBeInTheDocument()
  })

  it('does not navigate when a key is pressed before the entrance animation finishes', async () => {
    renderSplash({ state: LOGIN_SUCCESS_STATE })
    fireEvent.keyDown(window, { key: 'Enter' })
    await act(async () => {
      vi.advanceTimersByTime(OU_WEALTH_SPLASH_ENTRANCE_MS - 1)
    })
    expect(screen.getByTestId('ouwealth-splash')).toHaveAttribute('data-ready', 'false')
    expect(screen.queryByText('dashboard-route')).not.toBeInTheDocument()
  })

  it('shows the continue prompt and auto-exits at the entrance gate with no interaction', async () => {
    renderSplash({ state: LOGIN_SUCCESS_STATE })
    await act(async () => {
      vi.advanceTimersByTime(OU_WEALTH_SPLASH_ENTRANCE_MS)
    })
    const splash = screen.getByTestId('ouwealth-splash')
    expect(splash).toHaveAttribute('data-ready', 'true')
    expect(splash).toHaveClass('is-exiting')
    expect(screen.getByText('PRESS ANY KEY TO CONTINUE')).toBeInTheDocument()
    expect(screen.queryByText('dashboard-route')).not.toBeInTheDocument()

    await act(async () => {
      vi.advanceTimersByTime(OU_WEALTH_SPLASH_EXIT_MS)
    })
    expect(screen.getByText('dashboard-route')).toBeInTheDocument()
    expect(screen.queryByTestId('ouwealth-splash')).not.toBeInTheDocument()
  })

  it('continues to the original post-login destination after a key press when ready', async () => {
    renderSplash({ state: LOGIN_SUCCESS_STATE })
    await act(async () => {
      vi.advanceTimersByTime(OU_WEALTH_SPLASH_ENTRANCE_MS)
    })
    expect(screen.getByTestId('ouwealth-splash')).toHaveAttribute('data-ready', 'true')
    expect(screen.getByText('PRESS ANY KEY TO CONTINUE')).toBeInTheDocument()

    fireEvent.keyDown(window, { key: 'Enter' })
    await act(async () => {
      vi.advanceTimersByTime(OU_WEALTH_SPLASH_EXIT_MS)
    })
    expect(screen.getByText('dashboard-route')).toBeInTheDocument()
    expect(screen.queryByTestId('ouwealth-splash')).not.toBeInTheDocument()
  })

  it('continues on click/tap anywhere after the animation is ready', async () => {
    renderSplash({ state: LOGIN_SUCCESS_STATE })
    await act(async () => {
      vi.advanceTimersByTime(OU_WEALTH_SPLASH_ENTRANCE_MS)
    })
    fireEvent.click(screen.getByTestId('ouwealth-splash'))
    await act(async () => {
      vi.advanceTimersByTime(OU_WEALTH_SPLASH_EXIT_MS)
    })
    expect(screen.getByText('dashboard-route')).toBeInTheDocument()
  })

  it('does not continue from a click before the animation is ready', async () => {
    renderSplash({ state: LOGIN_SUCCESS_STATE })
    fireEvent.click(screen.getByTestId('ouwealth-splash'))
    await act(async () => {
      vi.advanceTimersByTime(OU_WEALTH_SPLASH_EXIT_MS)
    })
    expect(screen.getByTestId('ouwealth-splash')).toBeInTheDocument()
    expect(screen.queryByText('dashboard-route')).not.toBeInTheDocument()
  })

  it('ignores repeated continue actions so only one navigation happens', async () => {
    renderSplash({ state: LOGIN_SUCCESS_STATE })
    await act(async () => {
      vi.advanceTimersByTime(OU_WEALTH_SPLASH_ENTRANCE_MS)
    })
    fireEvent.keyDown(window, { key: 'Enter' })
    fireEvent.keyDown(window, { key: 'Enter' })
    fireEvent.click(screen.getByTestId('ouwealth-splash'))
    await act(async () => {
      vi.advanceTimersByTime(OU_WEALTH_SPLASH_EXIT_MS)
    })
    expect(screen.getAllByText('dashboard-route')).toHaveLength(1)
    expect(screen.queryByTestId('ouwealth-splash')).not.toBeInTheDocument()
  })

  it('does not issue a network request when continuing to Dashboard', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
    renderSplash({ state: LOGIN_SUCCESS_STATE })
    await act(async () => {
      vi.advanceTimersByTime(OU_WEALTH_SPLASH_ENTRANCE_MS)
    })
    fireEvent.keyDown(window, { key: 'Enter' })
    await act(async () => {
      vi.advanceTimersByTime(OU_WEALTH_SPLASH_EXIT_MS)
    })
    expect(screen.getByText('dashboard-route')).toBeInTheDocument()
    expect(fetchSpy).not.toHaveBeenCalled()
    fetchSpy.mockRestore()
  })

  it('does not let a modifier-only key create a second navigation after auto-continue', async () => {
    renderSplash({ state: LOGIN_SUCCESS_STATE })
    await act(async () => {
      vi.advanceTimersByTime(OU_WEALTH_SPLASH_ENTRANCE_MS)
    })
    fireEvent.keyDown(window, { key: 'Shift' })
    fireEvent.keyDown(window, { key: 'Enter' })
    await act(async () => {
      vi.advanceTimersByTime(OU_WEALTH_SPLASH_EXIT_MS)
    })
    expect(screen.getAllByText('dashboard-route')).toHaveLength(1)
  })

  it('does not show the splash for an already authenticated visit to Dashboard', () => {
    renderSplash({ initialPath: ROUTES.dashboard, state: undefined })
    expect(screen.getByText('dashboard-route')).toBeInTheDocument()
    expect(screen.queryByTestId('ouwealth-splash')).not.toBeInTheDocument()
  })

  it('skips the splash when an authenticated user opens /login-success without a login event', () => {
    renderSplash({ state: undefined })
    expect(screen.getByText('dashboard-route')).toBeInTheDocument()
    expect(screen.queryByTestId('ouwealth-splash')).not.toBeInTheDocument()
  })

  it('shows the splash again after logout and a new login event', async () => {
    const { store, unmount } = renderSplash({ state: LOGIN_SUCCESS_STATE })
    expect(screen.getByTestId('ouwealth-splash')).toBeInTheDocument()

    await act(async () => {
      vi.advanceTimersByTime(OU_WEALTH_SPLASH_ENTRANCE_MS)
    })
    fireEvent.keyDown(window, { key: 'Enter' })
    await act(async () => {
      vi.advanceTimersByTime(OU_WEALTH_SPLASH_EXIT_MS)
    })
    expect(screen.getByText('dashboard-route')).toBeInTheDocument()

    act(() => {
      store.dispatch(clearAuth())
    })
    unmount()

    store.dispatch(setCredentials({ user: testUser, accessToken: 'token-2' }))
    render(
      <Provider store={store}>
        <ThemeProvider theme={lightTheme}>
          <MemoryRouter
            initialEntries={[
              { pathname: ROUTES.loginSuccess, state: { fromLogin: true, next: ROUTES.dashboard } },
            ]}
          >
            <Routes>
              <Route path={ROUTES.loginSuccess} element={<LoginSuccessSplashPage />} />
              <Route path={ROUTES.dashboard} element={<div>dashboard-route</div>} />
            </Routes>
          </MemoryRouter>
        </ThemeProvider>
      </Provider>,
    )

    expect(screen.getByTestId('ouwealth-splash')).toHaveAttribute('data-ready', 'false')
    expect(screen.queryByText('PRESS ANY KEY TO CONTINUE')).not.toBeInTheDocument()
  })

  it('registers a single keydown listener and removes it on unmount', async () => {
    const addSpy = vi.spyOn(window, 'addEventListener')
    const removeSpy = vi.spyOn(window, 'removeEventListener')
    const { unmount } = renderSplash({ state: LOGIN_SUCCESS_STATE })

    await act(async () => {
      vi.advanceTimersByTime(OU_WEALTH_SPLASH_ENTRANCE_MS)
    })

    const keydownAdds = addSpy.mock.calls.filter((call) => call[0] === 'keydown')
    expect(keydownAdds).toHaveLength(1)
    const handler = keydownAdds[0]?.[1]

    unmount()
    const keydownRemoves = removeSpy.mock.calls.filter((call) => call[0] === 'keydown')
    expect(keydownRemoves.some((call) => call[1] === handler)).toBe(true)

    addSpy.mockRestore()
    removeSpy.mockRestore()
  })

  it('does not duplicate keydown listeners across remounts', async () => {
    const addSpy = vi.spyOn(window, 'addEventListener')
    const first = renderSplash({ state: LOGIN_SUCCESS_STATE })
    await act(async () => {
      vi.advanceTimersByTime(OU_WEALTH_SPLASH_ENTRANCE_MS)
    })
    first.unmount()

    const second = renderSplash({ state: LOGIN_SUCCESS_STATE })
    await act(async () => {
      vi.advanceTimersByTime(OU_WEALTH_SPLASH_ENTRANCE_MS)
    })

    expect(addSpy.mock.calls.filter((call) => call[0] === 'keydown')).toHaveLength(2)
    second.unmount()
    addSpy.mockRestore()
  })

  it('shows tap copy on coarse pointers and still continues from a tap', async () => {
    stubMatchMedia({ coarse: true })
    renderSplash({ state: LOGIN_SUCCESS_STATE })
    await act(async () => {
      vi.advanceTimersByTime(OU_WEALTH_SPLASH_ENTRANCE_MS)
    })
    expect(screen.getByText('TAP ANYWHERE TO CONTINUE')).toBeInTheDocument()
    fireEvent.click(screen.getByTestId('ouwealth-splash'))
    await act(async () => {
      vi.advanceTimersByTime(OU_WEALTH_SPLASH_EXIT_MS)
    })
    expect(screen.getByText('dashboard-route')).toBeInTheDocument()
  })

  it('renders the final composition immediately for reduced motion and auto-continues', async () => {
    stubMatchMedia({ reducedMotion: true })
    renderSplash({ state: LOGIN_SUCCESS_STATE })
    expect(screen.getByTestId('ouwealth-splash')).toHaveAttribute('data-ready', 'false')

    fireEvent.keyDown(window, { key: 'Enter' })
    expect(screen.queryByText('dashboard-route')).not.toBeInTheDocument()

    await act(async () => {
      vi.advanceTimersByTime(OU_WEALTH_SPLASH_REDUCED_MOTION_READY_MS)
    })
    expect(screen.getByTestId('ouwealth-splash')).toHaveAttribute('data-ready', 'true')
    expect(screen.getByText('PRESS ANY KEY TO CONTINUE')).toBeInTheDocument()
    expect(screen.getByText('Wealth')).toBeInTheDocument()
    expect(screen.queryByText('dashboard-route')).not.toBeInTheDocument()

    await act(async () => {
      vi.advanceTimersByTime(OU_WEALTH_SPLASH_EXIT_MS)
    })
    expect(screen.getByText('dashboard-route')).toBeInTheDocument()
  })
})
