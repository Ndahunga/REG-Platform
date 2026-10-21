import { ThemeProvider } from '@mui/material'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { lightTheme } from '@/theme/theme'
import { RouteErrorBoundary } from './RouteErrorBoundary'

function Boom(): never {
  throw new Error('test render crash')
}

describe('RouteErrorBoundary', () => {
  beforeEach(() => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('shows a visible fallback instead of a blank screen', () => {
    render(
      <MemoryRouter>
        <ThemeProvider theme={lightTheme}>
          <RouteErrorBoundary>
            <Boom />
          </RouteErrorBoundary>
        </ThemeProvider>
      </MemoryRouter>,
    )

    expect(screen.getByText('Something went wrong')).toBeInTheDocument()
    expect(
      screen.getByText('This page could not be displayed. You can try again or go back to loans.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to loans' })).toHaveAttribute('href', '/loans')
    expect(screen.queryByText('test render crash')).not.toBeInTheDocument()
  })
})
