// @vitest-environment jsdom
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { Form } from 'antd'
import { ConfigCenterPage } from '@/pages/settings/ConfigCenterPage'
import { NAV_GROUPS, NAV_NODES, findNavGroup } from '@/layout/navConfig'
import { ParamField } from '@/shared/config/ParamField'

/**
 * 配置管理重规划守卫：
 * 1) 曾经散在三个分区的配置入口必须收口到配置管理；
 * 2) 总览页只做索引，不复制写入表单；
 * 3) 参数字段用同一套文案契约（名称 + 语义 + 边界）。
 */
describe('配置管理导航分区', () => {
  it('配置总览 / 路由策略 / 对话审计 / 凭据引用归配置管理；发布策略归发布域', () => {
    const configPaths = ['/config', '/routing-policies', '/chat-audit-config', '/config/credentials']
    for (const path of configPaths) {
      const group = findNavGroup(path)
      expect(group, path).toBeDefined()
      expect(group?.key.startsWith('config-'), path).toBe(true)
    }
    const publishPolicyGroup = findNavGroup('/publish/policies')
    expect(publishPolicyGroup?.key).toBe('ops-observability')
    expect(NAV_NODES.find((n) => n.path === '/publish/policies')?.section).toBe('发布策略')
  })

  it('配置入口全站唯一（防止总览页再制造一份同路径菜单）', () => {
    const paths = NAV_NODES.map((n) => n.path)
    expect(new Set(paths).size).toBe(paths.length)
    expect(NAV_GROUPS.filter((g) => g.key.startsWith('config-')).length).toBe(2)
  })
})

describe('配置管理总览页', () => {
  it('展示四类入口与参数口径，不提供保存按钮', () => {
    render(
      <MemoryRouter>
        <ConfigCenterPage />
      </MemoryRouter>,
    )
    expect(screen.getByText('配置管理总览')).toBeTruthy()
    for (const title of ['基础配置', '策略与审计配置', 'RAG 规则配置', '配置变更的后续动作']) {
      expect(screen.getAllByText(title).length).toBeGreaterThan(0)
    }
    expect(screen.getByText(/参数名一律与后端字段路径一致/)).toBeTruthy()
    expect(screen.queryByRole('button', { name: '保存配置' })).toBeNull()
    expect(screen.getAllByText('进入配置 →').length).toBeGreaterThanOrEqual(10)
    // 索引从 NAV_GROUPS 派生：发布策略虽已归位到发布域，仍应出现在「策略与审计配置」
    expect(screen.getByText('发布策略配置')).toBeTruthy()
    expect(screen.getByText('高风险')).toBeTruthy()
  })
})

describe('统一参数字段', () => {
  it('数值参数同时展示语义与范围，并使用后端字段路径作为 name', async () => {
    render(
      <Form initialValues={{ retention: { days: 30 } }}>
        <ParamField
          spec={{
            name: ['retention', 'days'],
            label: '保留天数',
            kind: 'number',
            description: 'PIPL 留存，默认 30 天。',
            min: 1,
            max: 180,
            unit: '天',
            risk: 'high',
          }}
        />
      </Form>,
    )
    const input = await screen.findByLabelText('保留天数') as HTMLInputElement
    expect(input.value).toBe('30')
    expect(screen.getByText(/范围 1 ~ 180/)).toBeTruthy()
    expect(screen.getByText(/单位 天/)).toBeTruthy()
    expect(screen.getByText('高风险')).toBeTruthy()
  })
})
