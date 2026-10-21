import { AxiosError, type InternalAxiosRequestConfig } from 'axios'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { queryClient } from '@/app/queryClient'
import { clearAuth } from '@/app/store/authSlice'
import { store } from '@/app/store/store'
import { apiClient, isSubscriptionInactiveError } from './client'

describe('apiClient subscription errors', () => {
  const originalAdapter = apiClient.defaults.adapter

  afterEach(() => {
    apiClient.defaults.adapter = originalAdapter
    vi.restoreAllMocks()
  })

  it('does not logout or refresh on 402 SUBSCRIPTION_INACTIVE', async () => {
    const dispatchSpy = vi.spyOn(store, 'dispatch')
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')
    const coopId = '11111111-1111-1111-1111-111111111111'
    apiClient.defaults.adapter = async (config) => {
      const error = new AxiosError(
        'Payment Required',
        'ERR_BAD_REQUEST',
        config as InternalAxiosRequestConfig,
        undefined,
        {
          status: 402,
          statusText: 'Payment Required',
          headers: {},
          config: config as InternalAxiosRequestConfig,
          data: {
            success: false,
            code: 'SUBSCRIPTION_INACTIVE',
            message: "This Saving Scheme's OuWealth subscription is inactive.",
            details: { cooperativeId: coopId, subscriptionStatus: 'EXPIRED' },
          },
        },
      )
      throw error
    }

    await expect(apiClient.post(`/cooperatives/${coopId}/members`, {})).rejects.toSatisfy((error) => {
      expect(isSubscriptionInactiveError(error)).toBe(true)
      expect((error as AxiosError).response?.status).toBe(402)
      return true
    })

    const types = dispatchSpy.mock.calls.map((call) => (call[0] as { type?: string }).type)
    expect(types).not.toContain(clearAuth.type)
    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: ['cooperatives', coopId, 'subscription'],
    })
  })
})
