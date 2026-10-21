import AccountBalanceWalletIcon from '@mui/icons-material/AccountBalanceWallet'
import FavoriteIcon from '@mui/icons-material/Favorite'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import GavelIcon from '@mui/icons-material/Gavel'
import PaymentsIcon from '@mui/icons-material/Payments'
import PercentIcon from '@mui/icons-material/Percent'
import SavingsIcon from '@mui/icons-material/Savings'
import { Box, ButtonBase, Collapse, Grid, Typography, useMediaQuery } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useAppSelector } from '@/app/store/hooks'
import { getErrorMessage } from '@/shared/api/client'
import { fetchMemberFinancialSummary } from '@/shared/api/members'
import { ErrorState } from '@/shared/components/ErrorState'
import { MetricCard } from '@/shared/components/MetricCard'
import { formatMoney } from '@/shared/utils/formatMoney'

const METRIC_COLS = { xs: 12, sm: 6, md: 4, lg: 2.4 }

const HOVER_CAPABLE = '(pointer: fine) and (hover: hover)'
const REDUCED_MOTION = '(prefers-reduced-motion: reduce)'
const COLLAPSE_MS = 200

interface MyMemberStatusSectionProps {
  cooperativeId: string
  compact?: boolean
  /** Start expanded. Defaults to collapsed; the state is never persisted. */
  initialExpanded?: boolean
}

/**
 * Personal savings status — officers are still members and pay contributions.
 * The member's quick actions are a separate section ({@link MemberQuickActionsSection}) rendered above this one.
 *
 * The header is always visible and toggles the cards: a click/tap/Enter/Space pins them open until clicked
 * again. On devices that genuinely hover (fine pointer), hovering also reveals them temporarily; leaving
 * collapses them again unless they were clicked open.
 */
export function MyMemberStatusSection({
  cooperativeId,
  compact = false,
  initialExpanded = false,
}: MyMemberStatusSectionProps) {
  const { t } = useTranslation()
  const user = useAppSelector((s) => s.auth.user)
  const baseId = useId()
  const headerId = `${baseId}-header`
  const titleId = `${baseId}-title`
  const hintId = `${baseId}-hint`
  const contentId = `${baseId}-content`

  // All hooks run unconditionally and in a fixed order, regardless of the expanded state.
  const canHover = useMediaQuery(HOVER_CAPABLE)
  const reducedMotion = useMediaQuery(REDUCED_MOTION)
  const [clickedOpen, setClickedOpen] = useState(initialExpanded)
  const [hovered, setHovered] = useState(false)
  // After a click closes a section the pointer is still over, hover must not instantly reopen it.
  const [hoverSuppressed, setHoverSuppressed] = useState(false)
  const expanded = clickedOpen || (canHover && hovered && !hoverSuppressed)

  const toggle = () => {
    if (clickedOpen) {
      setClickedOpen(false)
      setHoverSuppressed(true)
    } else {
      setClickedOpen(true)
    }
  }

  const summaryQuery = useQuery({
    queryKey: ['members', 'financial-summary', cooperativeId, user?.id],
    queryFn: () => fetchMemberFinancialSummary(cooperativeId, user!.id),
    enabled: Boolean(cooperativeId && user?.id),
  })

  const summary = summaryQuery.data
  const currency = summary?.currency || 'RWF'
  const loading = summaryQuery.isLoading
  const money = (value: string | number | null | undefined) =>
    formatMoney(value ?? 0, { currency })

  const outstandingLoan =
    (Number(summary?.outstandingLoanPrincipal) || 0) +
    (Number(summary?.outstandingLoanInterest) || 0)

  return (
    <Box
      data-testid="my-member-status"
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => {
        setHovered(false)
        setHoverSuppressed(false)
      }}
      sx={{
        border: '1px solid',
        borderColor: 'divider',
        borderRadius: 2,
        bgcolor: 'rgba(27, 77, 140, 0.04)',
        overflow: 'hidden',
        transition: reducedMotion ? 'none' : 'border-color 150ms ease',
        '&:hover': { borderColor: 'text.disabled' },
      }}
    >
      <Typography
        component="h2"
        variant={compact ? 'subtitle1' : 'h6'}
        sx={{ fontSize: { xs: '1rem', md: '1.1rem' }, fontWeight: 600, m: 0 }}
        id={headerId}
      >
        <ButtonBase
          onClick={toggle}
          aria-expanded={expanded}
          aria-controls={contentId}
          aria-labelledby={titleId}
          aria-describedby={hintId}
          sx={{
            display: 'flex',
            width: '100%',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 2,
            textAlign: 'left',
            font: 'inherit',
            color: 'inherit',
            px: { xs: 2, md: 2.5 },
            py: 1.5,
            cursor: 'pointer',
            transition: reducedMotion ? 'none' : 'background-color 150ms ease',
            '&:hover': { bgcolor: 'action.hover' },
            '&.Mui-focusVisible': {
              outline: '2px solid',
              outlineColor: 'primary.main',
              outlineOffset: '-2px',
            },
          }}
        >
          <Box component="span" sx={{ display: 'block', minWidth: 0 }}>
            <Box component="span" id={titleId} sx={{ display: 'block', fontWeight: 600 }}>
              {t('dashboard.member.myStatusTitle')}
            </Box>
            <Typography
              component="span"
              id={hintId}
              variant="body2"
              color="text.secondary"
              sx={{ display: 'block', fontSize: '0.875rem', fontWeight: 400, maxWidth: 720 }}
            >
              {t('dashboard.member.myStatusHint')}
            </Typography>
          </Box>
          <ExpandMoreIcon
            aria-hidden="true"
            sx={{
              flexShrink: 0,
              transform: expanded ? 'rotate(0deg)' : 'rotate(-90deg)',
              transition: reducedMotion ? 'none' : `transform ${COLLAPSE_MS}ms ease`,
            }}
          />
        </ButtonBase>
      </Typography>

      <Box id={contentId}>
        <Collapse in={expanded} timeout={reducedMotion ? 0 : COLLAPSE_MS} unmountOnExit>
          <Box
            role="region"
            aria-labelledby={headerId}
            sx={{ px: { xs: 2, md: 2.5 }, pb: { xs: 2, md: 2.5 }, pt: 0.5 }}
          >
            {summaryQuery.isError ? (
              <Box sx={{ mb: 2 }}>
                <ErrorState
                  message={getErrorMessage(summaryQuery.error)}
                  onRetry={() => void summaryQuery.refetch()}
                />
              </Box>
            ) : null}

            <Grid container spacing={2}>
              <Grid size={METRIC_COLS}>
                <MetricCard
            scale="dashboard"
                  label={t('dashboard.member.totalContributions')}
                  value={money(summary?.actualContributions)}
                  icon={<SavingsIcon fontSize="small" />}
                  accent="blue"
                  loading={loading}
                />
              </Grid>
              <Grid size={METRIC_COLS}>
                <MetricCard
            scale="dashboard"
                  label={t('dashboard.member.outstandingLoan')}
                  value={money(outstandingLoan)}
                  icon={<AccountBalanceWalletIcon fontSize="small" />}
                  accent="orange"
                  loading={loading}
                />
              </Grid>
              <Grid size={METRIC_COLS}>
                <MetricCard
            scale="dashboard"
                  label={t('dashboard.member.outstandingFines')}
                  value={money(summary?.unpaidFines)}
                  icon={<GavelIcon fontSize="small" />}
                  accent="red"
                  loading={loading}
                />
              </Grid>
              <Grid size={METRIC_COLS}>
                <MetricCard
            scale="dashboard"
                  label={t('dashboard.member.socialContributions')}
                  value={money(summary?.socialContributions)}
                  icon={<FavoriteIcon fontSize="small" />}
                  accent="purple"
                  loading={loading}
                />
              </Grid>
              <Grid size={METRIC_COLS}>
                <MetricCard
            scale="dashboard"
                  label={t('dashboard.member.contributionPercentage')}
                  value={
                    summary?.contributionPercentage != null && summary.contributionPercentage !== ''
                      ? `${Number(summary.contributionPercentage).toFixed(2)}%`
                      : '—'
                  }
                  icon={<PercentIcon fontSize="small" />}
                  accent="green"
                  loading={loading}
                />
              </Grid>
              <Grid size={METRIC_COLS}>
                <MetricCard
            scale="dashboard"
                  label={t('dashboard.member.sharesHeld')}
                  value={summary?.sharesHeld != null ? String(summary.sharesHeld) : '—'}
                  icon={<PaymentsIcon fontSize="small" />}
                  accent="blue"
                  loading={loading}
                />
              </Grid>
              <Grid size={METRIC_COLS}>
                <MetricCard
            scale="dashboard"
                  label={t('dashboard.member.currentShareValue')}
                  value={money(summary?.currentShareValue)}
                  icon={<PaymentsIcon fontSize="small" />}
                  accent="green"
                  loading={loading}
                />
              </Grid>
              <Grid size={METRIC_COLS}>
                <MetricCard
            scale="dashboard"
                  label={t('dashboard.member.totalShareValue')}
                  value={money(summary?.totalShareValue)}
                  icon={<PaymentsIcon fontSize="small" />}
                  accent="purple"
                  loading={loading}
                />
              </Grid>
            </Grid>
          </Box>
        </Collapse>
      </Box>
    </Box>
  )
}
