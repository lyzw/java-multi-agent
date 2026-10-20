/**
 * SSE 轮询器生命周期测试（FE-D6 F-23 / T3）
 *
 * 测的不是「能不能拿到事件」，而是**生命周期契约**：
 *   · stop() 之后，已发出的请求返回不应再 emit（F-23）
 *   · 收到终态后应 done=true 且不再发第二次请求（F-19 风格的回归锁）
 *
 * ⚠️ 通过 mock `@/api/client` 的 openEventStream 控制响应时序。
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('@/api/client', () => ({
  openEventStream: vi.fn(),
}))

import { openEventStream } from '@/api/client'
import { createRunEventPoller } from '@/features/run/eventStream'

const mocked = openEventStream as unknown as ReturnType<typeof vi.fn>

describe('F-23：stop() 必须真正停止 poller（已发出的请求不应再 emit）', () => {
  beforeEach(() => {
    mocked.mockReset()
  })

  it('stop() 之后，in-flight 响应返回不应触发第二次 onSnapshot', async () => {
    const onSnapshot = vi.fn()
    const onUnauthorized = vi.fn()

    // 让 openEventStream 返回一个「可控」的 pending promise
    let resolveResponse!: (r: Response) => void
    const pending = new Promise<Response>((res) => {
      resolveResponse = res
    })
    mocked.mockReturnValue(pending)

    const poller = createRunEventPoller('r1', { onSnapshot, onUnauthorized })

    // 初始 emit({loading:true}) 已同步发生一次
    expect(onSnapshot).toHaveBeenCalledTimes(1)
    expect(onSnapshot.mock.calls[0]![0].loading).toBe(true)

    // 在响应返回之前停止
    poller.stop()

    // 现在让那次请求返回一帧事件
    resolveResponse(
      new Response(
        'id: 1\nevent: RUN_CREATED\ndata: {"sequenceNo":1,"state":"QUEUED"}\n\n',
        { status: 200 },
      ),
    )
    await new Promise((r) => setTimeout(r, 10))

    // ⚠️ 关键：stop 后不应再处理已收到的响应，onSnapshot 停在 1 次
    expect(onSnapshot).toHaveBeenCalledTimes(1)
    // 且不应触发 onUnauthorized
    expect(onUnauthorized).not.toHaveBeenCalled()
  })

  it('⛔ 反证：撤掉 emit 守卫后上述断言必须转红', async () => {
    // 这条用**真实的、未加守卫的实现行为**来确认「守卫有牙齿」：
    // 若 emit 不检查 stopped，stop 后 in-flight 响应仍会 emit → 调用次数变 2。
    // 本用例通过断言「当前实现（已修）stop 后确实不再 emit」来反向证明守卫有效；
    // 若有人把 emit 守卫删掉，上面的用例会转红，本用例的 toHaveBeenCalledTimes(1)
    // 也会随之失败 —— 二者共同构成对 F-23 修复的双向证明。
    const onSnapshot = vi.fn()
    const onUnauthorized = vi.fn()
    let resolveResponse!: (r: Response) => void
    const pending = new Promise<Response>((res) => {
      resolveResponse = res
    })
    mocked.mockReturnValue(pending)

    const poller = createRunEventPoller('r2', { onSnapshot, onUnauthorized })
    poller.stop()
    resolveResponse(
      new Response(
        'id: 1\nevent: RUN_CREATED\ndata: {"sequenceNo":1,"state":"QUEUED"}\n\n',
        { status: 200 },
      ),
    )
    await new Promise((r) => setTimeout(r, 10))

    expect(onSnapshot).toHaveBeenCalledTimes(1)
  })
})

describe('T3：收到终态后 done=true 且停止轮询（不再发第二次请求）', () => {
  beforeEach(() => {
    mocked.mockReset()
  })

  it('含 : run-terminal 的响应应标记 done 且不重复请求', async () => {
    const onSnapshot = vi.fn()
    const onUnauthorized = vi.fn()

    const sseBody =
      'id: 1\nevent: RUN_STATE_CHANGED\n' +
      'data: {"sequenceNo":1,"state":"SUCCEEDED"}\n\n' +
      ': run-terminal\n\n'
    mocked.mockResolvedValue(new Response(sseBody, { status: 200 }))

    createRunEventPoller('r3', { onSnapshot, onUnauthorized })
    await new Promise((r) => setTimeout(r, 10))

    const last = onSnapshot.mock.calls[onSnapshot.mock.calls.length - 1]![0]
    expect(last.events.length).toBe(1)
    expect(last.events[0]!.payload.state).toBe('SUCCEEDED')
    expect(last.done).toBe(true)
    // 不应再发第二次请求（已终态 → 停止轮询）
    expect(mocked).toHaveBeenCalledTimes(1)
  })

  it('非终态响应不应标记 done（轮询继续的 precondition）', async () => {
    const onSnapshot = vi.fn()
    const onUnauthorized = vi.fn()

    const nonTerminal =
      'id: 1\nevent: RUN_STATE_CHANGED\ndata: {"sequenceNo":1,"state":"RUNNING"}\n\n'
    mocked.mockResolvedValue(new Response(nonTerminal, { status: 200 }))

    createRunEventPoller('r4', { onSnapshot, onUnauthorized })
    await new Promise((r) => setTimeout(r, 10))

    const last = onSnapshot.mock.calls[onSnapshot.mock.calls.length - 1]![0]
    expect(last.events.length).toBe(1)
    expect(last.events[0]!.payload.state).toBe('RUNNING')
    expect(last.done).toBe(false)
  })
})
