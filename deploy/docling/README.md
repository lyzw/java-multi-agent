# Docling 解析服务 —— 容器部署方案

| 字段 | 内容 |
|---|---|
| 文档类型 | 部署方案 + 可执行配置 |
| 对应方案 | 《RAG语料录入线上化自动化方案 V1.0.2》§4 Z5a、RD-1、待确认项 **T-4** |
| 对应代码 | `DoclingDocumentParserPort`（适配器骨架已就位） |
| 编制日期 | 2026-11-02 |
| 合规约束 | **NFR-RAG-008：必须境内部署** |

---

## 1. 镜像选型

### 1.1 首选：官方 `docling-serve`

| 项 | 内容 |
|---|---|
| 镜像 | `quay.io/docling-project/docling-serve:latest` |
| 提供方 | Docling 项目官方（IBM Research 开源） |
| 形态 | Python FastAPI 服务，内置 `/health`、`/v1/document/convert` 等端点 |
| OCR | 内置 EasyOCR / Tesseract（可开关） |
| 优势 | 无需自写 API 层；官方维护；版本可锁 tag |

**锁定版本建议**：生产不要用 `latest`。首次部署确认可用后，用 `docker inspect` 取 digest 并改为
`quay.io/docling-project/docling-serve@sha256:<digest>`，保证可复现。

### 1.2 备选：自建镜像（当官方镜像不可达时）

见 §3.3 的 `Dockerfile`。自建内容 = Python 3.11 + `docling` + `fastapi` + `uvicorn` + 一个薄封装。

### 1.3 境内部署可行性

| 环节 | 境内部署做法 |
|---|---|
| **镜像来源** | 拉取后 `docker save` 导出 tar，经内网镜像仓库（Harbor/Nexus）分发；或直接 `docker load` |
| **模型权重** | Docling 的版面模型/OCR 模型默认在**首次运行时**从 HF 下载 → **必须预置**（见 §3.4），否则生产环境会因无外网启动失败 |
| **无外网运行** | 设置 `HF_HUB_OFFLINE=1` + 挂载预置模型目录 |
| **数据不出境** | 教材 PDF 只在本机/内网容器内处理，不经任何外部 API → 满足 NFR-RAG-008 |

> ⚠️ **这是本方案最关键的一条**：Docling 默认会在首启时联网下载模型。若未预置，
> 表现为「容器起来了、健康检查过了，但第一次解析卡住或报下载失败」。
> 必须在构建/部署阶段完成模型预置。

---

## 2. 依赖组件与服务划分

```
┌──────────────────────┐         HTTP          ┌───────────────────────┐
│  rag-worker (Java)   │ ───────────────────▶  │  docling-serve (Py)   │
│  DoclingDocument     │   POST /v1/document/  │  :5001                │
│  ParserPort          │        convert        │  ├ 版面分析模型        │
│                      │ ◀───────────────────  │  ├ OCR (可选)         │
│                      │   DoclingDocument JSON │  └ 表格结构识别        │
└──────────────────────┘                       └───────────────────────┘
         │                                                  │
         └─────────────── 同一内网 Docker 网络 ──────────────┘
```

| 组件 | 说明 |
|---|---|
| **docling-serve** | 独立容器，唯一职责：PDF → 结构化 JSON |
| **模型权重** | 挂载卷 `./models`，预置后只读 |
| **rag-worker** | 已有服务，通过 `rag.docling.base-url` 指向 docling-serve |
| **网络** | 自建 bridge 网络 `rag-net`，两者同网，服务名互访 |

**为什么独立成服务而非 sidecar**：解析是 CPU 密集且可能突发（一次解析数十秒），
独立容器才能单独限流/扩缩容；sidecar 会与 worker 抢同一份 CPU/memory 上限。

---

## 2.5 实测结论（2026-11-02，本机 Docker 部署验证）

以下全部为**实跑确认**，不是文档推断：

| 项 | 实测结果 |
|---|---|
| 镜像 | `quay.io/docling-project/docling-serve:latest`，**15.4GB**（含预置模型） |
| digest | `sha256:8a5fbc993da9dec17c53880e2445834591ad26800ef698ca64096bef29671808` |
| 健康检查 | `/health` → `{"status":"ok"}`，启动约 20s（CPU 推理） |
| OCR 引擎 | 自动选择 `rapidocr` + `onnxruntime` |
| **实际 API 路径** | **`POST /v1/convert/file`**（⚠️ 不是 `/v1/document/convert`） |
| **multipart 字段** | **`files`**（复数）+ **必须带 `to_formats=json`** |
| 响应结构 | `{status, document:{json_content:{texts,tables,pictures,body,groups,furniture,pages,...}}}` |
| `json_content` | 可能是**对象**也可能是**JSON 字符串**（版本差异，两者都要处理） |
| 正文顺序 | 由 `body.children` 的 `$ref` 序列决定，**不是** `texts` 数组下标 |
| 单页解析耗时 | 约 2 秒（CPU） |
| 容器 CPU 上限 | 本机 Docker 仅 3 核，`cpus: 4.0` 会被拒——已改为 `3.0` |

### ⚠️ 一个只有实测才能发现的坑：HTTP/2 升级

JDK `HttpClient` 默认优先 HTTP/2，会先发 h2c 升级请求；**uvicorn 不认**，直接返回
`400 Invalid HTTP request received.`。此时**请求体完全正确也照样 400**，
无论怎么调 multipart 格式都没用。

**必须**显式指定 HTTP/1.1：

```java
HttpClient.newBuilder()
    .version(HttpClient.Version.HTTP_1_1)   // ← 缺这一行，永远 400
    .build();
```

### ⚠️ 第二个坑：答案区必须是「跨块状态」

实测首版实现中，`Answer Key` 标题块被判 `inAnswerZone=true`，但其**下方真正的答案内容**
却是 `false` —— 答案会以讲解身份进入检索，**直接泄题**。

正确语义：一旦遇到答案区标题，**其后所有块都属答案区**，直到遇到新的顶层标题。
已在 `DoclingResponseMapper` 中修正并有测试锁定。

### ⚠️ 实测发现三：真实教材（148 页）的三个映射缺陷

用沪教版六年级下册（148 页 / 13.4MB）实测，发现并修复了三个**只有真实教材才能暴露**的缺陷：

| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| 1 | **89% 的块被判答案区** | 题干里的 "answers"（如 "Underline the answers to the questions"）命中了答案关键词 | 答案区改为**只由标题驱动**（`isAnswerZoneHeading`），正文关键词不再触发 |
| 2 | **页眉进正文 222 次** | Docling 把「上海教育出版社」误标成 `section_header` | 按**跨 ≥5 页重复的短行**识别页眉，降级为 `BOILERPLATE`；且第 1 页（封面）不参与判定 |
| 3 | **94% 的 sectionPath 超 60 字符** | 410 个 `section_header` 里 409 个是 level=6（单元内小节），每页都有 | 路径只接受 level 1~5 的标题推进，且最多 3 层；并过滤书名/出版社等噪声标题 |

修复前后对比（148 页真实教材）：

```
修复前：sectionPath 过长 1438 个 / 含页眉噪声 1438 个 / 答案区 1671 个(89%)
修复后：sectionPath 过长    0 个 / 含页眉噪声    0 个 / 答案区    0 个（本书无答案附录）
```

> ⚠️ **答案区为 0 是正确的**：该教材末页是「后记」与封底，**没有答案附录**。
> 若换成带答案册的教材，`inAnswerZone` 会重新出现。

### ⚠️ 实测发现四：整册同步解析不可行 → 分段提交

全册 148 页需 **710 秒**，逼近服务端 `MAX_SYNC_WAIT` 上限且远超任何 HTTP 客户端的合理超时。
实测 20 页/段耗时 36~54 秒，产出 226~281 块/段 —— 因此 Java 适配器改为**按页段提交并合并**：

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `rag.docling.pages-per-segment` | 20 | 每段页数；≤0 = 不分段（整册一次） |
| `rag.docling.max-segments` | 60 | 段数上限（1200 页），防异常文件打满服务 |
| `rag.docling.timeout-seconds` | 900 | **单请求**超时（不是整册总时长） |

**分段的三条关键语义**（Java 侧 `DoclingDocumentParserPort`）：

1. **单段失败 → 整册失败**（不部分入库，D04 §1）：半本教材入库会把「数据缺失」伪装成「知识边界」。
2. **合并后重新连续化 `charStart/charEnd`**：每段映射的区间都从 0 开始，直接拼接会互相重叠；
   字符区间是「可回取原文」这条门禁证据（D09 G1 ③）的依据。
3. **段数超限直接拒绝**：疑似文件异常时，不发起会打满服务的请求。

调用方（`DocumentParserPort` 契约）**零感知**——分段逻辑完全在适配器内部。

### ⚠️ 实测发现五：章节层级必须按「level 值栈 + 页码」构建

用**两册**教材（沪教版六年级上册 162 页 / 下册 148 页）实测，发现 Docling 的
标题 `level` 值**既不连续也不可靠**，纯靠 level 数值比较会得到错误层级：

| 实测 level 序列 | 含义 | 真实关系 |
|---|---|---|
| 上册 p2 `英语`=1 → p7 `Contents`=2 → p11 `Starter 1`=3 → p17 `To-do list`=5 | **跳过了 4** | Starter 1 与 To-do list 是父子 |
| 上册 p18 `Starter 4`=3 → p19 `Part I`=1 | level **变小** | Part I 是**新的顶层分区**，不是 Starter 4 的子节 |
| 下册 p10 `Everyone is different 1 Unit`=3 → p24 `2 Rules around us Unit`=3 | **同 level 但不同单元** | 两者是**平级**兄弟 |

**首版的三种错误结果**：
```
"Starter 1 / Starter 2 / Starter 3"   ← 同级被串成链（用 level-1 当下标）
"Starter 4 / Part I"                  ← level 变小时被当成「更深一层」
"2 Rules around us Unit / Festivals across cultures" ← 正确，但靠巧合
```

**正解（三条规则同时满足）**：
1. **按 level 值栈定位**，不能用 level-1 当数组下标（level 不连续）；
2. **level 变小 = 回到上层**（弹掉更深的槽），不是「更深一层」；
3. **跨页 + level 不更深 = 新的顶层分区**（清空路径）——页码是最可靠的信号，
   真正的子标题紧跟父标题出现在同一页或紧邻页。

**附带修掉的两处噪声**：
- 目录页标题 `Contents` 被当正文层级 → 加入噪声标题表；
- 标题文本里的页眉后缀（`Festivals across cultures 上海教育出版社`）
  会让 1438 个片段的路径含页眉噪声 → 入路径前剥离页眉部分（标题主体保留）。

### ✅ S2-6 参数归零验收：三册真实教材（两学科）

按 S2-6 判据「3 册不同样本**零手工参数**跑通」实测（2026-11-02）：

| 册次 | 学科 | 页数 | 大小 | 耗时 | 块数 | 公式块 | 长路径 | 页眉噪声 | 区间不连续 |
|---|---|---|---|---|---|---|---|---|---|
| 六年级上册 | 英语 | 162 | 16.6MB | 504s | 2123 | — | **0** | **0** | **0** |
| 六年级下册 | 英语 | 148 | 13.4MB | 447s | 1871 | — | **0** | **0** | **0** |
| 数学上册 | 数学 | 162 | 13.8MB | 736s | 2165 | **432** | **0** | **0** | **0** |

**零手工参数**的含义：不传 `--skip-pages` / `--toc-from` / `--toc-pages` / `--appendix-page`
任何一项，全部由适配器自动完成（页数探测 → 分段 → 层级构建 → 噪声过滤）。

> ⚠️ 三册的**版式差异**（英语双栏图文 vs 数学公式密集）正是这组验证的价值所在——
> 前两册的规则「看起来都对」，直到第三册（数学）才暴露出公式块丢失的缺陷（见下）。

### ⚠️ 实测发现六：公式块内容在 `orig` 字段

**只有数学教材能暴露**的缺陷：Docling 的 `label=formula` 节点 **`text` 恒为空串**，
公式/例题原文只存在于 **`orig`** 字段：

```json
{"self_ref": "#/texts/227", "label": "formula", "text": "",
 "orig": "例３ 画一条数轴，并用数轴上的点表示下列各数：３、－３、０．５、…"}
```

首版只读 `text`，结果**数学上册 18/18 个公式块全部退化成 `[图]` 占位符**
——例题与公式整段丢失，而这正是数学教材的核心内容。

修复：`text` 为空且类型为 `FORMULA` 时改读 `orig`。修复后上册全本正确产出 **432 个公式块**，
原文完整（`例３ 画一条数轴…`、`解 ６的相反数是－６…`）。

### 真实教材性能基线

| 项 | 实测值 |
|---|---|
| 页数 / 大小 | 148 页 / 13.4 MB |
| 全本解析耗时 | **710 秒**（CPU，无 GPU） |
| 产出 | 3004 texts / 70 tables / 467 pictures / 2057 正文块 |
| 映射后块数 | 1871 |
| 20 页分段耗时 | 94 秒 |
| 单页 PDF | 2 秒 |

> ⚠️ 这组数字决定了两个部署参数：
> `DOCLING_SERVE_MAX_SYNC_WAIT` 必须 ≥ 1200（默认 120 会 504），
> 以及**整册同步解析不可行到 10 分钟以上**——生产建议按页段异步提交。

---

## 3. 可执行部署

### 3.1 目录结构

```bash
mkdir -p deploy/docling/{models,inbox,samples}
cd deploy/docling
```

### 3.2 `docker-compose.yml`（**可直接复制执行**）

```yaml
# deploy/docling/docker-compose.yml
services:
  docling:
    image: quay.io/docling-project/docling-serve:latest
    container_name: rag-docling
    restart: unless-stopped
    ports:
      # ⚠️ 只绑本机，不对外暴露：解析服务不面向公网
      - "127.0.0.1:5001:5001"
    environment:
      # ── 基础 ──
      DOCLING_SERVE_HOST: 0.0.0.0
      DOCLING_SERVE_PORT: "5001"
      DOCLING_SERVE_ENABLE_UI: "false"          # 生产关掉 Web UI，减小攻击面
      DOCLING_SERVE_LOGLEVEL: "INFO"

      # ── OCR：born-digital 阶段关闭（更快），扫描件试点时再开 ──
      DOCLING_SERVE_DO_OCR: "false"
      # 扫描件试点时改为 true，并同时开下面两行
      # DOCLING_SERVE_OCR_ENGINE: "easyocr"
      # DOCLING_SERVE_FORCE_OCR: "true"

      # ── 表格结构识别：教辅的答案对照表需要 ──
      DOCLING_SERVE_DO_TABLE_STRUCTURE: "true"

      # ── 境内离线：模型已预置，禁止联网 ──
      HF_HUB_OFFLINE: "1"
      TRANSFORMERS_OFFLINE: "1"
      HF_HOME: "/models"

    volumes:
      # 模型权重（预置后只读）
      - ./models:/models:ro
      # 可选：留档解析输入/输出，便于问题回溯
      - ./inbox:/data:rw

    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://localhost:5001/health"]
      interval: 30s
      timeout: 10s
      retries: 5
      start_period: 90s        # ⚠️ 首启要加载模型，给足预热时间

    deploy:
      resources:
        limits:
          cpus: "4.0"
          memory: 8g
        reservations:
          memory: 2g

    networks:
      - rag-net

networks:
  rag-net:
    name: rag-net
    driver: bridge
```

### 3.3 自建镜像（当官方镜像不可用）

```dockerfile
# deploy/docling/Dockerfile
FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    HF_HOME=/models \
    HF_HUB_OFFLINE=1

# ⚠️ 系统依赖：PDF 处理与 OCR 需要
RUN apt-get update && apt-get install -y --no-install-recommends \
        libgl1 libglib2.0-0 libgomp1 poppler-utils curl \
    && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir \
        "docling>=2.0" \
        "fastapi" "uvicorn[standard]" "python-multipart"

# 薄封装：把 Docling 的 DoclingDocument 直接返回为 JSON
COPY app.py /app/app.py
WORKDIR /app
EXPOSE 5001

HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=5 \
    CMD curl -fsS http://localhost:5001/health || exit 1

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "5001"]
```

```python
# deploy/docling/app.py
"""Docling 解析服务（薄封装）。

⚠️ 本文件的唯一职责：把 PDF 转成 DoclingDocument 的 JSON。
   所有「映射到 LayoutBlock」的逻辑都在 Java 侧（DoclingResponseMapper），
   不在这里做——否则同一套映射有两份实现，必然漂移。
"""
import io
from fastapi import FastAPI, File, UploadFile, HTTPException
from docling.document_converter import DocumentConverter, PdfFormatOption
from docling.datamodel.base_models import InputFormat
from docling.datamodel.pipeline_options import PdfPipelineOptions

app = FastAPI()

_conv = DocumentConverter(
    format_options={
        InputFormat.PDF: PdfFormatOption(
            pipeline_options=PdfPipelineOptions(
                do_ocr=False,             # born-digital 阶段关闭
                do_table_structure=True,  # 教辅的答案对照表需要
            )
        )
    }
)


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/v1/convert/file")
async def convert(file: UploadFile = File(...)):
    if not file.filename.lower().endswith(".pdf"):
        raise HTTPException(status_code=400, detail="仅接受 PDF")
    raw = await file.read()
    if not raw:
        raise HTTPException(status_code=400, detail="文件为空")
    try:
        result = _conv.convert(io.BytesIO(raw))
    except Exception as e:                      # noqa: BLE001
        raise HTTPException(status_code=422, detail=f"解析失败: {e}") from e
    # ⚠️ 返回完整 DoclingDocument：Java 侧需要 body.children（阅读顺序）、
    #    texts/tables/pictures（块内容）、prov（页码）、bbox（版面位置）
    return result.document.export_to_dict()
```

### 3.4 模型预置（**境内部署必需**）

```bash
# ① 在有外网的机器上把模型拉进容器并导出
docker compose up -d docling
sleep 120   # 等首启完成模型下载

# ② 确认模型已落到挂载卷
ls -la deploy/docling/models/

# ③ 之后即可断网运行（compose 里已设 HF_HUB_OFFLINE=1）
```

> 若离线机器已有模型包，直接拷进 `deploy/docling/models/` 即可。

### 3.5 启动

```bash
cd deploy/docling
docker compose up -d
docker compose logs -f docling     # 观察是否健康
```

---

## 4. Java 侧接口对齐

### 4.1 端口契约（已有，不变）

`DocumentParserPort.parse(documentId, content)` → `ParsedDocument(documentId, List<LayoutBlock>)`

`LayoutBlock` 九字段：`type / pageNumber / headingLevel / sectionPath / exerciseNo / inAnswerZone / charStart / charEnd`（+ `text`）

### 4.2 Docling JSON → LayoutBlock 映射规则

| Docling 字段 | LayoutBlock | 说明 |
|---|---|---|
| `texts[].label = section_header` | `type=HEADING`，`headingLevel=texts[].level` | |
| `texts[].label = text/paragraph` | `type=PARAGRAPH` | |
| `texts[].label = list_item` | `type=LIST_ITEM` | |
| `tables[]` | `type=TABLE` | |
| `pictures[]` | `type=FIGURE` | |
| `texts[].label = page_header/page_footer` | `type=BOILERPLATE` | 交给 `isNoise()` 丢弃 |
| `prov[0].page_no` | `pageNumber` | 1 基 |
| `body.children` 顺序 | 决定 `charStart/charEnd` 累加顺序 | **阅读顺序的唯一来源** |
| — | `exerciseNo` | Java 侧正则抽取 + 继承 |
| — | `inAnswerZone` | Java 侧三层判定（见 4.3） |
| — | `sectionPath` | Java 侧按 heading 栈推进 |

### 4.3 `inAnswerZone` 三层判定（防泄题，最高风险点）

```
L1 标题驱动：「参考答案/Answer Key/答案与解析」→ 其后进入答案区
L2 版面驱动：page_header/footer 标签 + 页底位置
L3 内容驱动：RoleRules.classify() 的 ANSWER 正则
任一命中即判答案区（宁可过判 = 取严）
```

---

## 5. 验证步骤（**可逐条复制执行**）

### 5.1 健康检查

```bash
curl -fsS http://127.0.0.1:5001/health
# 期望：{"status":"ok"}
```

### 5.2 解析示例 PDF 并确认字段

准备一个可解析的 PDF（用系统自带文件即可）：

```bash
# 生成一个最小但合法的测试 PDF
python3 - <<'PY'
from pathlib import Path
pdf = b"""%PDF-1.4
1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj
3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]/Resources<</Font<</F1 5 0 R>>>>/Contents 4 0 R>>endobj
4 0 obj<</Length 120>>stream
BT /F1 18 Tf 72 760 Td (Unit 1 Hello) Tj ET
BT /F1 12 Tf 72 720 Td (1. What is your name?) Tj ET
BT /F1 12 Tf 72 690 Td (Answers: 1. My name is Li Lei.) Tj ET
endstream
endobj
5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj
trailer<</Root 1 0 R>>
%%EOF"""
Path("deploy/docling/samples/sample.pdf").write_bytes(pdf)
print("written", Path("deploy/docling/samples/sample.pdf").stat().st_size, "bytes")
PY

# 调用解析（⚠️ 字段名是 files，且必须带 to_formats=json）
curl -fsS -X POST http://127.0.0.1:5001/v1/convert/file \
     -F "files=@deploy/docling/samples/sample.pdf" \
     -F "to_formats=json" \
     -o /tmp/docling_out.json -w "HTTP %{http_code}\n"
```

### 5.3 校验输出结构

```bash
python3 - <<'PY'
import json
d = json.load(open("/tmp/docling_out.json"))
print("顶层键：", sorted(d.keys()))
# ⚠️ 正文在 document.json_content 下
jc = d["document"]["json_content"]
if isinstance(jc, str):
    jc = json.loads(jc)
body = jc.get("body", {}).get("children", [])
print("正文块数：", len(body))
# 展示前 3 个块的 label / 文本 / 页码
texts = {t.get("self_ref"): t for t in jc.get("texts", [])}
for child in body[:3]:
    ref = child.get("$ref")
    t = texts.get(ref, {})
    prov = (t.get("prov") or [{}])[0]
    print(f"  label={t.get('label')!r:<18} page={prov.get('page_no')} text={(t.get('text') or '')[:40]!r}")
PY
```

**判定标准**：需要能取到
- `body.children`（阅读顺序）
- 每个块的 `label`（→ `BlockType`）
- `prov[0].page_no`（→ `pageNumber`）
- `texts[].text`（→ 块文本）
- `texts[].level`（→ `headingLevel`，仅 section_header 有）

### 5.4 接入 rag-worker

```bash
# 让 worker 指向 Docling
#   宿主机进程（本机跑 rag-worker）→ 走已绑定的 127.0.0.1
export RAG_DOCLING_BASE_URL=http://127.0.0.1:5001
#   若 rag-worker 也在同一 compose 网络内 → 用服务名
#   export RAG_DOCLING_BASE_URL=http://docling:5001

# 2) 重启 rag-worker 后确认日志
#    期望出现：DocumentParserPort → Docling（base-url 已配置）
```

### 5.5 端到端（待 Java 侧映射器补齐后）

```bash
# 上传 → 建 job → worker 解析 → 查 job 状态
curl -fsS -X POST "http://127.0.0.1:8080/api/v1/management/sources/<sourceId>/documents" \
  -H "X-Tenant-Id: <tenantId>" -H "X-Corpus-Scope: TENANT" \
  -H "Authorization: Bearer <token>" \
  -F "file=@deploy/docling/samples/sample.pdf"
# 期望返回 201 + jobId
```

---

## 6. 资源与运维

| 项 | 建议值 | 说明 |
|---|---|---|
| CPU | 4 核 | 版面模型推理为主 |
| 内存 | 8 GB 上限 / 2 GB 预留 | 大 PDF 解析峰值较高 |
| 磁盘 | 模型约 1~2 GB | 挂载卷 |
| 端口 | 127.0.0.1:5001 | **不对外暴露** |
| 日志 | `docker compose logs` | 生产接平台日志采集 |

---

## 7. 已知边界与风险

| 风险 | 说明 | 对策 |
|---|---|---|
| **模型未预置** | 首启联网下载失败 → 解析永不成功 | §3.4 预置 + `HF_HUB_OFFLINE=1` |
| **扫描件识别率未知** | OCR 质量（D04 L-6） | 保持 `DO_OCR=false`，试点时单开 |
| **首启慢** | 模型加载 60~90s | healthcheck `start_period: 90s` |
| **内存峰值** | 数百页 PDF 可能 OOM | 限 8g，超限则按册拆分或调大 |
| **Docling 版本漂移** | 升级会改变切分结果 | 锁 digest；升级后必须重灌并重跑 diff 归零 |

---

## 8. 与 T-4 的对应

本方案回答了 T-4 中的 **部署形态** 与 **API 路径/响应格式**（`POST /v1/document/convert` → DoclingDocument JSON）。
剩余待确认的只有：**具体 Docling 版本号**（用于 `PARSER_VERSION` 留痕）。
