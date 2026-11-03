# RAG 语料入库操作手册

| 字段 | 内容 |
|---|---|
| 适用系统 | RAG 语料平台（java-multi-agent / rag 模块） |
| 对应方案 | 《RAG语料录入线上化自动化方案 V1.0.2》 |
| 版本 | v1.0 |
| 编制日期 | 2026-11-02 |
| 读者 | 语料采集员、审核员、运维 |
| 命令说明 | **所有 `curl` 命令均可直接复制执行**，只需替换 `<尖括号>` 中的值 |

---

## 目录

1. [前置准备](#1-前置准备)
2. [完整入库流程总览](#2-完整入库流程总览)
3. [第一步：登记语料来源](#3-第一步登记语料来源)
4. [第二步：上传文件并自动处理](#4-第二步上传文件并自动处理)
5. [第三步：查看处理进度](#5-第三步查看处理进度)
6. [第四步：人工抽检](#6-第四步人工抽检)
7. [第五步：签发 VERIFIED](#7-第五步签发-verified)
8. [批量入库](#8-批量入库)
9. [常见错误与处置](#9-常见错误与处置)
10. [运维检查清单](#10-运维检查清单)

---

## 1. 前置准备

### 1.1 获取访问令牌

RAG 管理面**必须携带令牌**，无令牌一律 401。

```bash
# 平台域登录（拿到令牌）
curl -s -X POST "http://127.0.0.1:8080/api/platform/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"plat-auditor","password":"Plat#2026!"}'
```

响应示例：
```json
{
  "accessToken": "eyJhbGciOiJSUzI1NiIs...",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "userId": "fa41bfeb-0183-40ee-9420-55c526b90590",
  "roles": ["PLATFORM_AUDITOR"]
}
```

> ⚠️ **令牌 15 分钟过期**（`expiresIn: 900`）。长时间操作请先刷新。

把令牌存进环境变量，后续命令直接复用：

```bash
export RAG_TOKEN="<粘贴 accessToken>"
export RAG_TID="<你的租户 ID>"
export RAG_BASE="http://127.0.0.1:8081"
```

### 1.2 确认服务健康

```bash
curl -fsS "$RAG_BASE/actuator/health"
# 期望：{"groups":["liveness","readiness"],"status":"UP"}
```

### 1.3 必备请求头

| 头名 | 含义 | 示例 |
|---|---|---|
| `Authorization` | 令牌 | `Bearer eyJ...` |
| `X-Tenant-Id` | **租户 ID（必填）** | `2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2` |
| `X-Corpus-Scope` | 作用域：`TENANT` / `PLATFORM` | `TENANT` |

> ⚠️ **缺 `X-Tenant-Id` 一定失败**（400）。这是刻意的：多租户隔离不允许「猜一个默认租户」。

---

## 2. 完整入库流程总览

```
① 登记来源(元数据)  →  ② 上传文件(自动建 job)  →  ③ 查看进度
                                                      ↓
                          ⑤ 签发 VERIFIED  ←  ④ 人工抽检
                                                      ↓
                                            可被学生检索
```

**关键约束**（不理解会踩坑）：

| 约束 | 含义 |
|---|---|
| **自动化不放行** | 上传后系统只处理到「待审」，**永远不会自动变 VERIFIED** |
| **未抽检不得签发** | `VERIFIED` 前必须先过抽检，系统会 409 拦截 |
| **双人复核** | 签发 VERIFIED 需要**两个不同主体**，且都不能是采集人 |
| **整册失败不部分入库** | 解析失败则整本不入库，不会留下半本 |

---

## 3. 第一步：登记语料来源

「来源」是书目级记录（一本书 = 一个来源），下面可以挂多个文件。

```bash
curl -s -X POST "$RAG_BASE/api/v1/management/sources" \
  -H "Authorization: Bearer $RAG_TOKEN" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: $RAG_TID" \
  -H "X-Corpus-Scope: TENANT" \
  -d '{
    "sourceType": "TEXTBOOK",
    "title": "沪教版英语六年级上册",
    "publisher": "上海教育出版社",
    "isbn": "978-7-5444-0000-0",
    "registeredBy": "fa41bfeb-0183-40ee-9420-55c526b90590"
  }'
```

### 3.1 字段说明

| 字段 | 必填 | 说明 |
|---|---|---|
| `sourceType` | ✅ | 类型，如 `TEXTBOOK`、`PAPER` |
| `title` | ✅ | 书名（≤512 字符） |
| `publisher` | ✅ | 出版社 |
| `isbn` | 建议 | 教材强烈建议填，用于去重与校验 |
| `doi` | 视情况 | 论文类必填 |
| `registeredBy` | ✅ | **登记人 UUID**，用于「审核员≠采集员」校验 |

### 3.2 拿到 sourceId

响应中的 `sourceId` 是后续所有操作的钥匙：

```json
{
  "sourceId": "9a1c0000-0000-4000-8000-000000000001",
  "title": "沪教版英语六年级上册",
  "verificationStatus": "PENDING"
}
```

```bash
export SID="9a1c0000-0000-4000-8000-000000000001"
```

### 3.3 去重规则（重要）

系统会做**三层去重**，命中任一层直接 400 拒收：

| 层级 | 判据 | 错误码 |
|---|---|---|
| 来源级 | 同 ISBN / DOI | `DUP_SOURCE` |
| 版本级 | 同五轴坐标 | `DUP_EDITION` |
| 载体级 | 同内容哈希 | `DUP_CARRIER` |

> ⚠️ **去重只针对「活跃」对象**：已撤回（REVOKED）或解析失败（FAILED）的不算重复。
> 因此「撤回后重新登记」和「失败后重传」是**合法操作**，不会撞去重。

---

## 4. 第二步：上传文件并自动处理

上传成功即**自动创建处理任务**，并返回 `jobId`。

```bash
curl -s -X POST "$RAG_BASE/api/v1/management/sources/$SID/documents" \
  -H "Authorization: Bearer $RAG_TOKEN" \
  -H "X-Tenant-Id: $RAG_TID" \
  -H "X-Corpus-Scope: TENANT" \
  -F "file=@/path/to/教材.pdf" \
  -F "carrier=PDF"
```

响应：
```json
{
  "documentId": "55555555-5555-5555-5555-555555555555",
  "parseStatus": "PENDING",
  "verificationStatus": "PENDING",
  "retrievable": false,
  "jobId": "b1f76ece-87da-4eeb-8330-8517a1642773"
}
```

```bash
export DOCID="55555555-5555-5555-5555-555555555555"
export JOBID="b1f76ece-87da-4eeb-8330-8517a1642773"
```

### 4.1 上传前的三道自动检查

系统在上传时会拦掉这些情况（都在**落库之前**，不会产生垃圾数据）：

| 检查 | 不通过时 | 说明 |
|---|---|---|
| 后缀白名单 | 400 | 仅允许 PDF/EPUB/HTML/TXT/MD/PNG/JPEG |
| **魔数校验** | 400 | 防止把 `.exe` 改名成 `.pdf` |
| 去重三层 | 400 | 见 §3.3 |
| 操作人 | **401** | 无令牌主体即拒绝，**不会**用匿名身份 |

> ⚠️ **401 是最常见的「以为登录了其实没带主体」**。检查 `Authorization` 头是否正确带上。

### 4.2 文件大小限制

| 项 | 上限 |
|---|---|
| 单个文件 | 200 MB |
| 批量 ZIP | 2 GB |
| 单批文件数 | 100 个 |

### 4.3 上传后系统自动做什么

```
解析(Docling) → 清洗 → 切分 → 角色判定 → 知识点挂接 → 嵌入 → 索引
```

**这一段全自动，无需人工干预**，典型耗时：

| 页数 | 耗时（CPU 环境） |
|---|---|
| 20 页 | 约 40 秒 |
| 148 页 | 约 450 秒 |
| 162 页 | 约 500 秒 |

> ⚠️ 系统按 **20 页/段** 分段处理大文件，这是为了规避单次请求超时，**不是卡住了**。

---

## 5. 第三步：查看处理进度

### 5.1 查询单个任务

```bash
curl -s "$RAG_BASE/api/v1/management/ingest-jobs/$JOBID" \
  -H "Authorization: Bearer $RAG_TOKEN" \
  -H "X-Tenant-Id: $RAG_TID" | python3 -m json.tool
```

响应：
```json
{
  "jobId": "b1f76ece-...",
  "status": "EMBEDDING",
  "stage": "EMBEDDING 60",
  "attempt": 0,
  "chunkCount": null,
  "errorDetail": null
}
```

### 5.2 状态含义

| 状态 | 含义 | 是否终态 |
|---|---|---|
| `PENDING` | 排队中 | 否 |
| `PARSING` | 解析版面 | 否 |
| `CHUNKING` | 清洗与切分 | 否 |
| `EMBEDDING` | 落库与向量化 | 否 |
| `INDEXING` | 建索引 | 否 |
| `SUCCEEDED` | **完成** | ✅ |
| `FAILED` | 失败（`errorDetail` 给出原因） | ✅ |
| `REJECTED` | 已取消（如来源被撤回） | ✅ |

> `stage` 字段给出阶段内百分比（如 `EMBEDDING 60`），与 `status` 互补。

### 5.3 列表查询

```bash
# 某来源的全部任务
curl -s "$RAG_BASE/api/v1/management/ingest-jobs?sourceId=$SID&limit=20" \
  -H "Authorization: Bearer $RAG_TOKEN" -H "X-Tenant-Id: $RAG_TID"

# 本租户最近任务
curl -s "$RAG_BASE/api/v1/management/ingest-jobs?limit=20" \
  -H "Authorization: Bearer $RAG_TOKEN" -H "X-Tenant-Id: $RAG_TID"
```

### 5.4 失败后重试

```bash
curl -s -X POST "$RAG_BASE/api/v1/management/ingest-jobs/$JOBID/retry" \
  -H "Authorization: Bearer $RAG_TOKEN" -H "X-Tenant-Id: $RAG_TID"
```

> ⚠️ **重试会新建一条任务**（`attempt` + 1），旧任务永久保留。
> 这样「失败过几次」这个事实不会被抹掉。

### 5.5 查看切出的片段（可选，用于核对质量）

```bash
curl -s "$RAG_BASE/api/v1/management/documents/$DOCID/chunks?limit=50" \
  -H "Authorization: Bearer $RAG_TOKEN" -H "X-Tenant-Id: $RAG_TID" | python3 -m json.tool
```

---

## 6. 第四步：人工抽检

**这一步不能跳过**——未抽检的来源签发 VERIFIED 会被 409 拦截。

### 6.1 抽检口径

| 指标 | 要求 |
|---|---|
| 答案区识别准确率 | ≥ 95% |
| **答案区漏判** | **= 0（单点否决）** |
| 题号保留 | 100% |

> ⚠️ **漏判即泄题**：标准答案被判成「讲解」后，会被送进给学生看的回答里。
> 所以漏判是**一票否决**，不能用 95% 的平均准确率抵偿。

### 6.2 提交抽检

先取片段列表，逐条给判定：

```bash
curl -s -X POST "$RAG_BASE/api/v1/management/sources/$SID/inspection" \
  -H "Authorization: Bearer $RAG_TOKEN" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: $RAG_TID" \
  -d '{
    "items": [
      {
        "chunkId": "<片段ID-1>",
        "expectedRole": "EXPLANATION",
        "actualRole": "EXPLANATION",
        "verdict": "PASS"
      },
      {
        "chunkId": "<片段ID-2>",
        "expectedRole": "EXERCISE_ANSWER",
        "actualRole": "EXPLANATION",
        "errorType": "ANSWER_LEAK",
        "verdict": "FAIL"
      }
    ]
  }'
```

响应：
```json
{
  "passed": false,
  "total": 2,
  "failed": 1,
  "answerLeaks": 1,
  "accuracy": 0.5,
  "reason": "答案区漏判 1 处（漏判即泄题，单点否决）"
}
```

### 6.3 错误类型取值

| errorType | 含义 | 严重性 |
|---|---|---|
| `ANSWER_LEAK` | 答案区漏判 | 🔴 **单点否决** |
| `EXERCISE_NO_LOST` | 题号丢失/错位 | 🟡 |
| `LOCATOR_WRONG` | 页码坐标错误 | 🟡 |
| `CHUNK_BOUNDARY` | 切分边界错误 | 🟡 |
| `OTHER` | 其他 | 🟡 |

### 6.4 规则

| 规则 | 说明 |
|---|---|
| 至少 1 条 | 空抽检提交 → 400 |
| `FAIL` 必带 `errorType` | 否则 400（无法统计错在哪类） |
| `PASS` 不得带 `errorType` | 否则 400（数据矛盾） |

### 6.5 查询历史抽检

```bash
curl -s "$RAG_BASE/api/v1/management/sources/$SID/inspection?limit=100" \
  -H "Authorization: Bearer $RAG_TOKEN" -H "X-Tenant-Id: $RAG_TID"
```

---

## 7. 第五步：签发 VERIFIED

### 7.1 前置条件（系统强制）

| 条件 | 不满足时 |
|---|---|
| 已通过抽检 | **409** |
| 审核人 ≠ 采集人 | **409** |
| 提供复核人且 ≠ 执行人 | **409** |
| 角色含 REVIEWER / AUDITOR | **403** |

### 7.2 签发命令

```bash
# 第一个人：认领并提交审核
curl -s -X POST "$RAG_BASE/api/v1/management/sources/$SID/verification" \
  -H "Authorization: Bearer $RAG_TOKEN" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: $RAG_TID" \
  -H "X-Corpus-Scope: TENANT" \
  -d '{
    "targetStatus": "IN_REVIEW",
    "evidenceRef": "ISBN 校验通过 2026-11-02",
    "decidedBy": "<审核人UUID>",
    "verificationMethod": "ISBN"
  }'
```

```bash
# 第二个人：复核并签发 VERIFIED
curl -s -X POST "$RAG_BASE/api/v1/management/sources/$SID/verification" \
  -H "Authorization: Bearer $RAG_TOKEN" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: $RAG_TID" \
  -H "X-Corpus-Scope: TENANT" \
  -d '{
    "targetStatus": "VERIFIED",
    "verifiedBy": "<复核人UUID>",
    "evidenceRef": "双人复核通过 2026-11-02",
    "decidedBy": "<审核人UUID>",
    "verificationMethod": "ISBN",
    "trustTier": "OFFICIAL_PUBLISHER"
  }'
```

> ⚠️ `verifiedBy` 必须**不同于**当前令牌的 `sub`，也不同于采集人。

### 7.3 状态机（不可跳步）

```
PENDING ──→ IN_REVIEW ──→ VERIFIED
   │            │  └────→ REJECTED ──→ IN_REVIEW（可重审）
   │            └───────→ REVOKED（终态）
   └────────────────────→ REVOKED（终态）
VERIFIED ──→ IN_REVIEW（到期重校）
```

**非法转移一律 409**，例如 `REJECTED → VERIFIED` 直接跳步会被拒。

---

## 8. 批量入库

### 8.1 准备 ZIP 包

目录结构：
```
batch.zip
├── manifest.csv      ← 清单（文件名必须叫这个）
├── book1.pdf
├── book2.pdf
└── book3.pdf
```

`manifest.csv` 内容（**表头必须有 `filename` 和 `title`**）：
```csv
filename,title,isbn
book1.pdf,沪教版英语六年级上册,978-7-5444-0001-1
book2.pdf,沪教版英语六年级下册,978-7-5444-0002-2
book3.pdf,沪教版数学六年级上册,978-7-5444-0003-3
```

### 8.2 上传批量包

```bash
curl -s -X POST "$RAG_BASE/api/v1/management/sources/$SID/documents/bulk" \
  -H "Authorization: Bearer $RAG_TOKEN" \
  -H "X-Tenant-Id: $RAG_TID" \
  -H "X-Corpus-Scope: TENANT" \
  -F "file=@batch.zip"
```

### 8.3 结果解读

```json
{
  "total": 3,
  "accepted": 2,
  "rejected": 1,
  "items": [
    {"lineNo": 2, "filename": "book1.pdf", "accepted": true, "documentId": "...", "jobId": "..."},
    {"lineNo": 3, "filename": "book2.pdf", "accepted": true, "documentId": "...", "jobId": "..."},
    {"lineNo": 4, "filename": "book3.pdf", "accepted": false, "reason": "批量包内缺少该文件"}
  ],
  "extras": []
}
```

**逐条独立成败**：一条失败不影响其余。

| 字段 | 含义 |
|---|---|
| `extras` | ZIP 里有但清单没列的文件（被忽略） |

### 8.4 批量包的拒收规则

| 情况 | 处理 |
|---|---|
| 缺 `manifest.csv` | **整批拒绝** |
| ZIP 超 2GB / 文件数超 100 | **整批拒绝** |
| 含路径穿越条目（`../`） | **整批拒绝** |
| 清单某行缺 `filename` 或 `title` | 该行失败，其余继续 |
| 清单列了但 ZIP 里没这个文件 | 该行失败，其余继续 |
| ZIP 里有但清单没列 | 忽略，记入 `extras` |

---

## 9. 常见错误与处置

### 9.1 HTTP 状态码

| 状态码 | 含义 | 处置 |
|---|---|---|
| **400** | 参数/格式/去重问题 | 看响应里的错误码 |
| **401** | 无令牌或令牌无效 | 检查 `Authorization` 头，重新登录取令牌 |
| **403** | 角色无权 | 换有 REVIEWER/AUDITOR 角色的账号 |
| **404** | 来源/任务不存在 | 检查 ID 是否正确 |
| **409** | 治理规则被违反 | **不是故障**，看提示调整流程 |
| **503** | 服务未就绪 | 稍后重试 |

### 9.2 去重错误码

| 错误码 | 原因 | 处置 |
|---|---|---|
| `DUP_SOURCE` | 同 ISBN/DOI 的活跃来源已存在 | 复用已有来源，或确认原来源已撤回 |
| `DUP_EDITION` | 同五轴坐标的版本已存在 | 正常情况会复用，报错请检查来源 |
| `DUP_CARRIER` | 同内容的活跃文件已存在 | 确认是否重复上传 |

### 9.3 上传被拒

| 提示 | 原因 |
|---|---|
| 「文件内容不是 PDF（魔数不符）」 | 文件后缀是 .pdf 但内容不是 PDF |
| 「文件内容过短，无法识别格式」 | 空文件或损坏文件 |
| 「后缀不在白名单」 | 不支持的格式 |

### 9.4 任务失败

```bash
# 看失败原因
curl -s "$RAG_BASE/api/v1/management/ingest-jobs/$JOBID" \
  -H "Authorization: Bearer $RAG_TOKEN" -H "X-Tenant-Id: $RAG_TID" \
  | python3 -c "import json,sys;print(json.load(sys.stdin)['errorDetail'])"
```

| 错误前缀 | 含义 | 处置 |
|---|---|---|
| `PARSING 失败` | 文件无法解析 | 检查文件是否加密/损坏 |
| `SWEEPER_TIMEOUT` | 处理超时被接管 | 用 retry 重试，或拆分文件 |
| `SOURCE_REVOKED` | 来源被撤回导致取消 | **正常**，不是故障 |
| `CONSUMER_CRASH` | 执行器异常 | 用 retry 重试 |

### 9.5 签发被 409 拦截

| 提示 | 处置 |
|---|---|
| 「尚未通过人工抽检」 | 先做 §6 抽检 |
| 「审核员不得审核自己采集的来源」 | 换一个审核人 |
| 「需要双人复核」 | 补 `verifiedBy` |
| 「执行人与复核人为同一人」 | 换一个复核人 |

---

## 10. 运维检查清单

### 10.1 每日检查

```bash
# ① 服务健康
curl -fsS "$RAG_BASE/actuator/health"

# ② 统计概览
curl -s "$RAG_BASE/api/v1/management/statistics" \
  -H "Authorization: Bearer $RAG_TOKEN" -H "X-Tenant-Id: $RAG_TID" | python3 -m json.tool

# ③ 积压检查（PENDING 是否堆积）
curl -s "$RAG_BASE/api/v1/management/ingest-jobs?limit=50" \
  -H "Authorization: Bearer $RAG_TOKEN" -H "X-Tenant-Id: $RAG_TID" \
  | python3 -c "
import json,sys
d=json.load(sys.stdin)
pending=[j for j in d if j['status']=='PENDING']
failed=[j for j in d if j['status']=='FAILED']
print(f'待处理 {len(pending)} 条，失败 {len(failed)} 条')
for j in failed[:5]: print('  失败:', j['jobId'][:8], (j.get('errorDetail') or '')[:60])
"
```

### 10.2 问题清单

```bash
curl -s "$RAG_BASE/api/v1/management/issues" \
  -H "Authorization: Bearer $RAG_TOKEN" -H "X-Tenant-Id: $RAG_TID" | python3 -m json.tool
```

返回三类问题：缺向量的载体、解析失败的载体、待审核的来源。

### 10.3 重跑索引（只补缺失向量）

```bash
curl -s -X POST "$RAG_BASE/api/v1/management/reindex?limit=500" \
  -H "Authorization: Bearer $RAG_TOKEN" -H "X-Tenant-Id: $RAG_TID"
```

> ⚠️ 只补**缺失**的向量，不会重新解析或重新切分。

### 10.4 依赖服务检查

```bash
# Docling 解析服务
curl -fsS http://127.0.0.1:5001/health

# 嵌入模型服务
curl -fsS http://127.0.0.1:11434/api/tags | head -c 100

# 数据库
docker ps --filter name=rag-pg --format "{{.Names}} {{.Status}}"
```

### 10.5 告警阈值（系统内置）

| 指标 | 阈值 | 含义 |
|---|---|---|
| job 失败率 | > 20% | 剔除撤回取消 |
| 待处理积压 | > 50 条且 > 15 分钟 | 检查 worker 是否在跑 |
| Sweeper 接管 | > 3 次/小时 | worker 可能不稳定 |

---

## 附录 A：权限速查

| 操作 | 采集员 | 审核员 | 法务 | 运维 |
|---|---|---|---|---|
| 登记/上传 | ✅ | ❌ | ❌ | ❌ |
| 抽检 | ❌ | ✅ | ❌ | ❌ |
| 签发 VERIFIED | ❌ | ✅（需双人） | ❌ | ❌ |
| 撤回 | ❌ | ❌ | ✅ | ❌ |
| 失败重试 | ❌ | ❌ | ❌ | ✅ |

## 附录 B：单册入库速查（可直接整段执行）

```bash
export RAG_TOKEN="<令牌>"
export RAG_TID="<租户ID>"
export RAG_BASE="http://127.0.0.1:8081"
export ME="<你的UUID>"

# 1. 登记
SID=$(curl -s -X POST "$RAG_BASE/api/v1/management/sources" \
  -H "Authorization: Bearer $RAG_TOKEN" -H "Content-Type: application/json" \
  -H "X-Tenant-Id: $RAG_TID" \
  -d "{\"sourceType\":\"TEXTBOOK\",\"title\":\"书名\",\"publisher\":\"出版社\",\"isbn\":\"ISBN\",\"registeredBy\":\"$ME\"}" \
  | python3 -c "import json,sys;print(json.load(sys.stdin)['sourceId'])")
echo "来源: $SID"

# 2. 上传
JOB=$(curl -s -X POST "$RAG_BASE/api/v1/management/sources/$SID/documents" \
  -H "Authorization: Bearer $RAG_TOKEN" -H "X-Tenant-Id: $RAG_TID" \
  -F "file=@教材.pdf" \
  | python3 -c "import json,sys;print(json.load(sys.stdin).get('jobId',''))")
echo "任务: $JOB"

# 3. 轮询进度
while true; do
  ST=$(curl -s "$RAG_BASE/api/v1/management/ingest-jobs/$JOB" \
    -H "Authorization: Bearer $RAG_TOKEN" -H "X-Tenant-Id: $RAG_TID" \
    | python3 -c "import json,sys;d=json.load(sys.stdin);print(d['status'],d.get('stage') or '')")
  echo "  状态: $ST"
  case "$ST" in
    SUCCEEDED*) echo "✅ 处理完成，请抽检后签发"; break;;
    FAILED*|REJECTED*) echo "❌ 失败，查 errorDetail"; break;;
  esac
  sleep 10
done
```

## 附录 C：术语表

| 术语 | 含义 |
|---|---|
| **来源 (Source)** | 书目级记录，一本书 = 一个来源 |
| **载体 (Document)** | 物理文件，一个来源可挂多个载体 |
| **片段 (Chunk)** | 切分后的最小检索单元 |
| **Job** | 一次处理任务，重试会新建 |
| **抽检 (Inspection)** | 人工核对切分质量 |
| **G-1~G-4 闸门** | 检索期准入过滤（校验状态/有效期/权威等级/版权） |
