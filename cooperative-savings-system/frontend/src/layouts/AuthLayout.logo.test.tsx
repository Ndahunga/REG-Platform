import { ThemeProvider } from '@mui/material'
import { configureStore } from '@reduxjs/toolkit'
import { render, screen } from '@testing-library/react'
import { Provider } from 'react-redux'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import authReducer from '@/app/store/authSlice'
import uiReducer from '@/app/store/uiSlice'
import { AuthBrand } from '@/shared/components/AuthBrand'
import { BRAND_LOGO_ALT, BRAND_LOGO_ON_DARK_SRC, BRAND_LOGO_SRC } from '@/shared/components/BrandLogo'
import { darkTheme, lightTheme } from '@/theme/theme'
import { AuthLayout } from './AuthLayout'

function renderAuth(mode: 'light' | 'dark') {
  const store = configureStore({
    reducer: { auth: authReducer, ui: uiReducer },
    preloadedState: {
      auth: {
        user: null,
        accessToken: null,
        selectedCooperativeId: null,
        status: 'anonymous' as const,
      },
      ui: { sidebarOpen: false, themePreference: mode },
    },
  })

  return render(
    <Provider store={store}>
      <ThemeProvider theme={mode === 'dark' ? darkTheme : lightTheme}>
        <MemoryRouter>
          <Routes>
            <Route element={<AuthLayout />}>
              <Route path="*" element={<AuthBrand title="OuWealth Community" />} />
            </Route>
          </Routes>
        </MemoryRouter>
      </ThemeProvider>
    </Provider>,
  )
}

/** The auth wordmark is the raster image; the compact footer adds a separate SVG lockup with the same name. */
const rasterLogo = () =>
  screen.getAllByRole('img', { name: BRAND_LOGO_ALT }).find((el) => el.tagName === 'IMG') as HTMLElement

describe('Auth logo surface', () => {
  it('uses the normal wordmark on the Light Mode auth canvas', () => {
    renderAuth('light')
    expect(rasterLogo()).toHaveAttribute('src', BRAND_LOGO_SRC)
  })

  it('uses the on-dark wordmark on the Dark Mode auth canvas', () => {
    renderAuth('dark')
    expect(rasterLogo()).toHaveAttribute('src', BRAND_LOGO_ON_DARK_SRC)
  })
})
