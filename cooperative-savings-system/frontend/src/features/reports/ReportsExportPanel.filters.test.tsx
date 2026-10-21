import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { configureStore } from '@reduxjs/toolkit'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SnackbarProvider } from 'notistack'
import { Provider } from 'react-redux'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import authReducer from '@/app/store/authSlice'
import uiReducer from '@/app/store/uiSlice'
import { fetchMembers } from '@/shared/api/members'
import { exportReport, shareReportViaWhatsApp } from '@/shared/api/reports'
import { fetchSubscription } from '@/shared/api/subscription'
import { ROLE_PRESIDENT } from '@/shared/types/auth'
import { ReportsExportPanel } from './ReportsExportPanel'

vi.mock('@/shared/api/reports', () => ({
  fetchReportTypes: vi.fn().mockResolvedValue([
    { type: 'CONTRIBUTIONS', label: 'Contributions', selfScoped: false },
    { type: 'INVESTMENTS', label: 'Investments', selfScoped: false },
    { type: 'FULL_FINANCIAL', label: 'Full Financial', selfScoped: false },
  ]),
  fetchWhatsAppStatus: vi.fn().mockResolvedValue({ configured: true }),
  exportReport: vi.fn(),
  shareReportViaWhatsApp: vi.fn(),
}))

vi.mock('@/shared/api/members', () => ({
  fetchMembers: vi.fn(),
}))

vi.mock('@/shared/api/subscription', () => ({
  cooperativeSubscriptionQueryKey: (id: string) => ['cooperatives', id, 'subscription'],
  fetchSubscription: vi.fn(),
}))

const alice = { userId: 'm-alice', firstName: 'Alice', lastName: 'Uwase', fullName: 'Alice Uwase' }

function renderPanel(initialReportType?: string) {
  vi.mocked(fetchSubscription).mockResolvedValue({
    id: 's1',
    cooperativeId: 'coop-1',
    status: 'TRIAL',
    storedStatus: 'TRIAL',
    effectiveStatus: 'TRIAL',
    writeAllowed: true,
    usable: true,
  })
  const store = configureStore({
    reducer: { auth: authReducer, ui: uiReducer },
    preloadedState: {
      auth: {
        user: {
          id: 'u1',
          username: 'pat',
          email: 'pat@test.local',
          firstName: 'Pat',
          lastName: 'Leader',
          fullName: 'Pat Leader',
          roles: [ROLE_PRESIDENT],
          permissions: ['REPORT_READ'],
          cooperativeIds: ['coop-1'],
        },
        accessToken: 'token',
        selectedCooperativeId: 'coop-1',
        status: 'authenticated' as const,
      },
      ui: { sidebarOpen: false, themePreference: 'light' as const },
    },
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <Provider store={store}>
      <QueryClientProvider client={client}>
        <SnackbarProvider>
          <ReportsExportPanel cooperativeId="coop-1" initialReportType={initialReportType} />
        </SnackbarProvider>
      </QueryClientProvider>
    </Provider>,
  )
}

const advancedSubmit = async () =>
  (await screen.findAllByRole('button', { name: 'Download PDF' })).find(
    (button) => button.getAttribute('type') === 'submit',
  )!

const filterSelect = (name: string) => screen.findByRole('combobox', { name })

async function choose(user: ReturnType<typeof userEvent.setup>, name: string, option: string) {
  await user.click(await filterSelect(name))
  await user.click(await screen.findByRole('option', { name: option }))
}

describe('ReportsExportPanel filters', { timeout: 30_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(fetchMembers).mockResolvedValue({ content: [alice] } as never)
    vi.mocked(exportReport).mockResolvedValue({ filename: 'report.pdf' })
    vi.mocked(shareReportViaWhatsApp).mockResolvedValue({ filename: 'report.pdf' } as never)
  })

  it('shows "All" in the Member and Status filters before anything is chosen', async () => {
    renderPanel('CONTRIBUTIONS')
    expect(await filterSelect('Member')).toHaveTextContent('All')
    expect(await filterSelect('Status')).toHaveTextContent('All')
  })

  it('shows "All" in the Transaction type filter for a ledger-style report', async () => {
    renderPanel('FULL_FINANCIAL')
    expect(await filterSelect('Transaction type')).toHaveTextContent('All')
  })

  it('shows the chosen member and status, and "All" again after re-selecting All', async () => {
    const user = userEvent.setup()
    renderPanel('CONTRIBUTIONS')

    await choose(user, 'Member', 'Alice Uwase')
    await choose(user, 'Status', 'Partially paid')
    expect(await filterSelect('Member')).toHaveTextContent('Alice Uwase')
    expect(await filterSelect('Status')).toHaveTextContent('Partially paid')

    await choose(user, 'Member', 'All')
    await choose(user, 'Status', 'All')
    expect(await filterSelect('Member')).toHaveTextContent('All')
    expect(await filterSelect('Status')).toHaveTextContent('All')
  })

  it('shows the translated transaction type after choosing it', async () => {
    const user = userEvent.setup()
    renderPanel('FULL_FINANCIAL')
    await choose(user, 'Transaction type', 'Fine payment')
    expect(await filterSelect('Transaction type')).toHaveTextContent('Fine payment')
  })

  it('offers only the statuses that belong to the selected report type', async () => {
    const user = userEvent.setup()
    renderPanel('INVESTMENTS')
    await user.click(await filterSelect('Status'))
    const options = within(await screen.findByRole('listbox'))
      .getAllByRole('option')
      .map((option) => option.textContent)
    expect(options).toContain('Partially returned')
    expect(options).not.toContain('Paid')
  })

  it('exports with every "All" filter omitted (null), never sending an "ALL" value', async () => {
    const user = userEvent.setup()
    renderPanel('CONTRIBUTIONS')
    await filterSelect('Member')
    await user.click(await advancedSubmit())

    await waitFor(() => expect(exportReport).toHaveBeenCalledTimes(1))
    const payload = vi.mocked(exportReport).mock.calls[0][1]
    expect(payload).toMatchObject({
      reportType: 'CONTRIBUTIONS',
      memberUserId: null,
      status: null,
      transactionType: null,
    })
    expect(JSON.stringify(payload)).not.toMatch(/"ALL"/)
  })

  it('exports a specific member and status when they are chosen', async () => {
    const user = userEvent.setup()
    renderPanel('CONTRIBUTIONS')
    await choose(user, 'Member', 'Alice Uwase')
    await choose(user, 'Status', 'Paid')
    await user.click(await advancedSubmit())

    await waitFor(() => expect(exportReport).toHaveBeenCalledTimes(1))
    expect(vi.mocked(exportReport).mock.calls[0][1]).toMatchObject({
      memberUserId: 'm-alice',
      status: 'PAID',
    })
  })

  it('drops a status that belongs to the previous report type when the type changes', async () => {
    const user = userEvent.setup()
    renderPanel('CONTRIBUTIONS')
    await choose(user, 'Status', 'Paid')
    await choose(user, 'Report type', 'Investments')
    expect(await filterSelect('Status')).toHaveTextContent('All')
    await user.click(await advancedSubmit())

    await waitFor(() => expect(exportReport).toHaveBeenCalledTimes(1))
    expect(vi.mocked(exportReport).mock.calls[0][1]).toMatchObject({
      reportType: 'INVESTMENTS',
      status: null,
    })
  })

  it('requires a report type: the advanced submit is disabled and nothing is exported', async () => {
    renderPanel()
    const submit = await advancedSubmit()
    expect(submit).toBeDisabled()
    fireEvent.submit(submit.closest('form')!)
    expect(exportReport).not.toHaveBeenCalled()
  })

  it('blocks export and share when the timeline is invalid, with an inline message', async () => {
    renderPanel('CONTRIBUTIONS')
    fireEvent.change(await screen.findByLabelText(/From date/), { target: { value: '2025-06-30' } })
    fireEvent.change(screen.getByLabelText(/To date/), { target: { value: '2025-06-01' } })

    expect(await screen.findAllByText('From date must be on or before to date.')).not.toHaveLength(0)
    screen.getAllByRole('button', { name: 'Download PDF' }).forEach((b) => expect(b).toBeDisabled())
    screen
      .getAllByRole('button', { name: 'Share via WhatsApp' })
      .forEach((b) => expect(b).toBeDisabled())
    expect(exportReport).not.toHaveBeenCalled()
  })

  it('blocks export for a future end date', async () => {
    renderPanel('CONTRIBUTIONS')
    fireEvent.change(await screen.findByLabelText(/To date/), { target: { value: '2999-01-01' } })
    expect(
      await screen.findAllByText('You cannot generate a report that ends in the future.'),
    ).not.toHaveLength(0)
    screen.getAllByRole('button', { name: 'Download PDF' }).forEach((b) => expect(b).toBeDisabled())
  })

  it('validates the WhatsApp recipient inline and sends the same filter payload', async () => {
    const user = userEvent.setup()
    renderPanel('CONTRIBUTIONS')
    const advancedShare = () =>
      screen
        .getAllByRole('button', { name: 'Share via WhatsApp' })
        .find((b) => b.className.includes('MuiButton-sizeMedium'))!
    await waitFor(() => expect(advancedShare()).toBeEnabled())
    await user.click(advancedShare())

    const dialog = await screen.findByRole('dialog')
    const phone = within(dialog).getByLabelText('Recipient phone number')
    const send = within(dialog).getByRole('button', { name: 'Send' })
    expect(send).toBeDisabled()

    await user.type(phone, '12345')
    expect(within(dialog).getByText('Enter a valid Rwandan mobile number')).toBeInTheDocument()
    expect(send).toBeDisabled()
    expect(shareReportViaWhatsApp).not.toHaveBeenCalled()

    await user.clear(phone)
    await user.type(phone, '0781234567')
    expect(within(dialog).queryByText('Enter a valid Rwandan mobile number')).not.toBeInTheDocument()
    await user.click(send)
    await waitFor(() => expect(shareReportViaWhatsApp).toHaveBeenCalledTimes(1))
    expect(vi.mocked(shareReportViaWhatsApp).mock.calls[0][1]).toMatchObject({
      reportType: 'CONTRIBUTIONS',
      memberUserId: null,
      status: null,
      recipientPhone: '0781234567',
    })
  })
})
