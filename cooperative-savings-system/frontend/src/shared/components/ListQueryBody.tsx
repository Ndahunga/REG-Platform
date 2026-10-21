import type { ReactNode } from 'react'
import { getErrorMessage } from '@/shared/api/client'
import { ErrorState } from './ErrorState'
import { LoadingState } from './LoadingState'

interface ListQueryBodyProps {
  isLoading: boolean
  isError: boolean
  error: unknown
  onRetry: () => void
  enabled?: boolean
  children: ReactNode
}

export function ListQueryBody({
  isLoading,
  isError,
  error,
  onRetry,
  enabled = true,
  children,
}: ListQueryBodyProps) {
  if (!enabled) return null
  if (isLoading) return <LoadingState variant="skeleton" rows={4} />
  if (isError) {
    return <ErrorState message={getErrorMessage(error)} onRetry={onRetry} />
  }
  return children
}
