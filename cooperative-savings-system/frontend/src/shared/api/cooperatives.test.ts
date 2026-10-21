import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from './client'
import { assignCooperativePresident, createCooperative, fetchCooperativeSubscription } from './cooperatives'

vi.mock('./client', () => ({
  apiClient: {
    get: vi.fn(),
    post: vi.fn(),
  },
  isNotFoundError: (error: { response?: { status?: number } }) => error?.response?.status === 404,
}))

vi.mock('./auth', () => ({
  unwrapApiData: (body: { data: unknown }) => body.data,
}))

const getMock = vi.mocked(apiClient.get)
const postMock = vi.mocked(apiClient.post)

describe('cooperatives API wrappers', () => {
  beforeEach(() => {
    getMock.mockReset()
    postMock.mockReset()
  })

  it('posts create payload including subscriptionInitialization and president', async () => {
    postMock.mockResolvedValue({
      data: { data: { id: 'c1', name: 'Demo', status: 'ACTIVE', onboardingState: 'COMPLETE' } },
    })
    const payload = {
      name: 'Demo',
      currency: 'RWF',
      financialYearStartMonth: 1,
      monthlyContributionAmount: '1000',
      contributionDueDay: 1,
      registrationDate: '2024-01-15',
      subscriptionInitialization: 'START_TRIAL' as const,
      president: { userId: 'user-1' },
    }
    await createCooperative(payload)
    expect(postMock).toHaveBeenCalledWith('/cooperatives', payload)
  })

  it('assigns president through the existing administrators endpoint', async () => {
    postMock.mockResolvedValue({
      data: {
        data: {
          userId: 'user-1',
          firstName: 'Pat',
          lastName: 'President',
          username: 'pat',
          email: 'pat@test.local',
          membershipStatus: 'ACTIVE',
          accountStatus: 'ACTIVE',
          roleInCooperative: 'PRESIDENT',
        },
      },
    })
    await assignCooperativePresident('coop-1', { username: 'pat', email: 'pat@test.local', firstName: 'Pat', lastName: 'President' })
    expect(postMock).toHaveBeenCalledWith('/cooperatives/coop-1/administrators', {
      username: 'pat',
      email: 'pat@test.local',
      firstName: 'Pat',
      lastName: 'President',
    })
  })

  it('returns null when subscription is missing', async () => {
    getMock.mockRejectedValue({ response: { status: 404 } })
    await expect(fetchCooperativeSubscription('coop-1')).resolves.toBeNull()
    expect(getMock).toHaveBeenCalledWith('/cooperatives/coop-1/subscription')
  })
})
