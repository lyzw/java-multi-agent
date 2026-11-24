package cn.studykid.rag.premise.ir;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.premise.model.PremiseBlock;
import cn.studykid.rag.premise.model.PremiseDocument;
import cn.studykid.rag.premise.normalize.FormulaAnnotation;

/**
 * P1-3（RAG-D28）：把 {@link PremiseDocument} 渲染为 D27 §4.2 中间产物（Markdown + JSON）。
 *
 * <p><b>⚠️ 设计纪律</b>：
 * <ol>
 *   <li><b>不伪造数据</b>：bbox 缺失输出 {@code null}（不是 {@code [0,0,0,0]}），
 *       confidence 未知输出 {@code null}（不是 0）——真实值才写，未知留白；</li>
 *   <li><b>人读/机读同源</b>：Markdown 与 JSON 由同一次遍历产出，块顺序一致；</li>
 *   <li><b>噪声不进正文</b>：BOILERPLATE（页眉页脚/页码）不进 Markdown——
 *       与切分器噪声口径一致，避免「第 12 页」污染几十个片段；</li>
 *   <li><b>可回取原文</b>：JSON 的 {@code charStart/charEnd} 是可定位回原文的权威区间，
 *       由 mapper 的阅读顺序累加得出；本类不改这些偏移，只输出。</li>
 * </ol>
 *
 * <p>⚠️ 零框架依赖（rag-premise 只依赖 core/ports/jspecify），JSON 手写严格转义。
 */
public final class PremiseIrRenderer {

    /** 渲染为中间产物三件套。 */
    public @NonNull IntermediateRepresentation render(@NonNull PremiseDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("document 不能为空");
        }
        List<PremiseBlock> blocks = document.blocks();
        StringBuilder md = new StringBuilder();
        StringBuilder json = new StringBuilder();
        List<Integer> readingOrder = new ArrayList<>(blocks.size());

        json.append("{\n  \"documentId\": ").append(quote(document.documentId()));
        json.append(",\n  \"parser\": {\"name\": ").append(quote(document.trace().backendId()));
        json.append(", \"version\": ").append(quote(document.trace().backendVersion()));
        json.append(", \"route\": ").append(quote(document.trace().routeReason())).append("},");
        json.append("\n  \"blocks\": [");

        int emitted = 0;
        for (int i = 0; i < blocks.size(); i++) {
            PremiseBlock b = blocks.get(i);
            if (b.type() == BlockType.BOILERPLATE) {
                continue; // 噪声不进中间产物正文
            }
            readingOrder.add(i);
            appendMarkdown(md, b);

            if (emitted++ > 0) {
                json.append(',');
            }
            json.append("\n    {\"readingOrder\": ").append(emitted - 1);
            json.append(", \"sourceIndex\": ").append(i);
            json.append(", \"pageNumber\": ").append(b.pageNumber());
            json.append(", \"type\": ").append(quote(b.type().name()));
            json.append(", \"text\": ").append(quote(b.text()));
            json.append(", \"bbox\": ").append(bboxJson(b));
            json.append(", \"charStart\": ").append(b.charStart());
            json.append(", \"charEnd\": ").append(b.charEnd());
            // ⚠️ 未知置信度输出 null，不伪造 0（可回取原文/质量判定会误用 0）
            json.append(", \"confidence\": ").append(confidenceJson(b));
            json.append(", \"sectionPath\": ").append(quote(b.sectionPath()));
            json.append(", \"exerciseNo\": ").append(exerciseJson(b));
            json.append(", \"inAnswerZone\": ").append(b.inAnswerZone());
            json.append(", \"formula\": ").append(formulaJson(b));
            json.append('}');
        }
        json.append("\n  ]\n}");
        return new IntermediateRepresentation(md.toString(), json.toString(),
                readingOrder, blocks);
    }

    /** 可回取原文：按字符区间从「拼接正文视图」切出每个块文本。 */
    public @NonNull List<String> retrieveOriginal(@NonNull IntermediateRepresentation ir) {
        if (ir == null) {
            throw new IllegalArgumentException("ir 不能为空");
        }
        // 正文视图 = 所有块 text 依阅读顺序拼接（与 mapper 的 charCursor 累加同口径）。
        StringBuilder full = new StringBuilder();
        for (PremiseBlock b : ir.blocks()) {
            full.append(b.text());
        }
        String corpus = full.toString();
        List<String> out = new ArrayList<>(ir.blocks().size());
        for (PremiseBlock b : ir.blocks()) {
            int start = b.charStart();
            int end = b.charEnd();
            if (start < 0 || end > corpus.length() || start > end) {
                // ⛔ 区间越界不可用空串冒充——抛错让「可回取原文」门禁转红。
                throw new IllegalStateException("字符区间越界，无法回取原文：documentId 块["
                        + b.pageNumber() + "] charStart=" + start + " charEnd=" + end
                        + " corpusLen=" + corpus.length());
            }
            out.add(corpus.substring(start, end));
        }
        return out;
    }

    private static void appendMarkdown(StringBuilder md, PremiseBlock b) {
        switch (b.type()) {
            case HEADING -> {
                int level = Math.max(1, Math.min(6, b.headingLevel()));
                md.append("#".repeat(level)).append(' ').append(b.text()).append("\n\n");
            }
            case FORMULA -> {
                md.append("$$").append(b.text()).append("$$");
                // P1-6：命中受控词典时追加自然语言描述（语义双写，D27 §4.4）。
                // ⚠️ 未命中不追加——禁把无依据描述写进正文/向量（DEC-P3）。
                if (b.hasFormulaAnnotation() && b.formulaAnnotation().hasNaturalLanguage()) {
                    md.append('\n').append(b.formulaAnnotation().naturalLanguage());
                }
                md.append("\n\n");
            }
            case LIST_ITEM -> md.append("- ").append(b.text()).append('\n');
            case TABLE, FIGURE -> md.append(b.text()).append("\n\n"); // 保留占位，详细结构在 JSON
            default -> md.append(b.text()).append("\n\n");
        }
    }

    private static String bboxJson(PremiseBlock b) {
        if (!b.hasBbox()) {
            return "null"; // ⛔ 不伪造空框
        }
        double[] box = b.pageBbox();
        return "[" + box[0] + "," + box[1] + "," + box[2] + "," + box[3] + "]";
    }

    private static String confidenceJson(PremiseBlock b) {
        return b.hasConfidence() ? Double.toString(b.confidence()) : "null";
    }

    private static String exerciseJson(PremiseBlock b) {
        String no = b.exerciseNo();
        return no == null ? "null" : quote(no);
    }

    /**
     * P1-6：公式语义标注 JSON。非公式块 / 未注解输出 {@code null}（不伪造空对象）。
     *
     * <p>⚠️ 自然语言未命中时为 JSON null，不写空串也不写猜测值——
     * 让「待抽检」状态在中间产物里一眼可见（DEC-P3 诚实标注）。
     */
    private static String formulaJson(PremiseBlock b) {
        if (!b.hasFormulaAnnotation()) {
            return "null";
        }
        FormulaAnnotation f = b.formulaAnnotation();
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"latex\": ").append(quote(f.latex()));
        sb.append(", \"naturalLanguage\": ")
                .append(f.hasNaturalLanguage() ? quote(f.naturalLanguage()) : "null");
        sb.append(", \"catalogueHit\": ").append(f.catalogueHit());
        sb.append(", \"needsReview\": ").append(f.needsReview());
        sb.append(", \"symbols\": {");
        boolean first = true;
        for (var e : f.symbols().entrySet()) {
            if (!first) {
                sb.append(',');
            }
            sb.append(quote(e.getKey())).append(": ").append(quote(e.getValue()));
            first = false;
        }
        sb.append("}}");
        return sb.toString();
    }

    /** 严格 JSON 字符串转义（控制符/引号/反斜杠），防止正文中的特殊字符破坏结构。 */
    private static String quote(String s) {
        StringBuilder out = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
