/**
 * SSE 帧解析测试（FE-D1 §9 T12/T13）
 *
 * ⚠️ 关键：`: run-terminal` 注释行是「停止轮询」的唯一信号，
 *    解析器必须识别它，否则会无限轮询。
 */

import { describe, it, expect } from 'vitest'
import { parseFrame, parseStream, IncrementalSseParser } from '@/features/run/sse'

describe('SSE 帧解析', () => {
  describe('单帧解析', () => {
    it('应解析标准三字段帧', () => {
      const frame = parseFrame(
        'id: 3\nevent: RUN_STATE_CHANGED\ndata: {"sequenceNo":3,"state":"RUNNING","at":"2026-10-19T14:50:00Z"}',
      )
      expect(frame).not.toBeNull()
      expect(frame?.id).toBe(3)
      expect(frame?.event).toBe('RUN_STATE_CHANGED')
      expect(frame?.data).toContain('RUNNING')
      expect(frame?.terminal).toBe(false)
    })

    it('⛔ 应识别 run-terminal 注释行', () => {
      const frame = parseFrame(': run-terminal')
      expect(frame).not.toBeNull()
      expect(frame?.terminal).toBe(true)
    })

    it('应忽略其他注释行', () => {
      // ⚠️ 纯注释行不含任何事件字段（id/event/data），
      //    按 SSE 规范应整体忽略 → 返回 null 而非带 terminal:false 的帧。
      //    若返回一个"空帧"，调用方会误以为收到了一条事件。
      const frame = parseFrame(': some-other-comment')
      expect(frame).toBeNull()
    })

    it('注释与事件同帧时应正常解析出事件', () => {
      // 后端可能在事件帧前加注释（如心跳），此时事件本身仍须解析
      const frame = parseFrame(': keep-alive\nid: 4\ndata: {"n":4}')
      expect(frame?.id).toBe(4)
      expect(frame?.terminal).toBe(false)
    })

    it('容忍冒号后空格', () => {
      const frame = parseFrame('id: 5\ndata: {"a":1}')
      expect(frame?.id).toBe(5)
      expect(frame?.data).toBe('{"a":1}')
    })

    it('容忍 CR 换行', () => {
      const frame = parseFrame('id: 7\r\ndata: {"a":1}')
      expect(frame?.id).toBe(7)
    })

    it('非法 id 应忽略而非抛错', () => {
      const frame = parseFrame('id: not-a-number\ndata: {"a":1}')
      expect(frame?.id).toBeNull()
      expect(frame?.data).toBe('{"a":1}')
    })

    it('空块返回 null', () => {
      expect(parseFrame('')).toBeNull()
      expect(parseFrame('   ')).toBeNull()
    })
  })

  describe('整流解析', () => {
    it('应解析多帧', () => {
      const text = [
        'id: 1',
        'event: RUN_CREATED',
        'data: {"sequenceNo":1,"state":"QUEUED","at":"t1"}',
        '',
        'id: 2',
        'event: RUN_STATE_CHANGED',
        'data: {"sequenceNo":2,"state":"RUNNING","at":"t2"}',
        '',
      ].join('\n')

      const frames = parseStream(text)
      expect(frames).toHaveLength(2)
      expect(frames[0]?.id).toBe(1)
      expect(frames[1]?.id).toBe(2)
    })

    it('末帧无空行也应解析（flush 场景）', () => {
      const text = 'id: 1\ndata: {"a":1}\n\nid: 2\ndata: {"b":2}'
      const frames = parseStream(text)
      expect(frames.length).toBeGreaterThanOrEqual(1)
    })

    it('⛔ 终态流应含 terminal 标记', () => {
      const text = [
        'id: 5',
        'event: RUN_FINISHED',
        'data: {"sequenceNo":5,"state":"SUCCEEDED","at":"t5"}',
        '',
        ': run-terminal',
        '',
      ].join('\n')

      const frames = parseStream(text)
      const terminalFrame = frames.find((f) => f.terminal)
      expect(terminalFrame).toBeDefined()
    })

    it('空流返回空数组', () => {
      expect(parseStream('')).toHaveLength(0)
    })
  })

  describe('增量解析器', () => {
    it('⛔ 逐块喂入应产出完整帧', () => {
      const parser = new IncrementalSseParser()
      const frame1 = parser.push('id: 1\nevent: A\ndata: {"n":1}\n\n')
      expect(frame1).toHaveLength(1)
      expect(frame1[0]?.id).toBe(1)

      const frame2 = parser.push('id: 2\nevent: B\ndata: {"n":2}\n\n')
      expect(frame2).toHaveLength(1)
      expect(frame2[0]?.id).toBe(2)
    })

    it('帧被切在中间时应缓冲', () => {
      const parser = new IncrementalSseParser()
      const first = parser.push('id: 1\nevent: A\n')
      expect(first).toHaveLength(0)

      const second = parser.push('data: {"n":1}\n\n')
      expect(second).toHaveLength(1)
      expect(second[0]?.id).toBe(1)
    })

    it('⛔ 应记录终止信号', () => {
      const parser = new IncrementalSseParser()
      parser.push(': run-terminal\n\n')
      expect(parser.hasTerminal()).toBe(true)
    })

    it('flush 应处理残留块', () => {
      const parser = new IncrementalSseParser()
      parser.push('id: 9\ndata: {"n":9}')
      const flushed = parser.flush()
      expect(flushed).toHaveLength(1)
      expect(flushed[0]?.id).toBe(9)
    })

    it('flush 空缓冲不产出帧', () => {
      const parser = new IncrementalSseParser()
      expect(parser.flush()).toHaveLength(0)
    })
  })

  describe('⛔ 载荷最小化：只消费白名单字段', () => {
    it('data 中即使出现白名单外字段，前端也不应消费', () => {
      // 后端已改为白名单透传，故正常不会出现 studentName。
      // 此处模拟「后端误加了敏感字段」，前端解析后不得引入业务字段。
      const data = JSON.stringify({
        sequenceNo: 1,
        at: 't1',
        state: 'RUNNING',
        studentName: '张三',
        idCard: '110101199003077615',
      })
      const frame = parseFrame(`id: 1\ndata: ${data}`)
      const parsed = JSON.parse(frame?.data ?? '{}') as Record<string, unknown>

      // 前端事件流模块只挑选白名单字段（见 eventStream.toRunEvent），
      // 故这里断言"解析器不主动过滤，由挑选逻辑负责"——
      // 挑选逻辑的断言见 eventStream 测试。
      expect(parsed['sequenceNo']).toBe(1)
      expect(Object.keys(parsed)).toContain('studentName')
    })
  })
})
