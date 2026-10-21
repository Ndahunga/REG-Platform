import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

describe('public sitemap', () => {
  it('lists only intended public marketing routes', () => {
    const xml = readFileSync(
      resolve(__dirname, '../../public/sitemap.xml'),
      'utf8',
    )
    expect(xml).toContain('https://wealthcommunity.ousuite.com/')
    expect(xml).toContain('https://wealthcommunity.ousuite.com/about')
    expect(xml).toContain('https://wealthcommunity.ousuite.com/contact')
    // privacy is a section of the Terms now; the retired page must never be advertised
    expect(xml).not.toContain('/privacy')
    expect(xml).not.toContain('/terms')
    expect(xml).not.toContain('/login')
    expect(xml).not.toContain('/dashboard')
    expect(xml).not.toContain('/members')
    expect(xml).not.toContain('/billing')
    expect(xml).not.toContain('/settings')
    expect(xml).not.toContain('/loans')
  })
})
