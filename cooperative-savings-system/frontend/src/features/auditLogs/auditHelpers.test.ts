import { describe, expect, it } from 'vitest'
import {
  auditEntityLabel,
  auditUserLabel,
  parseJsonSafe,
  toIsoDateEnd,
  toIsoDateStart,
} from './auditHelpers'

describe('auditHelpers', () => {
  it('parses JSON safely', () => {
    expect(parseJsonSafe('{"x":true}')).toEqual({ x: true })
    expect(parseJsonSafe('bad')).toBe('bad')
    expect(parseJsonSafe(null)).toBeNull()
  })

  it('builds ISO day bounds', () => {
    expect(toIsoDateStart('2026-08-01')).toBe('2026-08-01T00:00:00Z')
    expect(toIsoDateEnd('2026-08-01')).toBe('2026-08-01T23:59:59Z')
    expect(toIsoDateStart('')).toBeUndefined()
  })

  it('prefers resolved user and entity names over raw IDs', () => {
    expect(
      auditUserLabel({
        userName: 'System Administrator',
        username: 'superadmin',
        userId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
      }),
    ).toBe('System Administrator')
    expect(
      auditUserLabel({
        userName: null,
        username: 'superadmin',
        userId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
      }),
    ).toBe('superadmin')
    expect(auditUserLabel({ userName: null, username: null, userId: 'raw-id' })).toBe('—')
    expect(
      auditEntityLabel({
        entityLabel: 'Terimbere Cooperative',
        entityType: 'Cooperative',
      }),
    ).toBe('Terimbere Cooperative')
    expect(auditEntityLabel({ entityLabel: null, entityType: 'Fine' })).toBe('—')
  })
})
