import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from './client'
import { onboardCooperative } from './onboarding'

vi.mock('./client', () => ({
  apiClient: {
    post: vi.fn(),
  },
}))

vi.mock('./auth', () => ({
  unwrapApiData: (body: { data: unknown }) => body.data,
}))

const postMock = vi.mocked(apiClient.post)

describe('onboardCooperative', () => {
  beforeEach(() => {
    postMock.mockReset()
  })

  it('posts the public onboarding payload to /onboarding/signup', async () => {
    postMock.mockResolvedValue({
      data: {
        data: {
          accessToken: 'token',
          tokenType: 'Bearer',
          expiresIn: 900,
          user: { id: 'u1', cooperativeIds: ['c1'] },
        },
      },
    })
    const payload = {
      cooperative: {
        name: 'Public Scheme',
        contactEmail: 'scheme@example.com',
        contactPhone: '0781234567',
        currency: 'RWF',
        financialYearStartMonth: 1,
        monthlyContributionAmount: '5000',
        contributionDueDay: 5,
        registrationDate: '2024-01-15',
      },
      creator: {
        firstName: 'Pat',
        lastName: 'President',
        username: 'pat.president',
        email: 'pat@example.com',
        password: 'SignupPass1!',
      },
    }

    const result = await onboardCooperative(payload)

    expect(postMock).toHaveBeenCalledWith('/onboarding/signup', payload)
    expect(result.accessToken).toBe('token')
    expect(result.user.cooperativeIds).toEqual(['c1'])
    // the public payload carries no registration number
    expect(postMock.mock.calls[0][1]).not.toHaveProperty('cooperative.registrationNumber')
  })
})
