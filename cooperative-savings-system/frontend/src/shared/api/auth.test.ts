import { describe, expect, it } from 'vitest'
import { unwrapApiData } from './auth'
import type { ApiResponse } from '@/shared/types/api'
import type { LoginResponse } from '@/shared/types/auth'

describe('unwrapApiData', () => {
  it('returns nested data from a successful ApiResponse', () => {
    const loginData: LoginResponse = {
      accessToken: 'token-abc',
      tokenType: 'Bearer',
      expiresIn: 900,
      user: {
        id: 'u-1',
        username: 'jane',
        email: 'jane@example.com',
        firstName: 'Jane',
        lastName: 'Doe',
        fullName: 'Jane Doe',
        roles: ['COOPERATIVE_ADMIN'],
        permissions: ['members:read'],
        cooperativeIds: ['coop-1'],
      },
    }

    const body: ApiResponse<LoginResponse> = {
      success: true,
      message: 'OK',
      data: loginData,
      timestamp: '2026-08-04T12:00:00Z',
    }

    expect(unwrapApiData(body)).toEqual(loginData)
    expect(unwrapApiData(body).user.username).toBe('jane')
    expect(unwrapApiData(body).user.cooperativeIds).toEqual(['coop-1'])
  })

  it('throws when success is false', () => {
    const body: ApiResponse<null> = {
      success: false,
      message: 'Invalid credentials',
      data: null as unknown as null,
      timestamp: '2026-08-04T12:00:00Z',
    }

    expect(() => unwrapApiData(body)).toThrow('Invalid credentials')
  })

  it('throws when data is missing', () => {
    const body = {
      success: true,
      message: 'OK',
      data: null,
      timestamp: '2026-08-04T12:00:00Z',
    } as unknown as ApiResponse<LoginResponse>

    expect(() => unwrapApiData(body)).toThrow()
  })
})
