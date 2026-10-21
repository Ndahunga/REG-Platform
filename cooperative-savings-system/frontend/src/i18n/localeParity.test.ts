import { describe, expect, it } from 'vitest'
import en from './locales/en.json'
import rw from './locales/rw.json'

type Tree = { [key: string]: string | Tree }

function flatten(tree: Tree, prefix = ''): Record<string, string> {
  const out: Record<string, string> = {}
  for (const [key, value] of Object.entries(tree)) {
    const path = prefix + key
    if (typeof value === 'string') out[path] = value
    else Object.assign(out, flatten(value, `${path}.`))
  }
  return out
}

/**
 * i18next picks a plural form by language: English has `_one`/`_other`, Kinyarwanda may carry either or both. Two
 * languages cover the same message when they agree on the key without the plural suffix.
 */
const PLURAL = /_(zero|one|two|few|many|other)$/
const base = (key: string) => key.replace(PLURAL, '')

const enFlat = flatten(en as Tree)
const rwFlat = flatten(rw as Tree)
const placeholders = (text: string) => [...text.matchAll(/\{\{\s*([\w.]+)/g)].map((m) => m[1]).sort()

/** Values that are display acronyms or badges, not enum codes. Anything else shaped like CODE_NAME is a raw enum. */
const ENUM_SHAPE = /^[A-Z][A-Z0-9]*(_[A-Z0-9]+)+$/

describe('translations: English and Kinyarwanda', () => {
  it('cover exactly the same messages', () => {
    const enBases = new Set(Object.keys(enFlat).map(base))
    const rwBases = new Set(Object.keys(rwFlat).map(base))
    expect([...enBases].filter((k) => !rwBases.has(k)).sort()).toEqual([])
    expect([...rwBases].filter((k) => !enBases.has(k)).sort()).toEqual([])
  })

  it('give every plural message the forms both languages need', () => {
    const rwKeys = Object.keys(rwFlat)
    for (const key of Object.keys(enFlat).filter((k) => PLURAL.test(k))) {
      const b = base(key)
      expect(rwKeys.some((k) => base(k) === b), `rw has no form of ${b}`).toBe(true)
    }
  })

  it('use the same interpolation placeholders in both languages', () => {
    const mismatched: string[] = []
    for (const [key, text] of Object.entries(enFlat)) {
      const other = rwFlat[key]
      if (other === undefined) continue
      if (placeholders(text).join('|') !== placeholders(other).join('|')) mismatched.push(key)
    }
    expect(mismatched).toEqual([])
  })

  it('have no empty message in either language', () => {
    expect(Object.entries(enFlat).filter(([, v]) => v.trim() === '').map(([k]) => k)).toEqual([])
    expect(Object.entries(rwFlat).filter(([, v]) => v.trim() === '').map(([k]) => k)).toEqual([])
  })

  it('never show a raw enum code as a message', () => {
    const raw = (flat: Record<string, string>) =>
      Object.entries(flat)
        .filter(([, v]) => ENUM_SHAPE.test(v))
        .map(([k, v]) => `${k} = ${v}`)
    expect(raw(enFlat)).toEqual([])
    expect(raw(rwFlat)).toEqual([])
  })

  it('keep the payment, rotation and import messages translated, not copied from English', () => {
    // a reference line that is a format rather than prose may read the same in both languages
    const formats = new Set(['rotation.payouts.line'])
    const copied = Object.keys(enFlat).filter(
      (k) =>
        /^(payments|rotation|historicalImport)\./.test(k) &&
        !formats.has(k) &&
        rwFlat[k] === enFlat[k] &&
        enFlat[k].length > 25,
    )
    expect(copied).toEqual([])
  })
})
