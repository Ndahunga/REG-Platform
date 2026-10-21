import { Component, type ErrorInfo, type ReactNode } from 'react'
import { Box, Button, Paper, Stack, Typography } from '@mui/material'
import { Link as RouterLink } from 'react-router-dom'
import i18n from '@/i18n'
import { ROUTES } from '@/shared/constants/routes'

interface RouteErrorBoundaryProps {
  children: ReactNode
  resetKey?: string
}

interface RouteErrorBoundaryState {
  hasError: boolean
}

/**
 * Small route-level boundary so a render exception shows a visible fallback
 * instead of unmounting the whole application.
 */
export class RouteErrorBoundary extends Component<
  RouteErrorBoundaryProps,
  RouteErrorBoundaryState
> {
  state: RouteErrorBoundaryState = { hasError: false }

  static getDerivedStateFromError(): RouteErrorBoundaryState {
    return { hasError: true }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('Unexpected route render error', error.message, info.componentStack)
  }

  componentDidUpdate(prevProps: RouteErrorBoundaryProps) {
    if (this.state.hasError && prevProps.resetKey !== this.props.resetKey) {
      this.setState({ hasError: false })
    }
  }

  private handleRetry = () => {
    this.setState({ hasError: false })
  }

  render() {
    if (!this.state.hasError) {
      return this.props.children
    }

    return (
      <Box sx={{ p: { xs: 2, md: 3 } }}>
        <Paper
          elevation={0}
          sx={{
            p: { xs: 3, md: 4 },
            border: '1px solid',
            borderColor: 'error.light',
            bgcolor: 'background.paper',
          }}
        >
          <Typography variant="h6" gutterBottom>
            {i18n.t('errors.boundaryTitle')}
          </Typography>
          <Typography color="text.secondary" sx={{ mb: 2 }}>
            {i18n.t('errors.boundaryDescription')}
          </Typography>
          <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
            <Button variant="contained" onClick={this.handleRetry}>
              {i18n.t('errors.boundaryTryAgain')}
            </Button>
            <Button component={RouterLink} to={ROUTES.loans} variant="outlined">
              {i18n.t('loans.backToList')}
            </Button>
          </Stack>
        </Paper>
      </Box>
    )
  }
}
