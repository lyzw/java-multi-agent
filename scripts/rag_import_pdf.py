#!/usr/bin/env python3
"""把一本 PDF 教材抽成 rag_source / rag_edition / rag_document / rag_chunk 的导入 SQL。

RAG 服务没有文件上传通道（`object_ref` 只存引用，正文不入库），
所以真实教材入库走这条离线路径：本地抽取 → 生成 SQL → 由 DBA 执行 →
再用管理端接口签校验 → `POST /reindex` 补向量。

⚠️ 本脚本**只生成 SQL，不连库、不写库**，也不设置任何校验状态：
   产出的来源/载体一律 PENDING / UNVERIFIED / 不可检索，
   「谁签的、凭什么签」必须由人通过 `/sources/{id}/verification` 落库。

用法：
    python3 scripts/rag_import_pdf.py \
        --pdf "/path/七年级英语上册.pdf" \
        --title "沪教版（五·四学制）英语六年级上册" \
        --isbn 978-7-5720-2811-3 --publisher 沪教版 \
        --subject 英语 --grade 六年级 --term 上册 --revision 2024 \
        --skip-pages 10 --toc-from 7 --toc-pages 4 --appendix-page 105 \
        --out /tmp/hujiao-6a.sql

页序与单元都从 PDF 自身标定（每页首行的印刷页码 + 目录里的 `Title (p. N)`），
只有「正文从第几页起、附录从第几页起」需要按册给一次。

幂等：UUID 由 (isbn, page) 经 uuid5 派生，重复执行同参数得到同 ID；
生成的 SQL 全量 `ON CONFLICT DO NOTHING`，可反复跑。
"""

from __future__ import annotations

import argparse
import hashlib
import re
import uuid
from collections import Counter
from pathlib import Path

from pypdf import PdfReader

NS = uuid.UUID("6f1c9e20-7a3b-4c58-9d2e-4b7f0a1c2d3e")

# 角色判定用的信号词（英语教材体例）。
EXERCISE_RE = re.compile(
    r"\b(Ask|Write|Complete|Choose|Fill|Tick|Listen|Match|Read and|Look and|Talk|Practise|Practice)\b"
    r"|练习|作业|想一想|试一试"
)
EXPLANATION_RE = re.compile(r"\b(Grammar Focus|Grammar|Structure|Function|Pronunciation)\b|语法|句型|语音|知识")
ANSWER_RE = re.compile(r"\bAnswers?( to| key)?\b|参考答案|答案")
APPENDIX_RE = re.compile(r"\bWord [Ll]ist|Vocabulary\b|词汇表|单词表|索引")
# 封面/版权/编者页：不是语料，混进来会污染召回（这类页每册位置不同，按内容判而非按页号）。
BOILERPLATE_RE = re.compile(
    r"主\s*\s*编|分册主编|责任编辑|特约审读|封面设计|版式设计|插图绘制"
    r"|出版|发行|印刷|定价|书\s*号|版权所有|统一书号")


def derive(key: str) -> uuid.UUID:
    return uuid.uuid5(NS, key)


def sql_str(value: str | None) -> str:
    if value is None:
        return "NULL"
    return "'" + value.replace("'", "''") + "'"


def normalize(raw: str) -> str:
    """压掉抽取噪声：全角空格、软换行、行内多空格。"""
    lines = []
    for ln in raw.splitlines():
        ln = ln.replace("\u3000", " ").replace("\u2003", " ").replace("\xad", "")
        ln = re.sub(r"\s+", " ", ln).strip()
        if ln:
            lines.append(ln)
    return "\n".join(lines)


def strip_boilerplate(pages: list[str]) -> list[str]:
    """剥掉跨页重复的页眉页脚（教材每页都印出版社名，不去掉会污染每个片段）。"""
    counter: Counter[str] = Counter()
    for text in pages:
        for ln in text.splitlines():
            if len(ln) <= 40:
                counter[ln] += 1
    threshold = max(3, len(pages) // 3)
    junk = {ln for ln, c in counter.items() if c >= threshold}

    cleaned = []
    for text in pages:
        kept = [ln for ln in text.splitlines() if ln not in junk and not re.fullmatch(r"\d{1,3}", ln)]
        cleaned.append("\n".join(kept))
    return cleaned


def classify(text: str) -> str:
    if APPENDIX_RE.search(text):
        return "APPENDIX"
    if ANSWER_RE.search(text):
        return "EXERCISE_ANSWER"
    if len(EXERCISE_RE.findall(text)) >= 3:
        return "EXERCISE"
    if EXPLANATION_RE.search(text):
        return "EXPLANATION"
    return "PAPER_BODY"


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for blk in iter(lambda: fh.read(1 << 20), b""):
            h.update(blk)
    return h.hexdigest()


def toc_units(pages: list[str], max_pages: int) -> list[tuple[int, str, int]]:
    """从目录页解析 (单元号, 单元名, 印刷起始页)。

    ⚠️ 抽取噪声会把 `(p. 26)` 拆成 `(p` / `. 26)` 两段、把单元名折行成
       `Green\nneighbourhood\n(p. 58)`，故先把目录页拼成整串再压平空白，
       否则按行匹配会漏掉一半单元。
    """
    blob = " ".join(pages[:max_pages])
    blob = re.sub(r"\(\s*p\.?\s*\.?\s*(\d{1,3})\s*\)", r"(p\1)", blob)
    blob = re.sub(r"\s+", " ", blob)
    pat = re.compile(r"(?:^|\s)(\d{1,2})\s+([A-Za-z][A-Za-z ,'\-]{3,60}?)\s*\(p(\d{1,3})\)")
    out, seen = [], set()
    for m in pat.finditer(blob):
        no, title, start = int(m.group(1)), re.sub(r"\s+", " ", m.group(2)).strip(), int(m.group(3))
        if no in seen or not (0 < no <= 12 and 1 <= start <= 400):
            continue
        seen.add(no)
        out.append((no, title, start))
    return sorted(out, key=lambda x: x[0])


def page_offset(pages: list[str]) -> int:
    """标定「PDF 序号 - 印刷页码」的常量差值。

    教材每页把印刷页码单独排在首行，取「PDF 序号 - 页码」的众数即可自标定，
    页面里孤立的练习题号只是个别噪声，不影响众数。
    """
    deltas: Counter[int] = Counter()
    for idx, text in enumerate(pages, start=1):
        first = text.split("\n", 1)[0]
        m = re.fullmatch(r"\s*(\d{1,3})\s*", first)
        if m:
            deltas[idx - int(m.group(1))] += 1
    if not deltas:
        raise SystemExit("抽不到任何印刷页码，无法标定页序偏移；请检查这本 PDF 的抽取质量")
    return deltas.most_common(1)[0][0]


def section_of(printed: int, units: list[tuple[int, str, int]],
               starter_label: str, appendix_start: int | None) -> str:
    if appendix_start is not None and printed >= appendix_start:
        return "附录"
    label = None
    for no, title, start in units:
        if printed >= start:
            label = "Unit %d %s" % (no, title)
    return label or starter_label


def build(args: argparse.Namespace) -> str:
    path = Path(args.pdf)
    file_hash = sha256_file(path)
    reader = PdfReader(path)
    raw_pages = [normalize(p.extract_text() or "") for p in reader.pages]
    offset = page_offset(raw_pages)
    pages = strip_boilerplate(raw_pages)
    # 目录窗口用未清洗的页面：单元号「1」「2」各自独占一行，清洗会把它们当页码噪声吃掉
    toc_window = raw_pages[args.toc_from - 1: args.toc_from - 1 + args.toc_pages]
    units = toc_units(toc_window, len(toc_window))

    source_id = derive(f"source|{args.isbn or args.title}")
    edition_id = derive(f"edition|{args.isbn or args.title}")
    document_id = derive(f"document|{args.isbn or args.title}")
    tenant = args.tenant_id

    chunks = []
    skipped = 0
    seen_pages: dict[int, int] = {}
    for idx, text in enumerate(pages, start=1):
        if idx <= args.skip_pages:
            continue
        pno = idx - offset
        body = text.strip()
        if len(body) < args.min_chars:
            continue
        head = body[:400]
        markers = {m.group(0) for m in BOILERPLATE_RE.finditer(head)}
        if len(markers) >= 2:
            skipped += 1
            continue
        flat = re.sub(r"\s+", " ", body)
        # 页序标定偶尔失准会撞页码，同页第 2 个片段起加序号，否则派生 UUID 重复会被静默丢弃
        part = seen_pages.get(pno, 0)
        seen_pages[pno] = part + 1
        chunks.append(
            {
                "id": derive(f"chunk|{args.isbn or args.title}|{pno}" + (f"#{part}" if part else "")),
                "page": pno,
                "section": section_of(pno, units, args.starter_label, args.appendix_page),
                "role": classify(flat),
                "text": flat,
                "hash": hashlib.sha256(flat.encode()).hexdigest(),
            }
        )

    w = []
    w.append("-- 由 scripts/rag_import_pdf.py 生成：%s（%d 页 → %d 片段；页序偏移 %d、目录解析出 %d 个单元，"
             "撞页 %d、丢弃编者页 %d）"
             % (path.name, len(pages), len(chunks), offset, len(units),
                sum(v - 1 for v in seen_pages.values() if v > 1), skipped))
    w.append("-- ⚠️ 一律 PENDING / UNVERIFIED / retrievable=false：校验判定必须走管理端接口，不在此文件里伪造。")
    w.append("\\set ON_ERROR_STOP on")
    w.append("BEGIN;")
    w.append("SET search_path TO rag, public;")
    w.append("SELECT set_config('app.tenant_id', %s, false);" % sql_str(tenant))

    w.append("""
INSERT INTO rag.rag_source
    (source_id, tenant_id, source_type, title, publisher, isbn,
     trust_tier, ver_status, ver_method, ver_valid_days, license_status,
     object_ref, content_hash, created_at, updated_at)
VALUES
    (%s, %s, %s, %s, %s, %s,
     'UNVERIFIED', 'PENDING', 'NONE', 365, 'PENDING',
     %s, %s, now(), now())
ON CONFLICT (source_id) DO NOTHING;"""
    % (sql_str(str(source_id)), sql_str(tenant), sql_str(args.source_type),
       sql_str(args.title), sql_str(args.publisher), sql_str(args.isbn),
       sql_str(str(path)), sql_str(file_hash)))

    w.append("""
INSERT INTO rag.rag_edition
    (edition_id, tenant_id, source_id, subject, grade, term, publisher,
     revision_code, curriculum_ref, created_at)
VALUES
    (%s, %s, %s, %s, %s, %s, %s,
     %s, %s, now())
ON CONFLICT (edition_id) DO NOTHING;"""
    % (sql_str(str(edition_id)), sql_str(tenant), sql_str(str(source_id)),
       sql_str(args.subject), sql_str(args.grade), sql_str(args.term),
       sql_str(args.publisher), sql_str(args.revision), sql_str(args.curriculum_ref)))

    w.append("""
INSERT INTO rag.rag_document
    (document_id, tenant_id, source_id, edition_id, carrier, title, page_count,
     object_ref, parse_status, parser_version, content_hash, created_at, updated_at,
     ver_status, retrievable, license_status)
VALUES
    (%s, %s, %s, %s, 'PDF', %s, %d,
     %s, 'CHUNKED', 'rag_import_pdf-1', %s, now(), now(),
     'PENDING', false, 'PENDING')
ON CONFLICT (document_id) DO NOTHING;"""
    % (sql_str(str(document_id)), sql_str(tenant), sql_str(str(source_id)),
       sql_str(str(edition_id)), sql_str(args.title), len(pages) - args.skip_pages,
       sql_str(str(path)), sql_str(file_hash)))

    if chunks:
        w.append("INSERT INTO rag.rag_chunk")
        w.append("    (chunk_id, tenant_id, document_id, edition_id, role, text, page_number,")
        w.append("     section_path, char_start, char_end, kp_ids, content_hash, created_at, scope)")
        w.append("VALUES")
        rows = []
        for c in chunks:
            rows.append(
                "    (%s, %s, %s, %s, %s::rag_chunk_role, %s, %d, %s, 0, %d,"
                " '{}'::uuid[], %s, now(), 'TENANT')"
                % (sql_str(str(c["id"])), sql_str(tenant), sql_str(str(document_id)),
                   sql_str(str(edition_id)), sql_str(c["role"]), sql_str(c["text"]),
                   c["page"], sql_str(c["section"]), len(c["text"]), sql_str(c["hash"]))
            )
        w.append(",\n".join(rows))
        w.append("ON CONFLICT (chunk_id) DO NOTHING;")

    w.append("COMMIT;")
    w.append("")
    dup_pages = sum(v - 1 for v in seen_pages.values() if v > 1)
    return ("\n".join(w), len(chunks), str(source_id), str(document_id),
            "页序偏移 %d / %d 个单元 / 撞页 %d / 丢弃编者页 %d"
            % (offset, len(units), dup_pages, skipped))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pdf", required=True)
    ap.add_argument("--title", required=True)
    ap.add_argument("--tenant-id", required=True)
    ap.add_argument("--isbn")
    ap.add_argument("--publisher", required=True)
    ap.add_argument("--subject", required=True)
    ap.add_argument("--grade", required=True)
    ap.add_argument("--term", required=True)
    ap.add_argument("--revision", default="2024")
    ap.add_argument("--source-type", default="TEXTBOOK")
    ap.add_argument("--curriculum-ref")
    ap.add_argument("--skip-pages", type=int, default=0, help="跳过前 N 页（封面/版权/编者/目录），从正文开始切分")
    ap.add_argument("--toc-from", type=int, default=1, help="目录所在窗口的起始页（PDF 序号，1 基）")
    ap.add_argument("--toc-pages", type=int, default=6, help="目录窗口宽度，用于解析单元起始页")
    ap.add_argument("--starter-label", default="Starter", help="首个单元起始页之前的段名（前置单元 / 致同学等）")
    ap.add_argument("--appendix-page", type=int, default=None, help="附录/单词表起始印刷页（达到即标附录）")
    ap.add_argument("--min-chars", type=int, default=60, help="清洗后不足此字数的页丢弃")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    sql, n_chunks, source_id, document_id, summary = build(args)
    Path(args.out).write_text(sql, encoding="utf-8")
    print("source_id=%s" % source_id)
    print("document_id=%s" % document_id)
    print("chunks=%d (%s) -> %s" % (n_chunks, summary, args.out))


if __name__ == "__main__":
    main()
