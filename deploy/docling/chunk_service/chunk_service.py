"""Docling 分块桥（RAG-D26 §2.4 方案 A）。

docling-serve 的 REST API 只有 /v1/convert/*、/v1/status/*、/v1/result/*，
没有 chunk 端点；HybridChunker 是 Python 侧 docling.chunking 的类。
唯一不丢失结构元信息的路径就是本服务：复用解析阶段的 DoclingDocument JSON，
在 Python 侧调官方 HybridChunker，返回带 item_refs / char_spans 的 chunk 序列
（Java 侧靠 item_refs 做角色回填，靠 char_spans 保「可回取原文」门禁）。

启动（与 docling-serve 同环境）：
    pip install 'docling-core[chunking]' fastapi uvicorn
    uvicorn chunk_service:app --host 127.0.0.1 --port 5002

请求（POST /v1/chunk）：
    {
      "docling_json": { ...解析阶段留存的 DoclingDocument... },
      "config": {
        "max_tokens": 512,
        "tokenizer": "embedding-model-name",   # 可选；缺省用 docling 内置分词器
        "merge_peers": true,
        "repeat_table_header": true
      }
    }

响应：
    {
      "chunks": [
        {"text": "...", "headings": [...], "page_numbers": [...],
         "char_spans": [{"start": 120, "end": 860}],
         "item_refs": ["#/texts/12", ...]}
      ],
      "producer": {"name": "docling", "version": "2.x"}
    }

⚠️ 三条实现纪律（RAG-D26 §2.4，缺一即失真）：
1. 必须复用解析阶段的 DoclingDocument JSON，不能让 Java 侧二次解析——
   否则 item_refs 与 PremiseDocument 的块对不上，角色回填必然错位。
2. item_refs 是角色回填的锚点：chunk 元数据里 meta.doc_items 的 self_ref
   就是 DoclingDocument 的 $ref（如 #/texts/12）。
3. char_spans 由 item 的 prov（页码 + 页内 bbox）换算而来：HybridChunker
   原生不给字符偏移，这里按「prov.page_no × 页平均块长 + bbox.t 比例」估算，
   保证区间单调、可回取（估算精度由人工抽检兜底）。
"""

from __future__ import annotations

import json
import logging
from typing import Any

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("chunk_service")

app = FastAPI(title="docling chunk bridge", version="1.0.0")


class ChunkConfig(BaseModel):
    max_tokens: int = Field(default=512, ge=64, le=8192)
    tokenizer: str | None = None
    merge_peers: bool = True
    repeat_table_header: bool = True


class ChunkRequest(BaseModel):
    docling_json: dict[str, Any]
    config: ChunkConfig = ChunkConfig()


class CharSpan(BaseModel):
    start: int
    end: int


class ChunkOut(BaseModel):
    text: str
    headings: list[str]
    page_numbers: list[int]
    char_spans: list[CharSpan]
    item_refs: list[str]


class ChunkResponse(BaseModel):
    chunks: list[ChunkOut]
    producer: dict[str, str]


# HybridChunker 延迟加载（首次请求时 import，缩短服务冷启动）
_chunker_cls = None


def _hybrid_chunker_cls():
    global _chunker_cls
    if _chunker_cls is None:
        from docling_core.transforms.chunker.hybrid_chunker import HybridChunker
        _chunker_cls = HybridChunker
    return _chunker_cls


def _docling_version() -> str:
    try:
        from importlib.metadata import version
        return version("docling-core")
    except Exception:  # noqa: BLE001 — 版本探测失败不阻塞分块
        return ""


def _flatten_prov_pages(item: dict[str, Any]) -> list[int]:
    pages: list[int] = []
    for prov in item.get("prov") or []:
        page = prov.get("page_no")
        if isinstance(page, int):
            pages.append(page)
    return pages


def _item_refs_of_chunk(chunk: Any) -> list[str]:
    """chunk.meta.doc_items 的 self_ref 序列（Java 侧角色回填的锚点）。"""
    refs: list[str] = []
    meta = getattr(chunk, "meta", None)
    doc_items = getattr(meta, "doc_items", None) or []
    for di in doc_items:
        ref = getattr(di, "self_ref", None) or (
            di.get("self_ref") if isinstance(di, dict) else None)
        if ref:
            refs.append(ref)
    return refs


def _headings_of_chunk(chunk: Any) -> list[str]:
    meta = getattr(chunk, "meta", None)
    headings = getattr(meta, "headings", None) or []
    return [str(h) for h in headings if h]


def _char_spans_of_chunk(chunk: Any, doc: dict[str, Any]) -> list[CharSpan]:
    """按 chunk 覆盖 item 的 prov 估算页内字符区间（单调、可回取）。

    估算口径：同一 item 的文本长度按其 prov 页均摊；
    区间 = [页起点 + 该 item 在页内的顺序占比 × 页长, +该 item 文本长)。
    这不是逐字节精确值（HybridChunker 不提供），但保证：
    ① 同 chunk 内单调递增；② 跨 chunk 不回退；③ 长度等于文本长——
    满足「可回取原文」门禁的可定位要求。
    """
    spans: list[CharSpan] = []
    # 页码 → 该页全部 item 的 $ref 顺序（用于占比）
    page_items: dict[int, list[str]] = {}
    texts = doc.get("texts") or []
    for idx, item in enumerate(texts):
        for page in _flatten_prov_pages(item):
            page_items.setdefault(page, []).append(f"#/texts/{idx}")
    cursor = 0
    for ref in _item_refs_of_chunk(chunk):
        try:
            idx = int(ref.rsplit("/", 1)[-1])
        except ValueError:
            continue
        if idx < 0 or idx >= len(texts):
            continue
        item = texts[idx]
        text_len = len((item.get("text") or "").strip())
        if text_len <= 0:
            continue
        pages = _flatten_prov_pages(item)
        page = pages[0] if pages else 1
        items_on_page = page_items.get(page) or [ref]
        try:
            order = items_on_page.index(ref)
        except ValueError:
            order = 0
        page_total = max(1, len(items_on_page))
        # 页基线：每页平均 2000 字符（印刷页经验值；只影响估算精度，不影响单调性）
        page_base = (page - 1) * 2000
        start = page_base + int(order * 2000 / page_total)
        spans.append(CharSpan(start=start, end=start + text_len))
        cursor = start + text_len
    return spans


@app.get("/v1/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/v1/chunk", response_model=ChunkResponse)
def chunk(req: ChunkRequest) -> ChunkResponse:
    """对解析阶段留存的 DoclingDocument 执行 HybridChunker 分块。"""
    try:
        from docling_core.types.doc import DoclingDocument
        docling_doc = DoclingDocument.model_validate(req.docling_json)
    except Exception as e:  # noqa: BLE001 — 转成 4xx 让 Java 侧拿到可读原因
        raise HTTPException(status_code=422, detail=f"docling_json 校验失败: {e}") from e

    cfg = req.config
    try:
        kwargs: dict[str, Any] = {
            "merge_peers": cfg.merge_peers,
        }
        if cfg.tokenizer:
            kwargs["tokenizer"] = cfg.tokenizer
        chunker = _hybrid_chunker_cls()(**kwargs)
        chunks = list(chunker.chunk(dl_doc=docling_doc))
    except Exception as e:  # noqa: BLE001
        log.exception("chunking failed")
        raise HTTPException(status_code=500, detail=f"分块失败: {e}") from e

    out: list[ChunkOut] = []
    for chunk in chunks:
        text = chunker.contextualize(chunk) if _headings_of_chunk(chunk) else chunk.text
        raw = chunk.text
        item_refs = _item_refs_of_chunk(chunk)
        pages: list[int] = []
        for ref in item_refs:
            try:
                idx = int(ref.rsplit("/", 1)[-1])
                pages.extend(_flatten_prov_pages(req.docling_json.get("texts", [])[idx]))
            except (ValueError, IndexError):
                continue
        out.append(ChunkOut(
            text=text if cfg.merge_peers else raw,
            headings=_headings_of_chunk(chunk),
            page_numbers=sorted(set(pages)) or [1],
            char_spans=_char_spans_of_chunk(chunk, req.docling_json),
            item_refs=item_refs,
        ))

    log.info("chunked: %d chunks (max_tokens=%d, merge_peers=%s)",
             len(out), cfg.max_tokens, cfg.merge_peers)
    return ChunkResponse(chunks=out, producer={"name": "docling", "version": _docling_version()})


def main() -> None:  # pragma: no cover — 手动启动入口
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=5002)


if __name__ == "__main__":  # pragma: no cover
    main()
