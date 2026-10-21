import { ThemeProvider } from '@mui/material'
import { render, screen } from '@testing-library/react'
import type { ReactElement } from 'react'
import { describe, expect, it } from 'vitest'
import { darkTheme, lightTheme } from '@/theme/theme'
import {
  BRAND_LOGO_ALT,
  BRAND_LOGO_ON_DARK_SRC,
  BRAND_LOGO_SRC,
  BrandLogo,
  resolveBrandLogoSrc,
} from './BrandLogo'

function renderLogo(ui: ReactElement, mode: 'light' | 'dark' = 'light') {
  return render(
    <ThemeProvider theme={mode === 'dark' ? darkTheme : lightTheme}>{ui}</ThemeProvider>,
  )
}

describe('resolveBrandLogoSrc', () => {
  it('maps surface darkness to the canonical wordmark paths', () => {
    expect(resolveBrandLogoSrc(false)).toBe(BRAND_LOGO_SRC)
    expect(resolveBrandLogoSrc(true)).toBe(BRAND_LOGO_ON_DARK_SRC)
  })
})

describe('BrandLogo', () => {
  it('uses the normal wordmark on a light surface', () => {
    renderLogo(<BrandLogo />)
    const img = screen.getByRole('img', { name: BRAND_LOGO_ALT })
    expect(img).toHaveAttribute('src', BRAND_LOGO_SRC)
  })

  it('uses the on-dark wordmark when theme is dark and onDark is omitted', () => {
    renderLogo(<BrandLogo />, 'dark')
    expect(screen.getByRole('img', { name: BRAND_LOGO_ALT })).toHaveAttribute(
      'src',
      BRAND_LOGO_ON_DARK_SRC,
    )
  })

  it('lets explicit onDark override a light theme', () => {
    renderLogo(<BrandLogo onDark />, 'light')
    expect(screen.getByRole('img', { name: BRAND_LOGO_ALT })).toHaveAttribute(
      'src',
      BRAND_LOGO_ON_DARK_SRC,
    )
  })

  it('lets explicit onDark={false} override a dark theme', () => {
    renderLogo(<BrandLogo onDark={false} />, 'dark')
    expect(screen.getByRole('img', { name: BRAND_LOGO_ALT })).toHaveAttribute(
      'src',
      BRAND_LOGO_SRC,
    )
  })

  it('keeps width automatic so the aspect ratio is not forced', () => {
    renderLogo(<BrandLogo size={40} />)
    const img = screen.getByRole('img', { name: BRAND_LOGO_ALT })
    expect(img).toHaveStyle({ width: 'auto', height: '40px' })
  })

  it('uses the default brand alt text', () => {
    renderLogo(<BrandLogo />)
    expect(screen.getByRole('img', { name: BRAND_LOGO_ALT })).toBeInTheDocument()
    expect(screen.queryByText(BRAND_LOGO_ALT)).not.toBeInTheDocument()
  })

  it('renders a visible Wealth / COMMUNITY lockup with the orange mark', () => {
    renderLogo(<BrandLogo variant="lockup" onDark />)
    const lockup = screen.getByRole('img', { name: BRAND_LOGO_ALT })
    expect(lockup).not.toHaveAttribute('src')
    expect(screen.getByText('Wealth')).toBeInTheDocument()
    expect(screen.getByText('COMMUNITY')).toBeInTheDocument()
    expect(lockup.querySelector('svg')).not.toBeNull()
  })
})
