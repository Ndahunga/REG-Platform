import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SnackbarProvider } from 'notistack'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchContributionPeriod, saveContributionPeriod } from '@/shared/api/contributions'
import { mapContributionPeriodLine } from '@/shared/types/contribution'
import { MonthlyEntryPanel } from './MonthlyEntryPanel'

vi.mock('@/shared/api/contributions', () => ({
  fetchContributionPeriod: vi.fn(),
  saveContributionPeriod: vi.fn(),
}))

// The subscription/offline guard is covered elsewhere; a plain button keeps these tests on validation.
vi.mock('@/shared/components/FinancialActionButton', () => ({
  FinancialActionButton: ({
    children,
    onClick,
    disabled,
  }: {
    children: React.ReactNode
    onClick?: () => void
    disabled?: boolean
  }) => (
    <button type="button" onClick={onClick} disabled={disabled}>
      {children}
    </button>
  ),
}))

/** The exact shape GET /contributions/period returns: ContributionResponse carries memberName only. */
const backendRow = (overrides: Record<string, unknown> = {}) => ({
  memberUserId: '11111111-1111-4111-8111-111111111111',
  memberName: 'Alice Uwase',
  expectedAmount: '5000.0000',
  paidAmount: '0.0000',
  status: 'PENDING',
  ...overrides,
})

function stubMatchMedia(matches: boolean) {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    configurable: true,
    value: (query: string) => ({
      matches,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }),
  })
}

function loadRows(rows: Record<string, unknown>[]) {
  vi.mocked(fetchContributionPeriod).mockResolvedValue({
    year: 2026,
    month: 5,
    lines: rows.map((row) => mapContributionPeriodLine(row as never)),
  })
}

function renderPanel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <SnackbarProvider>
        <MonthlyEntryPanel cooperativeId="coop-1" canWrite />
      </SnackbarProvider>
    </QueryClientProvider>,
  )
}

const bob = backendRow({
  memberUserId: '22222222-2222-4222-8222-222222222222',
  memberName: 'Bob Mugisha',
})

const saveButton = () => screen.getByRole('button', { name: 'Save period' })

describe('MonthlyEntryPanel member names', () => {
  afterEach(() => stubMatchMedia(false))
  beforeEach(() => {
    vi.clearAllMocks()
    stubMatchMedia(false)
  })

  it('desktop grid shows the name when the backend row has only memberName', async () => {
    loadRows([backendRow(), bob])
    renderPanel()
    const names = await screen.findAllByTestId('contribution-member-name')
    expect(names.map((n) => n.textContent)).toEqual(['Alice Uwase', 'Bob Mugisha'])
    expect(screen.getByRole('table')).toHaveTextContent('Alice Uwase')
  })

  it('desktop grid still shows fullName when the row supplies it', async () => {
    loadRows([backendRow({ memberName: undefined, fullName: 'Carine Ingabire', username: 'carine' })])
    renderPanel()
    expect((await screen.findByTestId('contribution-member-name')).textContent).toBe('Carine Ingabire')
    expect(screen.getByText('carine')).toBeInTheDocument()
  })

  it('mobile card shows the name when the backend row has only memberName', async () => {
    stubMatchMedia(true)
    loadRows([backendRow()])
    renderPanel()
    expect((await screen.findByTestId('contribution-member-name')).textContent).toBe('Alice Uwase')
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('never shows the member UUID as the name: falls back to username, then a safe label', async () => {
    loadRows([
      backendRow({ memberName: undefined, username: 'alice_u' }),
      backendRow({ memberUserId: '33333333-3333-4333-8333-333333333333', memberName: undefined }),
    ])
    renderPanel()
    const names = (await screen.findAllByTestId('contribution-member-name')).map((n) => n.textContent)
    expect(names).toEqual(['alice_u', 'Unnamed member'])
    expect(document.body.textContent).not.toMatch(/11111111-1111|33333333-3333/)
  })
})

describe('MonthlyEntryPanel batch validation', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    stubMatchMedia(false)
    vi.mocked(saveContributionPeriod).mockResolvedValue(undefined as never)
  })

  async function ready() {
    loadRows([backendRow(), bob])
    renderPanel()
    await screen.findByText('Alice Uwase')
  }

  const amountOf = (name: string) => screen.getByRole('textbox', { name: `Paid ${name}` })
  const dateOf = (name: string) => screen.getByLabelText(`Payment date ${name}`)
  const referenceOf = (name: string) => screen.getByRole('textbox', { name: `Reference ${name}` })
  const notesOf = (name: string) => screen.getByRole('textbox', { name: `Notes ${name}` })

  async function expectBlocked(user: ReturnType<typeof userEvent.setup>, message: string) {
    await user.click(saveButton())
    expect(await screen.findByText(message)).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(saveContributionPeriod).not.toHaveBeenCalled()
  }

  it.each([
    ['abc', 'Enter a valid amount with at most 4 decimals.'],
    ['1e3', 'Enter a valid amount with at most 4 decimals.'],
    ['12.34567', 'Enter a valid amount with at most 4 decimals.'],
    ['-5', 'Paid amounts must be zero or greater.'],
    ['9999999999999999', 'Amount is too large.'],
  ])('rejects the amount "%s" and makes no API call', async (value, message) => {
    const user = userEvent.setup()
    await ready()
    fireEvent.change(amountOf('Alice Uwase'), { target: { value } })
    await expectBlocked(user, message)
  })

  it('rejects a future payment date', async () => {
    const user = userEvent.setup()
    await ready()
    fireEvent.change(dateOf('Alice Uwase'), { target: { value: '2999-01-01' } })
    await expectBlocked(user, 'Payment date cannot be in the future.')
  })

  it('rejects an over-long reference and over-long notes', async () => {
    const user = userEvent.setup()
    await ready()
    fireEvent.change(referenceOf('Alice Uwase'), { target: { value: 'r'.repeat(129) } })
    await expectBlocked(user, 'Reference must be 128 characters or fewer.')

    fireEvent.change(referenceOf('Alice Uwase'), { target: { value: 'ok' } })
    fireEvent.change(notesOf('Alice Uwase'), { target: { value: 'n'.repeat(2001) } })
    await user.click(saveButton())
    expect(await screen.findByText('Notes must be 2000 characters or fewer.')).toBeInTheDocument()
    expect(saveContributionPeriod).not.toHaveBeenCalled()
  })

  it('does not let one invalid row submit the whole batch, and names the member', async () => {
    const user = userEvent.setup()
    await ready()
    fireEvent.change(amountOf('Alice Uwase'), { target: { value: '5000' } }) // valid row
    fireEvent.change(amountOf('Bob Mugisha'), { target: { value: 'oops' } }) // invalid row

    await user.click(saveButton())
    const summary = await screen.findByTestId('contribution-validation-summary')
    expect(summary).toHaveTextContent('Bob Mugisha')
    expect(saveContributionPeriod).not.toHaveBeenCalled()
    expect(amountOf('Bob Mugisha')).toHaveAttribute('aria-invalid', 'true')
    expect(amountOf('Alice Uwase')).not.toHaveAttribute('aria-invalid', 'true')
  })

  it('moves focus to the first invalid field and keeps what the user typed', async () => {
    const user = userEvent.setup()
    await ready()
    fireEvent.change(amountOf('Alice Uwase'), { target: { value: '5000' } })
    fireEvent.change(amountOf('Bob Mugisha'), { target: { value: 'oops' } })
    await user.click(saveButton())
    await waitFor(() => expect(amountOf('Bob Mugisha')).toHaveFocus())
    expect(amountOf('Alice Uwase')).toHaveValue('5000')
    expect(amountOf('Bob Mugisha')).toHaveValue('oops')
  })

  it('clears a row error as soon as the row is fixed', async () => {
    const user = userEvent.setup()
    await ready()
    fireEvent.change(amountOf('Alice Uwase'), { target: { value: 'bad' } })
    await user.click(saveButton())
    expect(await screen.findByTestId('contribution-validation-summary')).toBeInTheDocument()

    fireEvent.change(amountOf('Alice Uwase'), { target: { value: '2500' } })
    await waitFor(() =>
      expect(screen.queryByTestId('contribution-validation-summary')).not.toBeInTheDocument(),
    )
    expect(amountOf('Alice Uwase')).not.toHaveAttribute('aria-invalid', 'true')
  })

  it('treats a blank amount as zero (nothing paid) rather than an error', async () => {
    const user = userEvent.setup()
    loadRows([backendRow({ paidAmount: '2000.0000' }), bob])
    renderPanel()
    await screen.findByText('Alice Uwase')
    fireEvent.change(amountOf('Alice Uwase'), { target: { value: '' } })
    await user.click(saveButton())
    expect(await screen.findByRole('dialog')).toBeInTheDocument()
    expect(screen.queryByTestId('contribution-validation-summary')).not.toBeInTheDocument()
  })

  it('submits exactly once when every row is valid, with trimmed values', async () => {
    const user = userEvent.setup()
    await ready()
    fireEvent.change(amountOf('Alice Uwase'), { target: { value: ' 5000.50 ' } })
    fireEvent.change(dateOf('Alice Uwase'), { target: { value: '2026-05-02' } })
    fireEvent.change(referenceOf('Alice Uwase'), { target: { value: '  MOMO-1 ' } })

    await user.click(saveButton())
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }))

    await waitFor(() => expect(saveContributionPeriod).toHaveBeenCalledTimes(1))
    const [, , , body] = vi.mocked(saveContributionPeriod).mock.calls[0]
    expect(body.lines).toEqual([
      {
        memberUserId: '11111111-1111-4111-8111-111111111111',
        paidAmount: '5000.50',
        paymentDate: '2026-05-02',
        paymentReference: 'MOMO-1',
        notes: null,
      },
    ])
  })

  it('prevents a duplicate submit while the save is pending', async () => {
    const user = userEvent.setup()
    vi.mocked(saveContributionPeriod).mockReturnValue(new Promise(() => {}))
    await ready()
    fireEvent.change(amountOf('Alice Uwase'), { target: { value: '1000' } })
    await user.click(saveButton())
    const dialog = await screen.findByRole('dialog')
    const confirm = within(dialog).getByRole('button', { name: 'Confirm' })
    await user.click(confirm)
    await waitFor(() => expect(confirm).toBeDisabled())
    fireEvent.click(confirm)
    expect(saveContributionPeriod).toHaveBeenCalledTimes(1)
  })
})

describe('MonthlyEntryPanel saves changed rows only', () => {
  const id = (n: number) => `${n}${n}${n}${n}${n}${n}${n}${n}-0000-4000-8000-00000000000${n}`
  const member = (n: number, name: string, overrides: Record<string, unknown> = {}) =>
    backendRow({
      memberUserId: id(n),
      memberName: name,
      paidAmount: '5000.0000',
      expectedAmount: '5000.0000',
      status: 'PAID',
      paymentDate: null,
      paymentReference: null,
      notes: null,
      reviewStatus: 'APPROVED',
      persisted: true,
      ...overrides,
    })

  const alice = member(1, 'Alice Uwase')
  const bobM = member(2, 'Bob Mugisha')
  const caleb = member(3, 'Caleb Nshuti', { status: 'PENDING', paidAmount: '3000.0000', reviewStatus: 'PENDING' })
  const diana = member(4, 'Diana Uwera', { status: 'PENDING', paidAmount: '0.0000', reviewStatus: null, persisted: false })
  const esther = member(5, 'Esther Mukamana')
  const frank = member(6, 'Frank Habimana', { status: 'WAIVED', paidAmount: '0.0000' })
  const gina = member(7, 'Gina Ingabire', { status: 'CANCELLED', paidAmount: '0.0000' })
  const five = [alice, bobM, caleb, diana, esther]

  const amountOf = (name: string) => screen.getByRole('textbox', { name: `Paid ${name}` })
  const referenceOf = (name: string) => screen.getByRole('textbox', { name: `Reference ${name}` })
  const rowOf = (name: string) =>
    screen.getByText(name).closest('[data-contribution-row]') as HTMLElement
  const sentLines = () => vi.mocked(saveContributionPeriod).mock.calls[0][3].lines

  async function open(rows: Record<string, unknown>[]) {
    loadRows(rows)
    renderPanel()
    await screen.findByText(rows[0].memberName as string)
  }

  async function confirmSave(user: ReturnType<typeof userEvent.setup>) {
    await user.click(saveButton())
    const dialog = await screen.findByRole('dialog')
    return dialog
  }

  beforeEach(() => {
    vi.clearAllMocks()
    stubMatchMedia(false)
    vi.mocked(saveContributionPeriod).mockResolvedValue(undefined as never)
  })

  it('editing one of five rows sends exactly that one line and none of the untouched rows', async () => {
    const user = userEvent.setup()
    await open(five)
    fireEvent.change(amountOf('Diana Uwera'), { target: { value: '2500' } })
    const dialog = await confirmSave(user)
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(saveContributionPeriod).toHaveBeenCalledTimes(1))
    const lines = sentLines()
    expect(lines).toHaveLength(1)
    expect(lines[0]).toMatchObject({ memberUserId: id(4), paidAmount: '2500' })
    expect(lines.map((l) => l.memberUserId)).not.toContain(id(1))
    expect(lines.map((l) => l.memberUserId)).not.toContain(id(2))
    expect(lines.map((l) => l.memberUserId)).not.toContain(id(5))
  })

  it('does not treat null vs empty optional fields, or 5000 vs 5000.0000, as a change', async () => {
    const user = userEvent.setup()
    await open(five)
    // the API returned null for date/reference/notes and "5000.0000"; the inputs show '' and "5000.0000"
    expect(referenceOf('Alice Uwase')).toHaveValue('')
    fireEvent.change(amountOf('Alice Uwase'), { target: { value: '5000' } })
    await user.click(saveButton())
    expect(await screen.findByText('No changes to save')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(saveContributionPeriod).not.toHaveBeenCalled()
  })

  it('does not treat surrounding whitespace as a change, but does send a real change trimmed', async () => {
    const user = userEvent.setup()
    await open([member(1, 'Alice Uwase', { paymentReference: 'MOMO-1' }), bobM])
    fireEvent.change(referenceOf('Alice Uwase'), { target: { value: '  MOMO-1  ' } })
    fireEvent.change(referenceOf('Bob Mugisha'), { target: { value: '   ' } })
    await user.click(saveButton())
    expect(await screen.findByText('No changes to save')).toBeInTheDocument()
    expect(saveContributionPeriod).not.toHaveBeenCalled()

    fireEvent.change(referenceOf('Alice Uwase'), { target: { value: '  MOMO-2  ' } })
    const dialog = await confirmSave(user)
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(saveContributionPeriod).toHaveBeenCalledTimes(1))
    expect(sentLines()).toEqual([expect.objectContaining({ memberUserId: id(1), paymentReference: 'MOMO-2' })])
  })

  it('with no changes it does not call the API or open the confirmation', async () => {
    const user = userEvent.setup()
    await open(five)
    await user.click(saveButton())
    expect(await screen.findByText('No changes to save')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(saveContributionPeriod).not.toHaveBeenCalled()
  })

  it('the confirmation names the number of changed rows, not the number of members', async () => {
    const user = userEvent.setup()
    await open(five)
    fireEvent.change(amountOf('Diana Uwera'), { target: { value: '1000' } })
    let dialog = await confirmSave(user)
    expect(dialog).toHaveTextContent(/Save changes for 1 member for/)
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    fireEvent.change(amountOf('Esther Mukamana'), { target: { value: '4000' } })
    dialog = await confirmSave(user)
    expect(dialog).toHaveTextContent(/Save changes for 2 members for/)
    expect(saveContributionPeriod).not.toHaveBeenCalled()
  })

  it.each([
    ['WAIVED', 'Frank Habimana', 'Waived: this contribution cannot be edited from the monthly grid.'],
    ['CANCELLED', 'Gina Ingabire', 'Cancelled: this contribution cannot be edited from the monthly grid.'],
    ['awaiting review', 'Caleb Nshuti', 'Awaiting Accountant review: approve or reject it in Approvals.'],
  ])('a %s row is read-only with an explanation, and is not removed', async (_label, name, message) => {
    await open([alice, caleb, frank, gina])
    expect(screen.queryByRole('textbox', { name: `Paid ${name}` })).not.toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: `Reference ${name}` })).not.toBeInTheDocument()
    expect(screen.queryByLabelText(`Payment date ${name}`)).not.toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: `Notes ${name}` })).not.toBeInTheDocument()
    expect(rowOf(name)).toHaveAttribute('data-locked', 'true')
    expect(within(rowOf(name)).getByTestId('contribution-row-locked')).toHaveTextContent(message)
    // ordinary rows stay editable
    expect(amountOf('Alice Uwase')).toBeInTheDocument()
    expect(rowOf('Alice Uwase')).toHaveAttribute('data-locked', 'false')
  })

  it('shows the waived and cancelled status chips on locked rows', async () => {
    await open([alice, frank, gina])
    expect(within(rowOf('Frank Habimana')).getByText('Waived', { selector: '.MuiChip-label' })).toBeInTheDocument()
    expect(within(rowOf('Gina Ingabire')).getByText('Cancelled', { selector: '.MuiChip-label' })).toBeInTheDocument()
  })

  it('locked rows do not stop a different valid changed row from being saved, and are never sent', async () => {
    const user = userEvent.setup()
    await open([diana, caleb, frank, gina])
    fireEvent.change(amountOf('Diana Uwera'), { target: { value: '1500' } })
    const dialog = await confirmSave(user)
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(saveContributionPeriod).toHaveBeenCalledTimes(1))
    expect(sentLines().map((l) => l.memberUserId)).toEqual([id(4)])
  })

  it('an invalid changed row still blocks the submission and names the member', async () => {
    const user = userEvent.setup()
    await open(five)
    fireEvent.change(amountOf('Diana Uwera'), { target: { value: 'abc' } })
    await user.click(saveButton())
    expect(await screen.findByTestId('contribution-validation-summary')).toHaveTextContent('Diana Uwera')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(saveContributionPeriod).not.toHaveBeenCalled()
  })

  it('an invalid untouched or locked row does not block saving another changed row', async () => {
    const user = userEvent.setup()
    const tooLong = 'r'.repeat(129)
    await open([
      diana,
      member(2, 'Bob Mugisha', { notes: 'n'.repeat(2001) }), // untouched editable row with bad stored data
      member(3, 'Caleb Nshuti', { status: 'PENDING', reviewStatus: 'PENDING', paymentReference: tooLong }), // locked
    ])
    fireEvent.change(amountOf('Diana Uwera'), { target: { value: '1000' } })
    const dialog = await confirmSave(user)
    expect(screen.queryByTestId('contribution-validation-summary')).not.toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(saveContributionPeriod).toHaveBeenCalledTimes(1))
    expect(sentLines().map((l) => l.memberUserId)).toEqual([id(4)])
  })

  it('after a successful save the saved row is no longer changed', async () => {
    const user = userEvent.setup()
    await open([diana, bobM])
    fireEvent.change(amountOf('Diana Uwera'), { target: { value: '2500' } })
    const dialog = await confirmSave(user)
    // the refetch after saving returns what the server now holds
    loadRows([member(4, 'Diana Uwera', { status: 'PARTIALLY_PAID', paidAmount: '2500.0000', reviewStatus: null, persisted: true }), bobM])
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(saveContributionPeriod).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    await user.click(saveButton())
    expect(await screen.findByText('No changes to save')).toBeInTheDocument()
    expect(saveContributionPeriod).toHaveBeenCalledTimes(1)
    // the grid now shows the server's saved value
    expect(amountOf('Diana Uwera')).toHaveValue('2500.0000')
  })

  it('a saved row is clean even if the refetch returns the very same rows', async () => {
    const user = userEvent.setup()
    await open([diana, bobM])
    fireEvent.change(amountOf('Diana Uwera'), { target: { value: '2500' } })
    const dialog = await confirmSave(user)
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(saveContributionPeriod).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    await user.click(saveButton())
    expect(await screen.findByText('No changes to save')).toBeInTheDocument()
    expect(saveContributionPeriod).toHaveBeenCalledTimes(1)
  })
})
