import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SnackbarProvider } from 'notistack'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '@/i18n'
import {
  fetchContributionDueDay,
  fetchContributionPeriodPreview,
  setContributionDueDay,
} from '@/shared/api/contributions'
import type { ContributionDueDay } from '@/shared/types/contribution'
import { ContributionDueDayCard } from './ContributionDueDayCard'
import { MemberContributionSubmitPanel } from './MemberContributionSubmitPanel'
import { ObligationDetails } from './ObligationDetails'
import { bpsLabel, hasExpectedSplit, hasOverdue, parseDueDay } from './obligationHelpers'

vi.mock('@/shared/api/contributions', () => ({
  fetchContributionDueDay: vi.fn(),
  setContributionDueDay: vi.fn(),
  fetchContributionPeriodPreview: vi.fn(),
  submitRegularContribution: vi.fn(),
}))
vi.mock('@/shared/api/files', () => ({ uploadCooperativeFile: vi.fn() }))

function wrap(node: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <SnackbarProvider>{node}</SnackbarProvider>
    </QueryClientProvider>,
  )
}

const configured: ContributionDueDay = { configured: true, currentDueDay: 10, pending: null, history: [] }
const legacy: ContributionDueDay = { configured: false, currentDueDay: null, pending: null, history: [] }

describe('obligation helpers', () => {
  it('shows overdue only for a positive overdue amount', () => {
    expect(hasOverdue({ overdue: true, overdueAmount: '1000' })).toBe(true)
    expect(hasOverdue({ overdue: true, overdueAmount: '0' })).toBe(false)
    expect(hasOverdue({ overdue: false, overdueAmount: '1000' })).toBe(false)
    expect(hasOverdue({})).toBe(false)
  })

  it('shows the expected split only for rotating or hybrid obligations with both components', () => {
    const base = { expectedRotatingAmount: '1000', expectedRetainedAmount: '500' }
    expect(hasExpectedSplit({ ...base, operatingModel: 'HYBRID' })).toBe(true)
    expect(hasExpectedSplit({ ...base, operatingModel: 'ROTATING' })).toBe(true)
    expect(hasExpectedSplit({ ...base, operatingModel: 'RETAINED' })).toBe(false)
    expect(hasExpectedSplit({ operatingModel: 'HYBRID' })).toBe(false)
  })

  it('formats basis points and parses due days strictly', () => {
    expect(bpsLabel(7000)).toBe('70%')
    expect(bpsLabel(3333)).toBe('33.33%')
    expect(bpsLabel(null)).toBe('')
    expect(parseDueDay('1')).toBe(1)
    expect(parseDueDay('31')).toBe(31)
    expect(parseDueDay('0')).toBeNull()
    expect(parseDueDay('32')).toBeNull()
    expect(parseDueDay('1.5')).toBeNull()
    expect(parseDueDay('')).toBeNull()
  })
})

describe('ObligationDetails', () => {
  afterEach(async () => {
    await i18n.changeLanguage('en')
  })

  it('renders due date, overdue amount and the split labelled expected, not received', () => {
    render(
      <ObligationDetails
        fields={{
          dueDate: '2026-06-10',
          overdue: true,
          overdueAmount: '1500',
          operatingModel: 'HYBRID',
          rotatingBps: 7000,
          retainedBps: 3000,
          expectedRotatingAmount: '2100',
          expectedRetainedAmount: '900',
        }}
      />,
    )
    expect(screen.getByTestId('contribution-due-date')).toHaveTextContent('Due 2026-06-10')
    expect(screen.getByTestId('contribution-overdue')).toHaveTextContent(/Overdue/)
    const split = screen.getByTestId('contribution-expected-split')
    expect(split).toHaveTextContent(/Expected \(not received\)/)
    expect(split).toHaveTextContent(/70%/)
    expect(split).toHaveTextContent(/30%/)
  })

  it('renders nothing for a legacy obligation without due date, overdue or split', () => {
    const { container } = render(<ObligationDetails fields={{ operatingModel: 'RETAINED' }} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('does not mark an obligation overdue without an overdue amount', () => {
    render(<ObligationDetails fields={{ dueDate: '2026-06-10', overdue: false, overdueAmount: '0' }} />)
    expect(screen.queryByTestId('contribution-overdue')).not.toBeInTheDocument()
  })

  it('is localized in Kinyarwanda', async () => {
    await i18n.changeLanguage('rw')
    render(<ObligationDetails fields={{ dueDate: '2026-06-10', overdue: true, overdueAmount: '1500' }} />)
    expect(screen.getByTestId('contribution-due-date')).toHaveTextContent('Itariki ntarengwa: 2026-06-10')
    expect(screen.getByTestId('contribution-overdue')).toHaveTextContent(/Yarengeje igihe/)
  })
})

describe('ContributionDueDayCard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(fetchContributionDueDay).mockResolvedValue(configured)
    vi.mocked(setContributionDueDay).mockResolvedValue({ ...configured, pending: null })
  })

  it('shows the due day to members without a way to change it', async () => {
    wrap(<ContributionDueDayCard cooperativeId="coop-1" canManage={false} />)
    expect(await screen.findByTestId('contribution-due-day-current')).toHaveTextContent('day 10')
    expect(screen.queryByRole('button', { name: 'Save due day' })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('New due day (1-31)')).not.toBeInTheDocument()
  })

  it('says no due day is confirmed for a legacy scheme and promises no backdated arrears', async () => {
    vi.mocked(fetchContributionDueDay).mockResolvedValue(legacy)
    wrap(<ContributionDueDayCard cooperativeId="coop-1" canManage />)
    expect(await screen.findByTestId('contribution-due-day-current')).toHaveTextContent(/No due day has been confirmed/)
    expect(screen.getByText(/never creates arrears for past months/)).toBeInTheDocument()
  })

  it('shows a pending change', async () => {
    vi.mocked(fetchContributionDueDay).mockResolvedValue({
      ...configured,
      pending: { effectiveYear: 2026, effectiveMonth: 7, dueDay: 20 },
    })
    wrap(<ContributionDueDayCard cooperativeId="coop-1" canManage />)
    expect(await screen.findByTestId('contribution-due-day-pending')).toHaveTextContent('Day 20 takes effect from 2026-07')
  })

  it('rejects an invalid day and requires explicit confirmation before saving', async () => {
    const user = userEvent.setup()
    wrap(<ContributionDueDayCard cooperativeId="coop-1" canManage />)
    await screen.findByTestId('contribution-due-day-current')
    const save = screen.getByRole('button', { name: 'Save due day' })

    await user.type(screen.getByLabelText('New due day (1-31)'), '45')
    await user.click(save)
    expect(await screen.findByText('Enter a whole number from 1 to 31.')).toBeInTheDocument()
    expect(setContributionDueDay).not.toHaveBeenCalled()

    await user.clear(screen.getByLabelText('New due day (1-31)'))
    await user.type(screen.getByLabelText('New due day (1-31)'), '15')
    await user.click(save)
    expect(await screen.findByText('Please confirm before saving.')).toBeInTheDocument()
    expect(setContributionDueDay).not.toHaveBeenCalled()
  })

  it('saves a confirmed due day', async () => {
    const user = userEvent.setup()
    wrap(<ContributionDueDayCard cooperativeId="coop-1" canManage />)
    await screen.findByTestId('contribution-due-day-current')
    await user.type(screen.getByLabelText('New due day (1-31)'), '15')
    await user.click(screen.getByRole('checkbox'))
    await user.click(screen.getByRole('button', { name: 'Save due day' }))
    await waitFor(() =>
      expect(setContributionDueDay).toHaveBeenCalledWith('coop-1', { dueDay: 15, confirmed: true }),
    )
  })

  it('shows an error state when the due day cannot be loaded', async () => {
    vi.mocked(fetchContributionDueDay).mockRejectedValue(new Error('boom'))
    wrap(<ContributionDueDayCard cooperativeId="coop-1" canManage />)
    expect(await screen.findByRole('button', { name: /retry|try again/i })).toBeInTheDocument()
    expect(screen.queryByTestId('contribution-due-day-current')).not.toBeInTheDocument()
  })

  it('shows a loading state first', () => {
    vi.mocked(fetchContributionDueDay).mockReturnValue(new Promise(() => {}))
    wrap(<ContributionDueDayCard cooperativeId="coop-1" canManage />)
    expect(screen.queryByTestId('contribution-due-day-current')).not.toBeInTheDocument()
  })
})

describe('MemberContributionSubmitPanel obligation preview', () => {
  it('shows the due date and the expected split without calling it received money', async () => {
    vi.mocked(fetchContributionPeriodPreview).mockResolvedValue({
      year: 2026,
      month: 6,
      shareCount: 3,
      requiredAmount: '3000',
      paidAmount: '0',
      remainingAmount: '3000',
      status: 'PENDING',
      canSubmit: true,
      dueDate: '2026-06-10',
      operatingModel: 'HYBRID',
      rotatingBps: 7000,
      retainedBps: 3000,
      expectedRotatingAmount: '2100',
      expectedRetainedAmount: '900',
    } as never)
    wrap(<MemberContributionSubmitPanel cooperativeId="coop-1" />)
    expect(await screen.findByTestId('contribution-preview-due-date')).toHaveTextContent('Due 2026-06-10')
    expect(screen.getByTestId('contribution-expected-split')).toHaveTextContent(/Expected \(not received\)/)
  })
})
