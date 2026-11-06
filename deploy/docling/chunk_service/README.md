# Docling 分块桥（RAG-D26 §2.4 方案 A）

docling-serve 无 chunk 端点；本服务用官方 HybridChunker 对「解析阶段留存的
DoclingDocument JSON」分块，返回带 `item_refs`（角色回填锚点）与 `char_spans`
（可回取原文）的 chunk 序列。

## 启动

```bash
# venv 已含 fastapi/uvicorn；docling-core[chunking] 需一次安装
source .venv/bin/activate
python -m pip install 'docling-core[chunking]'   # 已装过可跳过

# ⚠️ HybridChunker 默认 tokenizer（all-MiniLM-L6-v2）首次运行要从 HF 下载，
#    国内环境必须走镜像；下载一次后进本地缓存（实测已落盘：
#    ~/.cache/huggingface/hub/models--sentence-transformers--all-MiniLM-L6-v2），
#    后续启动可离线，无需再设 HF_ENDPOINT。
export HF_ENDPOINT=https://hf-mirror.com

cd deploy/docling/chunk_service
python -m uvicorn chunk_service:app --host 127.0.0.1 --port 5002
```

## 验证

```bash
curl -s http://127.0.0.1:5002/v1/health
# {"status":"ok"}
```

实测（2026-11-06，docling-core 2.106.0，真实 162 页英语教材）：
6.4 秒 → 444 chunks，item_refs 覆盖 100%，char_spans 覆盖 100%。

## Java 侧接入

`rag.premise.chunk-policy=hybrid` + `rag.premise.enabled=true`
（端点默认 `http://127.0.0.1:5002`）。桥不可用时 job 显式失败，
**绝不回退** heading（策略是 chunk_policy 留痕事实）。
