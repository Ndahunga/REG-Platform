import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from './client'
import { exportLoanSchedule, shareLoanScheduleViaWhatsApp } from './loans'

vi.mock('./client', () => ({
  apiClient: {
    get: vi.fn(),
    post: vi.fn(),
  },
  getErrorMessage: vi.fn(),
}))

vi.mock('./auth', () => ({
  unwrapApiData: (body: { data: unknown }) => body.data,
}))

vi.mock('@/shared/utils/download', () => ({
  throwIfBlobError: vi.fn(async () => undefined),
  parseContentDispositionFilename: vi.fn(
    (_header: string | undefined, fallback: string) => fallback,
  ),
  triggerBlobDownload: vi.fn(),
}))

const getMock = vi.mocked(apiClient.get)
const postMock = vi.mocked(apiClient.post)

describe('exportLoanSchedule', () => {
  beforeEach(() => {
    getMock.mockReset()
    getMock.mockResolvedValue({
      data: new Blob(['%PDF-1.4'], { type: 'application/pdf' }),
      headers: { 'content-disposition': 'attachment; filename="repayment-schedule-jane-doe.pdf"' },
    })
  })

  it('requests the PDF as a blob with the reports Accept header', async () => {
    await exportLoanSchedule('coop-1', 'loan-1', 'pdf')
    expect(getMock).toHaveBeenCalledWith(
      '/cooperatives/coop-1/loans/loan-1/schedule/export',
      expect.objectContaining({
        params: { format: 'pdf' },
        responseType: 'blob',
        headers: {
          Accept: 'application/pdf, application/json',
        },
      }),
    )
  })

  it('does not put the loan id in the fallback filename', async () => {
    const { filename } = await exportLoanSchedule('coop-1', 'loan-1', 'pdf')
    expect(filename).not.toContain('loan-1')
    expect(filename.toLowerCase()).not.toContain('wa.me')
  })
})

describe('shareLoanScheduleViaWhatsApp', () => {
  beforeEach(() => {
    postMock.mockReset()
    postMock.mockResolvedValue({
      data: {
        success: true,
        data: {
          sent: true,
          recipient: '250788123456',
          filename: 'repayment-schedule-jane-doe.pdf',
        },
      },
    })
  })

  it('posts the recipient phone to the loan schedule share endpoint', async () => {
    const result = await shareLoanScheduleViaWhatsApp('coop-1', 'loan-1', '0788123456')
    expect(postMock).toHaveBeenCalledWith(
      '/cooperatives/coop-1/loans/loan-1/schedule/share-whatsapp',
      { recipientPhone: '0788123456' },
      { timeout: 120000 },
    )
    expect(result.filename).toBe('repayment-schedule-jane-doe.pdf')
    expect(result.filename).not.toContain('loan-1')
    expect(JSON.stringify(postMock.mock.calls[0])).not.toContain('wa.me')
    expect(JSON.stringify(postMock.mock.calls[0])).not.toContain('navigator.share')
  })
})
