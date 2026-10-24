import { describe, it, expect, beforeEach } from 'vitest'
import {
  storeSession,
  loadSession,
  clearSession,
  isExpired,
  type StoredSession,
} from '@/auth/tokenStorage'

describe('tokenStorage', () => {
  beforeEach(() => {
    clearSession()
  })

  it('store 与 load 往返一致', () => {
    const s: StoredSession = {
      accessToken: 't',
      expiresAt: Date.now() + 1000,
      tenantId: 'x',
      principalId: 'p',
      roles: ['TENANT_ADMIN'],
    }
    storeSession(s)
    expect(loadSession()).toEqual(s)
  })

  it('clear 后 load 返回 null', () => {
    storeSession({ accessToken: 't', expiresAt: Date.now() + 1000 })
    clearSession()
    expect(loadSession()).toBeNull()
  })

  it('isExpired 随时间正确反映', () => {
    expect(isExpired({ accessToken: 't', expiresAt: Date.now() - 10 })).toBe(true)
    expect(isExpired({ accessToken: 't', expiresAt: Date.now() + 10000 })).toBe(false)
  })

  it('缺 roles 字段时回落为空数组', () => {
    storeSession({ accessToken: 't', expiresAt: Date.now() + 1000 })
    expect(loadSession()?.roles).toEqual([])
  })
})
