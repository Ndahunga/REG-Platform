import { describe, expect, it } from 'vitest'
import { INTEREST_TYPES } from '@/shared/types/loan'
import { finePaymentSchema, toFinePaymentPayload } from '@/features/fines/fineFormSchemas'

describe('production-critical form guards', () => {
  it('loan settings interest types exclude REDUCING for new configuration', () => {
    expect(INTEREST_TYPES).toEqual(['FLAT'])
    expect(INTEREST_TYPES).not.toContain('REDUCING')
  })

  it('fine payment payload includes method and optional evidence key', async () => {
    const schema = finePaymentSchema(100)
    const values = await schema.validate({
      amount: '50',
      paymentDate: '2026-01-15',
      paymentMethod: 'MOBILE_MONEY',
      paymentMethodDetail: '',
      paymentReference: 'MOMO-1',
      notes: '',
      evidenceFileKey: 'cooperatives/x/fine_payment_evidence/abc.pdf',
    })
    const payload = toFinePaymentPayload(values)
    expect(payload.paymentMethod).toBe('MOBILE_MONEY')
    expect(payload.evidenceFileKey).toContain('fine_payment_evidence')
  })
})
