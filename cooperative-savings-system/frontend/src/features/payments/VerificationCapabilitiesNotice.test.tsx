import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchVerificationCapabilities } from '@/shared/api/paymentVerification'
import type { VerificationCapabilities } from '@/shared/types/paymentVerification'
import { renderWithApp } from '@/test/paymentTestHarness'
import { VerificationCapabilitiesNotice } from './VerificationCapabilitiesNotice'

vi.mock('@/shared/api/paymentVerification', () => ({ fetchVerificationCapabilities: vi.fn() }))

const mock = vi.mocked(fetchVerificationCapabilities)
const officer = { id: 'o', roles: ['ACCOUNTANT'], permissions: ['CONTRIBUTION_WRITE'] }

const capabilities = (extra: Partial<VerificationCapabilities> = {}): VerificationCapabilities => ({
  ocrAvailable: false,
  ocrEngine: null,
  supportedReceiptTypes: ['image/png', 'image/jpeg'],
  pdfSupported: false,
  maxReceiptBytes: 8 * 1024 * 1024,
  ocrTimeoutSeconds: 30,
  strictVerification: true,
  providerCallbackConfigured: false,
  ...extra,
})

describe('VerificationCapabilitiesNotice', { timeout: 20_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('says plainly that receipt reading is not installed, that PDFs are not read and that no provider confirms automatically', async () => {
    mock.mockResolvedValue(capabilities())
    renderWithApp(<VerificationCapabilitiesNotice cooperativeId="c1" />, { user: officer })
    expect(await screen.findByTestId('cap-ocr')).toHaveTextContent(/not installed on this server/)
    expect(screen.getByTestId('cap-pdf')).toHaveTextContent(/PDF receipts are not read/)
    expect(screen.getByTestId('cap-strict')).toHaveTextContent(/needs authoritative confirmation/)
    expect(screen.getByTestId('cap-provider')).toHaveTextContent(/Automatic provider confirmation is not available/)
    expect(mock).toHaveBeenCalledWith('c1')
  })

  it('states the limits when reading is available and reports a relaxed deployment as an explicit choice', async () => {
    mock.mockResolvedValue(capabilities({ ocrAvailable: true, ocrEngine: 'tesseract-cli', strictVerification: false, providerCallbackConfigured: true }))
    renderWithApp(<VerificationCapabilitiesNotice cooperativeId="c1" />, { user: officer })
    expect(await screen.findByTestId('cap-ocr')).toHaveTextContent(/PNG, JPEG images up to 8 MB.*30 seconds.*never proof/)
    expect(screen.getByTestId('cap-strict')).toHaveTextContent(/explicit configuration choice/)
    expect(screen.getByTestId('cap-provider')).toHaveTextContent(/No provider is contacted by this application/)
  })

  it('shows only the reading status in compact form and renders nothing before the answer arrives', async () => {
    mock.mockResolvedValue(capabilities())
    const view = renderWithApp(<VerificationCapabilitiesNotice cooperativeId="c1" compact />, { user: officer })
    expect(await screen.findByTestId('cap-ocr')).toBeInTheDocument()
    expect(screen.queryByTestId('cap-strict')).not.toBeInTheDocument()
    expect(screen.queryByTestId('cap-provider')).not.toBeInTheDocument()
    view.unmount()

    mock.mockReturnValue(new Promise(() => {}))
    renderWithApp(<VerificationCapabilitiesNotice cooperativeId="c1" />, { user: officer })
    expect(screen.queryByTestId('verification-capabilities')).not.toBeInTheDocument()
  })
})
