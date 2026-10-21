import { describe, expect, it } from 'vitest'
import { fallbackFilename, fileApiPath, fileDownloadPath, normalizeStorageKey } from './files'

describe('file paths', () => {
  it('strips leading slashes and optional /api/v1/files prefix', () => {
    expect(normalizeStorageKey('cooperatives/x/a.png')).toBe('cooperatives/x/a.png')
    expect(normalizeStorageKey('/cooperatives/x/a.png')).toBe('cooperatives/x/a.png')
    expect(normalizeStorageKey('api/v1/files/cooperatives/x/a.png')).toBe('cooperatives/x/a.png')
  })

  it('builds the authenticated apiClient path, not a browser navigation URL', () => {
    expect(fileApiPath('cooperatives/x/contribution_evidence/a.png')).toBe(
      '/files/cooperatives/x/contribution_evidence/a.png',
    )
    expect(fileDownloadPath('/cooperatives/x/a.png')).toBe('/api/v1/files/cooperatives/x/a.png')
  })

  it('uses the last path segment as a fallback filename', () => {
    expect(fallbackFilename('cooperatives/x/contribution_evidence/proof.png')).toBe('proof.png')
  })
})
