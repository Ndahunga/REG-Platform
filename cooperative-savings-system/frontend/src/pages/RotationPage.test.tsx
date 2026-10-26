import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ThemeProvider } from '@mui/material'
import { configureStore } from '@reduxjs/toolkit'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SnackbarProvider } from 'notistack'
import { Provider } from 'react-redux'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import authReducer from '@/app/store/authSlice'
import uiReducer from '@/app/store/uiSlice'
import { fetchCooperative } from '@/shared/api/cooperatives'
import { fetchMembers } from '@/shared/api/members'
import {
  activateRotationCycle,
  cancelRotationCycle,
  changeRotationRecipient,
  createRotationConfiguration,
  createRotationCycle,
  fetchRotationConfigurations,
  fetchRotationCycles,
  fetchRotationRecipientChanges,
} from '@/shared/api/rotation'
import { ROLE_MEMBER, ROLE_PRESIDENT } from '@/shared/types/auth'
import type { RotationCycle } from '@/shared/types/rotation'
import { lightTheme } from '@/theme/theme'
import { RotationPage } from './RotationPage'

vi.mock('@/shared/api/cooperatives', () => ({ fetchCooperative: vi.fn() }))
vi.mock('@/shared/api/members', () => ({ fetchMembers: vi.fn() }))
vi.mock('@/shared/api/rotation', () => ({
  fetchRotationConfigurations: vi.fn(),
  createRotationConfiguration: vi.fn(),
  fetchRotationCycles: vi.fn(),
  createRotationCycle: vi.fn(),
  activateRotationCycle: vi.fn(),
  cancelRotationCycle: vi.fn(),
  changeRotationRecipient: vi.fn(),
  fetchRotationRecipientChanges: vi.fn(),
  createShareCycle: vi.fn(),
  importRotation: vi.fn(),
  reassignRotationTurn: vi.fn(),
  fetchTurnEligibility: vi.fn().mockResolvedValue([]),
  fetchSharePool: vi.fn(),
  requestTurnPayout: vi.fn(),
}))

const cooperativeMock = vi.mocked(fetchCooperative)
const membersMock = vi.mocked(fetchMembers)
const configsMock = vi.mocked(fetchRotationConfigurations)
const createConfigMock = vi.mocked(createRotationConfiguration)
const cyclesMock = vi.mocked(fetchRotationCycles)
const createCycleMock = vi.mocked(createRotationCycle)
const activateMock = vi.mocked(activateRotationCycle)
const cancelMock = vi.mocked(cancelRotationCycle)
const changeMock = vi.mocked(changeRotationRecipient)
const historyMock = vi.mocked(fetchRotationRecipientChanges)

const baseScheme = {
  id: 'c1',
  name: 'Scheme',
  currency: 'RWF',
  status: 'ACTIVE' as const,
  financialYearStartMonth: 1,
  monthlyContributionAmount: '1000',
  contributionDueDay: 1,
}

const member = (userId: string, firstName: string, status = 'ACTIVE') => ({
  userId,
  firstName,
  lastName: 'Test',
  username: firstName.toLowerCase(),
  email: `${firstName}@test.local`,
  membershipStatus: status as 'ACTIVE',
  accountStatus: 'ACTIVE' as const,
  roleInCooperative: 'MEMBER',
})

function cycleOf(status: RotationCycle['status'], firstStarted = false): RotationCycle {
  return {
    id: 'cy1',
    cycleNumber: 1,
    startYear: 2030,
    startMonth: 5,
    periodCount: 2,
    status,
    periods: [
      { id: 'p1', cycleId: 'cy1', sequenceNumber: 1, year: 2030, month: 5, recipientUserId: 'u1', recipientName: 'Ange Test', status: 'SCHEDULED', started: firstStarted },
      { id: 'p2', cycleId: 'cy1', sequenceNumber: 2, year: 2030, month: 6, recipientUserId: 'u2', recipientName: 'Beata Test', status: 'SCHEDULED', started: false },
    ],
  }
}

function renderPage(options: { roles: string[]; permissions: string[]; cooperativeId?: string | null }) {
  const store = configureStore({
    reducer: { auth: authReducer, ui: uiReducer },
    preloadedState: {
      auth: {
        user: {
          id: 'me',
          username: 'me',
          email: 'me@test.local',
          firstName: 'Me',
          lastName: 'User',
          fullName: 'Me User',
          roles: options.roles,
          permissions: options.permissions,
          cooperativeIds: ['c1'],
        },
        accessToken: 'token',
        selectedCooperativeId: options.cooperativeId === undefined ? 'c1' : options.cooperativeId,
        status: 'authenticated' as const,
      },
      ui: { sidebarOpen: false, themePreference: 'light' as const },
    },
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <Provider store={store}>
      <QueryClientProvider client={client}>
        <ThemeProvider theme={lightTheme}>
          <SnackbarProvider>
            <MemoryRouter>
              <RotationPage />
            </MemoryRouter>
          </SnackbarProvider>
        </ThemeProvider>
      </QueryClientProvider>
    </Provider>,
  )
}

const MEMBER_USER = { roles: [ROLE_MEMBER], permissions: [] as string[] }
const PRESIDENT_USER = { roles: [ROLE_MEMBER, ROLE_PRESIDENT], permissions: ['COOPERATIVE_WRITE'] }

describe('RotationPage', { timeout: 20_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    cooperativeMock.mockResolvedValue({ ...baseScheme, operatingModel: 'HYBRID' })
    cyclesMock.mockResolvedValue([])
    configsMock.mockResolvedValue([])
    membersMock.mockResolvedValue({
      content: [member('u1', 'Ange'), member('u2', 'Beata'), member('u3', 'Chantal'), member('u4', 'Dieudonne', 'INACTIVE')],
      page: 0,
      size: 500,
      totalElements: 4,
      totalPages: 1,
    } as never)
    historyMock.mockResolvedValue([])
  })

  // ---------------------------------------------------------------- model states

  it('asks for a Saving Scheme when none is selected', async () => {
    renderPage({ ...MEMBER_USER, cooperativeId: null })
    expect(await screen.findByText('Select a Saving Scheme')).toBeInTheDocument()
    expect(cooperativeMock).not.toHaveBeenCalled()
  })

  it('shows the not-configured state for a legacy scheme and nothing to manage', async () => {
    cooperativeMock.mockResolvedValue({ ...baseScheme, operatingModel: null })
    renderPage(PRESIDENT_USER)
    expect(await screen.findByTestId('rotation-model-missing')).toHaveTextContent('Operating model not configured')
    expect(screen.queryByRole('button', { name: 'New cycle' })).not.toBeInTheDocument()
  })

  it('explains that rotation does not apply to an Accumulating scheme', async () => {
    cooperativeMock.mockResolvedValue({ ...baseScheme, operatingModel: 'ACCUMULATING' })
    renderPage(PRESIDENT_USER)
    expect(await screen.findByTestId('rotation-not-applicable')).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Schedule' })).not.toBeInTheDocument()
  })

  // ---------------------------------------------------------------- members view

  it('lets a member read the schedule but offers no management actions', async () => {
    cyclesMock.mockResolvedValue([cycleOf('ACTIVE')])
    configsMock.mockResolvedValue([
      { id: 'k1', effectiveYear: 2030, effectiveMonth: 5, rotatingBps: 7000, retainedBps: 3000 },
    ])
    const user = userEvent.setup()
    renderPage(MEMBER_USER)

    expect(await screen.findByText('Ange Test')).toBeInTheDocument()
    expect(screen.getByText('Beata Test')).toBeInTheDocument()
    expect(screen.getAllByTestId('rotation-period')).toHaveLength(2)
    expect(screen.queryByRole('button', { name: 'New cycle' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Change recipient' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancel cycle' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Configuration' }))
    expect(await screen.findByText('Configuration history')).toBeInTheDocument()
    expect(screen.getByTestId('rotation-config-row')).toHaveTextContent('2030-05')
    expect(screen.getByTestId('rotation-config-row')).toHaveTextContent('70%')
    expect(screen.queryByText('New configuration')).not.toBeInTheDocument()
  })

  // ---------------------------------------------------------------- administrator actions

  it('offers actions to an administrator, but not for a period that has begun or a cycle that started', async () => {
    cyclesMock.mockResolvedValue([cycleOf('DRAFT', true)])
    renderPage(PRESIDENT_USER)
    await screen.findByText('Ange Test')

    expect(screen.getByRole('button', { name: 'New cycle' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Activate' })).toBeInTheDocument()
    // only the period that has not begun can be reassigned, and a started cycle cannot be cancelled here
    expect(screen.getAllByRole('button', { name: 'Change recipient' })).toHaveLength(1)
    expect(screen.queryByRole('button', { name: 'Cancel cycle' })).not.toBeInTheDocument()
    expect(screen.getByText('Started')).toBeInTheDocument()
  })

  it('activates a draft cycle', async () => {
    cyclesMock.mockResolvedValue([cycleOf('DRAFT')])
    activateMock.mockResolvedValue(cycleOf('ACTIVE'))
    const user = userEvent.setup()
    renderPage(PRESIDENT_USER)
    await user.click(await screen.findByRole('button', { name: 'Activate' }))
    await waitFor(() => expect(activateMock).toHaveBeenCalledWith('c1', 'cy1'))
  })

  it('needs a reason to cancel a cycle', async () => {
    cyclesMock.mockResolvedValue([cycleOf('ACTIVE')])
    cancelMock.mockResolvedValue(cycleOf('CANCELLED'))
    const user = userEvent.setup()
    renderPage(PRESIDENT_USER)
    await user.click(await screen.findByRole('button', { name: 'Cancel cycle' }))

    const dialog = within(await screen.findByRole('dialog'))
    await user.click(dialog.getByRole('button', { name: 'Cancel cycle' }))
    expect(await dialog.findByText('A reason is required')).toBeInTheDocument()
    expect(cancelMock).not.toHaveBeenCalled()

    fireEvent.change(dialog.getByLabelText(/^Reason/), { target: { value: 'Wrong order' } })
    await user.click(dialog.getByRole('button', { name: 'Cancel cycle' }))
    await waitFor(() => expect(cancelMock).toHaveBeenCalledWith('c1', 'cy1', 'Wrong order'))
  })

  it('reassigns a recipient only with a new member and a reason, offering only members without a turn', async () => {
    cyclesMock.mockResolvedValue([cycleOf('ACTIVE')])
    changeMock.mockResolvedValue(cycleOf('ACTIVE').periods[1])
    const user = userEvent.setup()
    renderPage(PRESIDENT_USER)
    const buttons = await screen.findAllByRole('button', { name: 'Change recipient' })
    await user.click(buttons[1])

    const dialog = within(await screen.findByRole('dialog'))
    await user.click(dialog.getByRole('button', { name: 'Change recipient' }))
    expect(await dialog.findByText('Choose a member')).toBeInTheDocument()
    expect(dialog.getByText('A reason is required')).toBeInTheDocument()
    expect(changeMock).not.toHaveBeenCalled()

    await user.click(dialog.getByLabelText(/New recipient/))
    // Ange and Beata already have turns and Dieudonne is inactive: only Chantal can be chosen
    const options = await screen.findAllByRole('option')
    expect(options.map((o) => o.textContent)).toEqual(['Chantal Test'])
    await user.click(options[0])
    fireEvent.change(dialog.getByLabelText(/^Reason/), { target: { value: 'Travelling' } })
    await user.click(dialog.getByRole('button', { name: 'Change recipient' }))
    await waitFor(() => expect(changeMock).toHaveBeenCalledWith('c1', 'p2', 'u3', 'Travelling'))
  })

  it('shows the recorded history of a turn', async () => {
    cyclesMock.mockResolvedValue([cycleOf('ACTIVE')])
    historyMock.mockResolvedValue([
      {
        id: 'h1',
        rotationPeriodId: 'p2',
        previousRecipientUserId: 'u2',
        previousRecipientName: 'Beata Test',
        newRecipientUserId: 'u3',
        newRecipientName: 'Chantal Test',
        reason: 'Travelling',
        changedAt: '2030-01-02T10:00:00Z',
      },
    ])
    const user = userEvent.setup()
    renderPage(MEMBER_USER)
    const buttons = await screen.findAllByRole('button', { name: 'History' })
    await user.click(buttons[1])
    expect(await screen.findByTestId('rotation-change')).toHaveTextContent('Beata Test → Chantal Test')
    expect(screen.getByTestId('rotation-change')).toHaveTextContent('Travelling')
  })

  // ---------------------------------------------------------------- creating a cycle

  it('creates a cycle from a manually ordered list of active members, with one idempotency key', async () => {
    createCycleMock.mockResolvedValue(cycleOf('DRAFT'))
    const user = userEvent.setup()
    renderPage(PRESIDENT_USER)
    await user.click(await screen.findByRole('button', { name: 'New cycle' }))
    const dialog = within(await screen.findByRole('dialog'))

    // nothing chosen yet: validation, no request
    await user.click(dialog.getByRole('button', { name: 'Create draft cycle' }))
    expect(await dialog.findByText('Add at least one member')).toBeInTheDocument()
    expect(createCycleMock).not.toHaveBeenCalled()

    for (const name of ['Chantal Test', 'Ange Test']) {
      await user.click(dialog.getByLabelText(/Add a member to the order/))
      const options = await screen.findAllByRole('option')
      // the inactive member is never offered
      expect(options.map((o) => o.textContent)).not.toContain('Dieudonne Test')
      await user.click(options.find((o) => o.textContent === name)!)
    }
    const order = within(dialog.getByTestId('rotation-order'))
    expect(order.getAllByRole('listitem').map((li) => li.textContent)).toEqual([
      expect.stringContaining('1. Chantal Test'),
      expect.stringContaining('2. Ange Test'),
    ])
    // reorder: Ange first
    await user.click(order.getByRole('button', { name: 'Move Ange Test earlier' }))
    expect(order.getAllByRole('listitem')[0]).toHaveTextContent('1. Ange Test')

    await user.click(dialog.getByRole('button', { name: 'Create draft cycle' }))
    await waitFor(() => expect(createCycleMock).toHaveBeenCalledTimes(1))
    const [coop, payload, key] = createCycleMock.mock.calls[0]
    expect(coop).toBe('c1')
    expect(payload.recipientUserIds).toEqual(['u1', 'u3'])
    expect(payload.startMonth).toBeGreaterThanOrEqual(1)
    expect(typeof key).toBe('string')
    expect((key as string).length).toBeGreaterThan(8)
  })

  // ---------------------------------------------------------------- configuration

  it('saves a Hybrid configuration as a rotated amount per share and shows what is retained', async () => {
    cooperativeMock.mockResolvedValue({ ...baseScheme, operatingModel: 'HYBRID', monthlyContributionAmount: 70000 })
    createConfigMock.mockResolvedValue({
      id: 'k',
      effectiveYear: 2030,
      effectiveMonth: 1,
      rotatingBps: 7143,
      retainedBps: 2857,
      rotatingUnitAmount: 50000,
    })
    const user = userEvent.setup()
    renderPage(PRESIDENT_USER)
    await user.click(await screen.findByRole('tab', { name: 'Configuration' }))
    expect(await screen.findByText('New configuration')).toBeInTheDocument()

    // invalid amounts are refused client-side
    for (const bad of ['', '0', '70000', 'abc']) {
      fireEvent.change(screen.getByLabelText(/^Rotated per share/), { target: { value: bad } })
      await user.click(screen.getByRole('button', { name: 'Save configuration' }))
    }
    expect(createConfigMock).not.toHaveBeenCalled()

    fireEvent.change(screen.getByLabelText(/^Rotated per share/), { target: { value: '50000' } })
    expect(screen.getByTestId('retained-preview')).toHaveTextContent('20,000')
    await user.click(screen.getByRole('button', { name: 'Save configuration' }))
    await waitFor(() => expect(createConfigMock).toHaveBeenCalledTimes(1))
    const [, payload] = createConfigMock.mock.calls[0]
    expect(payload.rotatingUnitAmount).toBe(50000)
  })

  it('stores 100% rotating for a Rotating scheme without asking for a percentage', async () => {
    cooperativeMock.mockResolvedValue({ ...baseScheme, operatingModel: 'ROTATING' })
    createConfigMock.mockResolvedValue({ id: 'k', effectiveYear: 2030, effectiveMonth: 1, rotatingBps: 10000, retainedBps: 0 })
    const user = userEvent.setup()
    renderPage(PRESIDENT_USER)
    await user.click(await screen.findByRole('tab', { name: 'Configuration' }))
    expect(await screen.findByText(/distributes 100% of contributions/)).toBeInTheDocument()
    expect(screen.queryByLabelText(/^Rotated per share/)).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Save configuration' }))
    await waitFor(() => expect(createConfigMock).toHaveBeenCalledTimes(1))
    expect(createConfigMock.mock.calls[0][1]).toMatchObject({ rotatingBps: 10000, retainedBps: 0 })
  })
})
