/**
 * 平台字典 API（把下拉枚举从代码常量变成可维护的数据）。
 *
 * ⚠️ 契约纪律：
 *    · `dictCode` 小写点分（如 `http.method`）；`itemCode` 是**写入业务表的实际取值**，
 *      后端按 Java 枚举校验的就是它。
 *    · 内置字典（builtIn=true）**禁止删除、禁止改 itemCode**，但可改中文标签 / 排序 / 停用；
 *      违反前两条后端返回 **409**（非法操作，不是服务故障）。
 *    · 删除 = 停用（后端不物理删行）。
 */

import { request } from '@/api/client'

export interface DictionaryItem {
  dictCode: string
  itemCode: string
  itemLabel: string
  sortOrder: number
  builtIn: boolean
  enabled: boolean
}

export interface DictionaryView {
  dictCode: string
  dictName: string
  description: string | null
  builtIn: boolean
  enabled: boolean
  sortOrder: number
  items: DictionaryItem[]
}

export interface CreateDictionaryRequest {
  dictCode: string
  dictName: string
  description?: string | null
  sortOrder?: number | null
}

export interface UpdateDictionaryRequest {
  dictName: string
  description?: string | null
  enabled?: boolean | null
  sortOrder?: number | null
}

export interface UpsertItemRequest {
  itemCode: string
  itemLabel: string
  sortOrder?: number | null
  enabled?: boolean | null
}

// ⚠️ 路径**不含** `/api` 前缀：`request()` 会统一拼 API_BASE（见 api/client.ts）。
//    自带前缀会拼成 `/api/api/...` → 后端返回 401 → 401 兜底清空会话并跳登录，
//    表现为「点菜单就被登出」。测试 mock 的是本模块函数，测不出这层拼接。
const BASE = '/platform/v1/dictionaries'

/** GET /api/platform/v1/dictionaries —— 全部字典及其取值。 */
export function getDictionaries(enabledOnly = false): Promise<DictionaryView[]> {
  return request<DictionaryView[]>(`${BASE}?enabledOnly=${enabledOnly ? 'true' : 'false'}`)
}

/** GET /api/platform/v1/dictionaries/{code} —— 单个字典。 */
export function getDictionary(dictCode: string, enabledOnly = false): Promise<DictionaryView> {
  return request<DictionaryView>(
    `${BASE}/${encodeURIComponent(dictCode)}?enabledOnly=${enabledOnly ? 'true' : 'false'}`,
  )
}

/** POST —— 新建字典（恒为非内置）。 */
export function createDictionary(payload: CreateDictionaryRequest): Promise<DictionaryView> {
  return request<DictionaryView>(BASE, { method: 'POST', body: JSON.stringify(payload) })
}

/** PUT —— 更新字典名称 / 说明 / 排序 / 启用（编码不可改）。 */
export function updateDictionary(
  dictCode: string,
  payload: UpdateDictionaryRequest,
): Promise<DictionaryView> {
  return request<DictionaryView>(`${BASE}/${encodeURIComponent(dictCode)}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

/** POST /{code}/items —— 写入或更新一项。 */
export function upsertDictionaryItem(
  dictCode: string,
  payload: UpsertItemRequest,
): Promise<DictionaryItem> {
  return request<DictionaryItem>(`${BASE}/${encodeURIComponent(dictCode)}/items`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

/** PUT /{code}/items/{itemCode} —— 更新一项（内置项不可改编码）。 */
export function updateDictionaryItem(
  dictCode: string,
  itemCode: string,
  payload: UpsertItemRequest,
): Promise<DictionaryItem> {
  return request<DictionaryItem>(
    `${BASE}/${encodeURIComponent(dictCode)}/items/${encodeURIComponent(itemCode)}`,
    { method: 'PUT', body: JSON.stringify(payload) },
  )
}

/** DELETE /{code}/items/{itemCode} —— **停用**一项（不是物理删除）。 */
export function disableDictionaryItem(
  dictCode: string,
  itemCode: string,
): Promise<DictionaryItem> {
  return request<DictionaryItem>(
    `${BASE}/${encodeURIComponent(dictCode)}/items/${encodeURIComponent(itemCode)}`,
    { method: 'DELETE' },
  )
}
