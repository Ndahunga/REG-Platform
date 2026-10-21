import { describe, expect, it } from 'vitest'
import en from '@/i18n/locales/en.json'
import rw from '@/i18n/locales/rw.json'

function collectStrings(value: unknown, acc: string[] = []): string[] {
  if (typeof value === 'string') {
    acc.push(value)
    return acc
  }
  if (Array.isArray(value)) {
    for (const item of value) collectStrings(item, acc)
    return acc
  }
  if (value && typeof value === 'object') {
    for (const child of Object.values(value)) collectStrings(child, acc)
  }
  return acc
}

describe('Saving Scheme terminology', () => {
  it('keeps i18n keys while showing Saving Scheme in English', () => {
    expect(en.nav).toHaveProperty('cooperatives')
    expect(en.common).toHaveProperty('selectCooperative')
    expect(en.cooperatives.create).toBe('Create Saving Scheme')
    expect(en.nav.cooperatives).toBe('Saving Schemes')
    expect(en.common.selectCooperative).toBe('Select Saving Scheme')
    expect(en.cooperatives.noneSelected).toBe('No Saving Scheme selected')
    expect(en.auditLogs.entityTypes.Cooperative).toBe('Saving Scheme')
  })

  it('shows Ikimina / Ibimina in Kinyarwanda', () => {
    expect(rw.nav.cooperatives).toBe('Ibimina')
    expect(rw.common.selectCooperative).toBe('Hitamo ikimina')
    expect(rw.cooperatives.create).toBe('Kora ikimina')
    expect(rw.cooperatives.noneSelected).toBe('Nta kimina cyahiswemo')
    expect(rw.auditLogs.entityTypes.Cooperative).toBe('Ikimina')
  })

  it('does not leave cooperative / ikoperative in locale values', () => {
    const enHits = collectStrings(en).filter((text) => /\bcooperatives?\b/i.test(text))
    const rwHits = collectStrings(rw).filter((text) => /koperative|ikcooperative/i.test(text))
    expect(enHits).toEqual([])
    expect(rwHits).toEqual([])
  })

  it('leaves itsinda in Kinyarwanda where it means group', () => {
    expect(collectStrings(rw).some((text) => text.includes('itsinda'))).toBe(true)
  })
})
