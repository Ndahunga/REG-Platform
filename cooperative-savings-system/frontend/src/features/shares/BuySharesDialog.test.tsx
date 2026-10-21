import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SnackbarProvider } from 'notistack'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { uploadCooperativeFile } from '@/shared/api/files'
import { fetchShareValuation, submitSharePurchase } from '@/shared/api/shares'
import { kigaliDay } from '@/test/schemaHelpers'
import { BuySharesDialog } from './BuySharesDialog'

vi.mock('@/shared/api/files', () => ({ uploadCooperativeFile: vi.fn() }))
vi.mock('@/shared/api/shares', () => ({
  fetchShareValuation: vi.fn(),
  submitSharePurchase: vi.fn(),
}))

function renderDialog() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <SnackbarProvider>
        <BuySharesDialog open cooperativeId="coop-1" onClose={() => {}} />
      </SnackbarProvider>
    </QueryClientProvider>,
  )
}

const submit = () => screen.getByRole('button', { name: /submit/i })
const date = () => screen.getByLabelText(/payment date/i)

async function withProof() {
  const input = document.querySelector('input[type="file"]') as HTMLInputElement
  await userEvent.upload(input, new File(['x'], 'proof.png', { type: 'image/png' }))
  await screen.findByText('proof.png')
}

describe('BuySharesDialog validation', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(fetchShareValuation).mockResolvedValue({
      canPurchase: true,
      currentShareValue: '1000',
      currency: 'RWF',
    } as never)
    vi.mocked(uploadCooperativeFile).mockResolvedValue({
      storageKey: 'k/proof.png',
      originalFilename: 'proof.png',
    } as never)
    vi.mocked(submitSharePurchase).mockResolvedValue({ status: 'PENDING' } as never)
  })

  async function ready() {
    renderDialog()
    await screen.findByLabelText(/payment date/i)
    await withProof()
  }

  it('allows a valid purchase exactly once', async () => {
    const u = userEvent.setup()
    await ready()
    await waitFor(() => expect(submit()).toBeEnabled())
    await u.click(submit())
    await waitFor(() => expect(submitSharePurchase).toHaveBeenCalledTimes(1))
    expect(vi.mocked(submitSharePurchase).mock.calls[0][1]).toMatchObject({
      numberOfShares: 1,
      paymentDate: kigaliDay(0),
      evidenceFileKey: 'k/proof.png',
    })
  })

  it('blocks a future payment date and says why', async () => {
    await ready()
    fireEvent.change(date(), { target: { value: kigaliDay(3) } })
    expect(await screen.findByText('Payment date cannot be in the future.')).toBeInTheDocument()
    expect(submit()).toBeDisabled()
    expect(submitSharePurchase).not.toHaveBeenCalled()
  })

  it('blocks a cleared or impossible date', async () => {
    await ready()
    fireEvent.change(date(), { target: { value: '' } })
    expect(await screen.findByText('Date is required')).toBeInTheDocument()
    expect(submit()).toBeDisabled()
  })

  it('blocks an over-long reference and over-long notes with a visible message', async () => {
    await ready()
    fireEvent.change(screen.getByLabelText(/reference/i), { target: { value: 'r'.repeat(129) } })
    expect(await screen.findByText('Reference must be 128 characters or fewer.')).toBeInTheDocument()
    expect(submit()).toBeDisabled()

    fireEvent.change(screen.getByLabelText(/reference/i), { target: { value: 'ok' } })
    fireEvent.change(screen.getByLabelText(/notes/i), { target: { value: 'n'.repeat(2001) } })
    expect(await screen.findByText('Notes must be 2000 characters or fewer.')).toBeInTheDocument()
    expect(submit()).toBeDisabled()
  })

  it('blocks a share quantity outside 1 to 1000', async () => {
    await ready()
    for (const bad of ['0', '1001', '1.5', '']) {
      fireEvent.change(screen.getByLabelText(/number of shares|shares/i, { selector: 'input' }), {
        target: { value: bad },
      })
      expect(submit()).toBeDisabled()
    }
    expect(submitSharePurchase).not.toHaveBeenCalled()
  })
})
