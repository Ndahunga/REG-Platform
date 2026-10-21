import { Tooltip, type ButtonProps } from '@mui/material'
import { useTranslation } from 'react-i18next'
import { useCooperativeSubscription } from '@/features/subscription/useCooperativeSubscription'
import { FinancialActionButton } from './FinancialActionButton'

export function SubscriptionAwareButton({
  disabled,
  children,
  ...rest
}: ButtonProps) {
  const { t } = useTranslation()
  const { canWrite, readOnly } = useCooperativeSubscription()
  const blocked = readOnly || !canWrite
  const title = blocked ? t('subscription.readOnlyAction') : undefined
  const button = (
    <FinancialActionButton {...rest} disabled={disabled || blocked}>
      {children}
    </FinancialActionButton>
  )
  if (!title) return button
  return (
    <Tooltip title={title}>
      <span style={{ display: 'inline-flex' }}>{button}</span>
    </Tooltip>
  )
}
