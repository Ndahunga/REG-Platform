import { describe, expect, it } from 'vitest'
import { isPreviewLoginEnabled } from './previewLogin'

describe('isPreviewLoginEnabled', () => {
  it('is disabled by default even in DEV', () => {
    expect(
      isPreviewLoginEnabled({
        DEV: true,
        PROD: false,
        MODE: 'development',
        VITE_ENABLE_PREVIEW_LOGIN: '',
        VITE_APP_ENV: 'development',
      } as never),
    ).toBe(false)
  })

  it('requires explicit VITE_ENABLE_PREVIEW_LOGIN=true in DEV', () => {
    expect(
      isPreviewLoginEnabled({
        DEV: true,
        PROD: false,
        MODE: 'development',
        VITE_ENABLE_PREVIEW_LOGIN: 'true',
        VITE_APP_ENV: 'development',
      } as never),
    ).toBe(true)
  })

  it('never enables in production builds', () => {
    expect(
      isPreviewLoginEnabled({
        DEV: false,
        PROD: true,
        MODE: 'production',
        VITE_ENABLE_PREVIEW_LOGIN: 'true',
        VITE_APP_ENV: 'production',
      } as never),
    ).toBe(false)
  })

  it('never enables when VITE_APP_ENV is production even if DEV is true', () => {
    expect(
      isPreviewLoginEnabled({
        DEV: true,
        PROD: false,
        MODE: 'development',
        VITE_ENABLE_PREVIEW_LOGIN: 'true',
        VITE_APP_ENV: 'production',
      } as never),
    ).toBe(false)
  })

  it('never enables for staging app env', () => {
    expect(
      isPreviewLoginEnabled({
        DEV: true,
        PROD: false,
        MODE: 'development',
        VITE_ENABLE_PREVIEW_LOGIN: 'true',
        VITE_APP_ENV: 'staging',
      } as never),
    ).toBe(false)
  })
})
