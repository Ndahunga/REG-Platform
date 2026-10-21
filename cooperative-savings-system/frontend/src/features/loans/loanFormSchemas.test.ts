import { describe, expect, it } from 'vitest'
import {
  loanApproveDefaults,
  loanApproveSchema,
  loanRequestDefaults,
  loanSettingsDefaults,
  toLoanApprovePayload,
  toLoanCreatePayload,
  toLoanSettingsPayload,
} from './loanFormSchemas'

describe('toLoanCreatePayload', () => {
  it('sends term without borrower prorata or client-calculated totals', () => {
    const payload = toLoanCreatePayload(
      {
        ...loanRequestDefaults,
        amount: '300000',
        termMonths: '5',
        purpose: 'Business',
      },
      false,
    )
    expect(payload).toEqual({
      amount: '300000',
      purpose: 'Business',
      notes: undefined,
      guaranteeMode: 'SELF',
      termMonths: 5,
    })
    expect(payload).not.toHaveProperty('prorataEnabled')
    expect(payload).not.toHaveProperty('totalInterest')
    expect(payload).not.toHaveProperty('equalInstallmentAmount')
  })
})

describe('loanApproveSchema', () => {
  it('accepts amount and term without a due date', async () => {
    await expect(
      loanApproveSchema.validate({
        approvedAmount: '80000',
        termMonths: '6',
      }),
    ).resolves.toEqual({
      approvedAmount: '80000',
      termMonths: '6',
    })
  })

  it('accepts empty amount and term', async () => {
    await expect(loanApproveSchema.validate(loanApproveDefaults)).resolves.toEqual({
      approvedAmount: '',
      termMonths: '',
    })
  })

  it('rejects an invalid approved amount', async () => {
    await expect(
      loanApproveSchema.validate({
        approvedAmount: 'abc',
        termMonths: '6',
      }),
    ).rejects.toThrow('Enter a valid amount')
  })

  it('rejects a non-integer term', async () => {
    await expect(
      loanApproveSchema.validate({
        approvedAmount: '80000',
        termMonths: '1.5',
      }),
    ).rejects.toThrow('Enter a valid term in months')
  })
})

describe('toLoanApprovePayload', () => {
  it('sends only approvedAmount and termMonths', () => {
    const payload = toLoanApprovePayload({
      approvedAmount: '80000',
      termMonths: '6',
    })
    expect(payload).toEqual({
      approvedAmount: '80000',
      termMonths: 6,
    })
    expect(payload).not.toHaveProperty('dueDate')
  })

  it('omits empty fields and never sends dueDate', () => {
    const payload = toLoanApprovePayload(loanApproveDefaults)
    expect(payload).toEqual({})
    expect(payload).not.toHaveProperty('dueDate')
  })
})

describe('toLoanSettingsPayload', () => {
  it('sends cooperative repayment and penalty policy', () => {
    const payload = toLoanSettingsPayload(
      {
        ...loanSettingsDefaults,
        interestRatePercent: '2',
        repaymentDateModel: 'MONTH_END',
        loanPenaltyEnabled: true,
        penaltyType: 'PERCENTAGE_OF_OVERDUE_INSTALLMENT',
        penaltyRateOrAmount: '5',
        penaltyFrequency: 'DAILY',
        gracePeriodDays: '3',
        allocationOrder: ['INTEREST', 'PENALTY', 'PRINCIPAL'],
      },
      false,
    )
    expect(payload.repaymentDateModel).toBe('MONTH_END')
    expect(payload.loanPenaltyEnabled).toBe(true)
    expect(payload.allocationOrder).toEqual(['INTEREST', 'PENALTY', 'PRINCIPAL'])
    expect(payload).not.toHaveProperty('shareTiers')
  })
})
