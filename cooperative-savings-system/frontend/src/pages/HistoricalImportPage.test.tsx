import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ThemeProvider } from '@mui/material'
import { configureStore } from '@reduxjs/toolkit'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { SnackbarProvider } from 'notistack'
import { Provider } from 'react-redux'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import authReducer from '@/app/store/authSlice'
import uiReducer from '@/app/store/uiSlice'
import i18n from '@/i18n'
import en from '@/i18n/locales/en.json'
import rw from '@/i18n/locales/rw.json'
import {
  confirmHistoricalImport,
  downloadHistoricalImportTemplate,
  fetchHistoricalImportHistory,
  previewHistoricalImport,
} from '@/shared/api/historicalImport'
import type {
  HistoricalImportConfirm,
  HistoricalImportPreview,
  HistoricalImportSummary,
} from '@/shared/types/historicalImport'
import { backendError } from '@/test/paymentTestHarness'
import { lightTheme } from '@/theme/theme'
import { HistoricalImportPage } from './HistoricalImportPage'

vi.mock('@/shared/api/historicalImport', () => ({
  cancelHistoricalImport: vi.fn(),
  confirmHistoricalImport: vi.fn(),
  downloadHistoricalImportTemplate: vi.fn(),
  downloadHistoricalImportLoanTemplate: vi.fn(),
  fetchHistoricalImport: vi.fn(),
  fetchHistoricalImportHistory: vi.fn(),
  previewHistoricalImport: vi.fn(),
}))

const previewMock = vi.mocked(previewHistoricalImport)
const confirmMock = vi.mocked(confirmHistoricalImport)
const historyMock = vi.mocked(fetchHistoricalImportHistory)
const templateMock = vi.mocked(downloadHistoricalImportTemplate)

function migrationPreview(overrides: Partial<HistoricalImportPreview> = {}): HistoricalImportPreview {
  return {
    importId: 'imp-1',
    status: 'READY',
    format: 'OPENING_BALANCE',
    cutoverDate: '2026-09-30',
    totalRows: 5,
    validRows: 5,
    invalidRows: 0,
    confirmAllowed: true,
    reportReady: true,
    migration: {
      cutoverDate: '2026-09-30',
      openingAvailableFunds: 800000,
      members: { totalRows: 2, validRows: 2, invalidRows: 0, totalOpeningContributions: 850000 },
      shareCapital: {
        totalRows: 1,
        validRows: 1,
        invalidRows: 0,
        totalOpeningShares: 8,
        totalOpeningShareCapital: 240000,
      },
      loans: {
        totalRows: 2,
        validRows: 2,
        invalidRows: 0,
        endOfTermCount: 1,
        monthlyCount: 1,
        totalOriginalPrincipal: 1300000,
        totalOutstandingPrincipal: 750000,
        totalAmountPaid: 600000,
      },
    },
    sheets: [],
    errors: [],
    ...overrides,
  }
}

const confirmResult: HistoricalImportConfirm = {
  importId: 'imp-1',
  status: 'CONFIRMED',
  cutoverDate: '2026-09-30',
  membersImported: 2,
  memberOpeningBalancesImported: 2,
  shareOpeningBalancesImported: 1,
  loansImported: 2,
  loanInstallmentsCreated: 11,
  totalHistoricalContributions: 850000,
  totalHistoricalShares: 8,
  totalHistoricalShareCapital: 240000,
  totalOriginalLoanAmount: 1300000,
  totalOutstandingLoanPrincipal: 750000,
  totalHistoricalLoanPayments: 600000,
}

function renderPage() {
  const store = configureStore({
    reducer: { auth: authReducer, ui: uiReducer },
    preloadedState: {
      auth: { user: null, accessToken: null, selectedCooperativeId: 'coop-1', status: 'anonymous' as const },
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
              <HistoricalImportPage />
            </MemoryRouter>
          </SnackbarProvider>
        </ThemeProvider>
      </QueryClientProvider>
    </Provider>,
  )
}

function chooseFile(name = 'opening.xlsx') {
  const input = document.querySelector('input[type="file"]') as HTMLInputElement
  const file = new File(['x'], name, {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  })
  fireEvent.change(input, { target: { files: [file] } })
  return file
}

function setFunds(value: string) {
  fireEvent.change(screen.getByLabelText(/Available Group Funds at Cutover/i), { target: { value } })
}

function setCutover(value: string) {
  fireEvent.change(screen.getByLabelText(/Historical data through/i), { target: { value } })
}

beforeEach(() => {
  vi.clearAllMocks()
  historyMock.mockResolvedValue([])
  templateMock.mockResolvedValue({ filename: 'ikimina-migration-template.xlsx' })
})

describe('HistoricalImportPage (opening-balance migration)', { timeout: 15_000 }, () => {
  it('shows a short setup description instead of a large instruction panel', async () => {
    renderPage()
    expect(await screen.findByText('Historical migration setup')).toBeInTheDocument()
    expect(screen.getByText(/opening position as of the date it started using OuWealth/i)).toBeInTheDocument()
    expect(screen.getByText(/Last day covered by historical records/i)).toBeInTheDocument()
    expect(screen.getByText(/Actual funds available at the cutover date/i)).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByText(/The Username connects the sheets/i)).not.toBeInTheDocument()
  })

  it('requires the cutover date before a file can be previewed', async () => {
    previewMock.mockResolvedValue(migrationPreview())
    renderPage()
    const previewButton = await screen.findByRole('button', { name: 'Preview Import' })
    const file = chooseFile()
    expect(previewButton).toBeDisabled()

    setCutover('2026-09-30')
    expect(previewButton).toBeDisabled() // cutover cash is also required
    setFunds('800.123')
    expect(previewButton).toBeDisabled()
    expect(screen.getByText(/at most two decimals/i)).toBeInTheDocument()
    setFunds('800000')
    expect(screen.getByLabelText(/Available Group Funds at Cutover/i)).toHaveValue('800,000')
    expect(previewButton).toBeEnabled()
    await act(async () => {
      fireEvent.click(previewButton)
    })
    await waitFor(() => expect(previewMock).toHaveBeenCalledWith('coop-1', file, '2026-09-30', '800000'))
  })

  it('previews the separate loans workbook without a cash position', async () => {
    previewMock.mockResolvedValue(migrationPreview({ kind: 'OPENING_LOANS' }))
    renderPage()
    const previewButton = await screen.findByRole('button', { name: 'Preview Import' })
    const file = chooseFile('loans.xlsx')
    setCutover('2026-09-30')
    expect(previewButton).toBeDisabled() // opening balances still need the cash position

    fireEvent.click(screen.getByLabelText('This file is the loans workbook'))
    expect(screen.queryByLabelText(/Available Group Funds at Cutover/i)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Download Loans Template' })).toBeInTheDocument()
    expect(previewButton).toBeEnabled()
    await act(async () => {
      fireEvent.click(previewButton)
    })
    await waitFor(() => expect(previewMock).toHaveBeenCalledWith('coop-1', file, '2026-09-30', ''))
  })

  it('shows the cutover date and the three migration sections instead of the old sheet grid', async () => {
    previewMock.mockResolvedValue(migrationPreview())
    renderPage()
    chooseFile()
    setCutover('2026-09-30')
    setFunds('800000')
    await act(async () => {
      fireEvent.click(await screen.findByRole('button', { name: 'Preview Import' }))
    })

    expect(await screen.findByText(/Import preview/i)).toBeInTheDocument()
    expect(screen.getByText(/Historical data through: 30 Sep 2026/i)).toBeInTheDocument()
    expect(screen.getByText(/Available Group Funds at Cutover:/i)).toBeInTheDocument()
    const members = screen.getByText('Members').closest('.MuiCard-root') as HTMLElement
    expect(within(members).getByText('Opening contributions')).toBeInTheDocument()
    const shares = screen.getByText('Share capital').closest('.MuiCard-root') as HTMLElement
    expect(within(shares).getByText('Opening shares')).toBeInTheDocument()
    expect(within(shares).getByText('8')).toBeInTheDocument()
    const loans = screen.getByText('Loans').closest('.MuiCard-root') as HTMLElement
    expect(within(loans).getByText('END_OF_TERM loans')).toBeInTheDocument()
    expect(within(loans).getByText('MONTHLY loans')).toBeInTheDocument()
    expect(within(loans).getByText('Outstanding principal')).toBeInTheDocument()
    // the retired sections are gone
    expect(screen.queryByText(/Fund reconciliation/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/Historical years in this workbook/i)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Validate' })).toBeEnabled()
  })

  it('lists row errors with sheet, row, column and message and keeps confirm disabled', async () => {
    previewMock.mockResolvedValue(
      migrationPreview({
        status: 'INVALID',
        confirmAllowed: false,
        validRows: 3,
        invalidRows: 2,
        errors: [
          {
            sheet: 'Member Registration',
            rowNumber: 5,
            field: 'Username',
            code: 'DUPLICATE_USERNAME',
            message: 'Username is duplicated in this workbook.',
          },
          {
            sheet: 'Loan Registration',
            rowNumber: 8,
            field: 'Last Payment Date',
            code: 'REQUIRED',
            message: 'Last Payment Date is required when Amount Paid to Date is greater than zero.',
          },
        ],
      }),
    )
    renderPage()
    chooseFile()
    setCutover('2026-09-30')
    setFunds('800000')
    await act(async () => {
      fireEvent.click(await screen.findByRole('button', { name: 'Preview Import' }))
    })

    expect(await screen.findByText('This username appears more than once, or belongs to another person.')).toBeInTheDocument()
    expect(screen.getByText('This value is required.')).toBeInTheDocument()
    expect(screen.queryByText('Username is duplicated in this workbook.')).not.toBeInTheDocument()
    expect(screen.getAllByText('Member Registration').length).toBeGreaterThan(0)
    expect(screen.getByText('Loan Registration')).toBeInTheDocument()
    expect(screen.getByText('Last Payment Date')).toBeInTheDocument()
    expect(screen.getByText('5')).toBeInTheDocument()
    expect(screen.getByText(/Fix the errors in the workbook/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Validate' })).toBeDisabled()
  })

  it('shows a Kinyarwanda user the translated reason for a refused import, never the backend English', async () => {
    previewMock.mockRejectedValue(
      backendError(409, 'MIGRATION_STRATEGY_CONFLICT', 'This scheme migrates by detailed transaction history. (English diagnostic)'),
    )
    await i18n.changeLanguage('rw')
    try {
      renderPage()
      chooseFile()
      fireEvent.change(document.querySelector('input[type="date"]') as HTMLInputElement, { target: { value: '2026-09-30' } })
      const funds = document.querySelectorAll('input[inputmode="decimal"]')[0] as HTMLInputElement
      fireEvent.change(funds, { target: { value: '800000' } })
      const button = await screen.findByRole('button', { name: rw.historicalImport.preview })
      await act(async () => {
        fireEvent.click(button)
      })
      await waitFor(() => expect(previewMock).toHaveBeenCalled())
      expect((await screen.findAllByText(/Iki kimina cyinjiza amateka muundi buryo/)).length).toBeGreaterThan(0)
      expect(screen.queryByText(/English diagnostic/)).not.toBeInTheDocument()
    } finally {
      await i18n.changeLanguage('en')
    }
  })

  it('shows a stable-code fallback, not the backend text, for a code it has no translation for', async () => {
    previewMock.mockRejectedValue(backendError(422, 'SOMETHING_NEW', 'Raw backend sentence'))
    renderPage()
    chooseFile()
    setCutover('2026-09-30')
    setFunds('800000')
    await act(async () => {
      fireEvent.click(await screen.findByRole('button', { name: 'Preview Import' }))
    })
    expect((await screen.findAllByText(/Something went wrong\. Please try again\. \(SOMETHING_NEW\)/)).length).toBeGreaterThan(0)
    expect(screen.queryByText(/Raw backend sentence/)).not.toBeInTheDocument()
  })

  it('confirms and reports the migration result', async () => {
    previewMock.mockResolvedValue(migrationPreview())
    confirmMock.mockResolvedValue(confirmResult)
    renderPage()
    chooseFile()
    setCutover('2026-09-30')
    setFunds('800000')
    await act(async () => {
      fireEvent.click(await screen.findByRole('button', { name: 'Preview Import' }))
    })
    await act(async () => {
      fireEvent.click(await screen.findByRole('button', { name: 'Validate' }))
    })
    expect(await screen.findByText(/does not create contribution, share purchase or loan payment/i)).toBeInTheDocument()
    await act(async () => {
      const buttons = screen.getAllByRole('button', { name: 'Validate' })
      fireEvent.click(buttons[buttons.length - 1])
    })

    await waitFor(() => expect(confirmMock).toHaveBeenCalledWith('coop-1', 'imp-1', undefined))
    expect(await screen.findByText('Migration completed')).toBeInTheDocument()
    expect(screen.getByText(/11 remaining instalments/i)).toBeInTheDocument()
  })

  it('keeps earlier (legacy) confirmed imports visible in history', async () => {
    const legacy: HistoricalImportSummary = {
      id: 'old-1',
      cooperativeId: 'coop-1',
      originalFilename: 'history-2022.xlsx',
      status: 'CONFIRMED',
      format: 'LEGACY',
      totalRows: 100,
      validRows: 100,
      invalidRows: 0,
    }
    const current: HistoricalImportSummary = {
      ...legacy,
      id: 'new-1',
      originalFilename: 'opening.xlsx',
      format: 'OPENING_BALANCE',
      cutoverDate: '2026-09-30',
    }
    historyMock.mockResolvedValue([current, legacy])
    renderPage()
    expect(await screen.findByText('history-2022.xlsx')).toBeInTheDocument()
    expect(screen.getByText('Earlier format')).toBeInTheDocument()
    expect(screen.getByText('2026-09-30')).toBeInTheDocument()
  })

  it('has matching English and Kinyarwanda copy for every historical import key', () => {
    const flatten = (value: unknown, prefix = ''): string[] =>
      value && typeof value === 'object'
        ? Object.entries(value as Record<string, unknown>).flatMap(([key, child]) =>
            flatten(child, prefix ? `${prefix}.${key}` : key),
          )
        : [prefix]
    expect(flatten(rw.historicalImport).sort()).toEqual(flatten(en.historicalImport).sort())
  })
})
