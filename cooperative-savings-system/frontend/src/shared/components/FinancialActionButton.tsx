import { Button, Tooltip, type ButtonProps } from '@mui/material'
import { useTranslation } from 'react-i18next'
import { useCooperativeSubscription } from '@/features/subscription/useCooperativeSubscription'
import {
  useFinancialSubmitGuard,
  type FinancialSubmitGuard,
} from '@/shared/hooks/useFinancialSubmitGuard'

type FinancialActionButtonProps = ButtonProps & {
  /** When true, also disable if public health check fails. */
  requireServerReachable?: boolean
  /** Optional override of guard (e.g. shared parent hook). */
  guard?: FinancialSubmitGuard
}

/**
 * Submit/action button that disables when offline, the server is unreachable,
 * or the selected cooperative subscription is read-only.
 */
export function FinancialActionButton({
  requireServerReachable = false,
  guard: guardProp,
  disabled,
  children,
  ...rest
}: FinancialActionButtonProps) {
  const { t } = useTranslation()
  const localGuard = useFinancialSubmitGuard({ requireServerReachable })
  const guard = guardProp ?? localGuard
  const { readOnly, canWrite } = useCooperativeSubscription()
  const subscriptionBlocked = readOnly || !canWrite
  const blocked = !guard.canSubmit || subscriptionBlocked
  const title = subscriptionBlocked
    ? t('subscription.readOnlyAction')
    : blocked
      ? (guard.reason ?? undefined)
      : undefined

  const button = (
    <Button {...rest} disabled={disabled || blocked}>
      {children}
    </Button>
  )

  if (!title) return button

  return (
    <Tooltip title={title}>
      <span style={{ display: 'inline-flex' }}>{button}</span>
    </Tooltip>
  )
}
