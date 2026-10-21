import { ThemeProvider } from '@mui/material'
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { lightTheme } from '@/theme/theme'
import { LoanSchedulePreview } from './LoanSchedulePreview'

describe('LoanSchedulePreview', () => {
  it('renders an em dash for null optional schedule amounts and dates', () => {
    render(
      <ThemeProvider theme={lightTheme}>
        <LoanSchedulePreview
          schedule={{
            prorataEnabled: false,
            regularMonthlyInterest: null,
            firstPeriodInterest: null,
            totalInterest: null,
            totalRepayment: null,
            equalInstallmentAmount: null,
            scheduleFinalized: true,
            installments: [
              {
                installmentNumber: 1,
                dueDate: null,
                openingPrincipalBalance: null,
                principalComponent: null,
                interestComponent: null,
                paymentAmount: null,
                penaltyDue: null,
                status: 'UNKNOWN_STATUS',
              },
            ],
          }}
        />
      </ThemeProvider>,
    )

    expect(screen.getAllByText('—').length).toBeGreaterThan(0)
    expect(screen.getByText('UNKNOWN_STATUS')).toBeInTheDocument()
    expect(screen.queryByText(/Invalid monetary amount/i)).not.toBeInTheDocument()
  })

  it('formats valid installment amounts', () => {
    render(
      <ThemeProvider theme={lightTheme}>
        <LoanSchedulePreview
          schedule={{
            prorataEnabled: false,
            regularMonthlyInterest: 1600,
            totalInterest: 3200,
            totalRepayment: 83200,
            equalInstallmentAmount: 41600,
            scheduleFinalized: true,
            installments: [
              {
                installmentNumber: 1,
                dueDate: '2026-02-15',
                principalComponent: 40000,
                interestComponent: 1600,
                paymentAmount: 41600,
                status: 'PENDING',
              },
            ],
          }}
        />
      </ThemeProvider>,
    )

    expect(screen.getByText('2026-02-15')).toBeInTheDocument()
    expect(screen.getAllByText(/40,000/).length).toBeGreaterThan(0)
    expect(screen.getByText('Pending')).toBeInTheDocument()
  })
})
