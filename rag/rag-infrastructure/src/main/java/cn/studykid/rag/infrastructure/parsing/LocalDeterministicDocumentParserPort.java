package cn.studykid.rag.infrastructure.parsing;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.DocumentParserPort;
import cn.studykid.rag.ports.LayoutBlock;
import cn.studykid.rag.ports.ParsedDocument;

/**
 * 本地确定性解析占位实现（RAG-D02 §3.4 / RAG-D04 §3 · G1 占位）。
 *
 * <p><b>⚠️ 这不是 Docling，也不是生产解析器。</b>
 * 解析质量是教辅 RAG 的第一瓶颈；本实现仅做<b>朴素行级切分 +
 * 极简答案区/标题启发式</b>，目的是让 {@code IngestionPipeline}
 * 的 G1 垂直切片在<b>无外部依赖</b>下可端到端跑通（与
 * {@code LocalDeterministicEmbeddingPort} 的占位定位一致）。
 *
 * <p><b>已知缺陷（占位实现，不得用于生产）</b>：
 * <ul>
 *   <li>不解析 PDF/版面树——把 {@code byte[]} 直接按 UTF-8 当纯文本处理；</li>
 *   <li>不识别表格/公式/图注的真实边界（一律归为段落）；</li>
 *   <li>答案区判定是关键词启发式（含「答案/解/解析/解答」即判为答案区），
 *       误判代价是<b>泄题</b>，生产须由版面解析器给出 {@code inAnswerZone}；</li>
 *   <li>无章节层级识别（sectionPath 恒为空串）。</li>
 * </ul>
 *
 * <p><b>推进 G1 的真实路径</b>（仍受 <b>A-008 硬阻断</b>「首期不做 RAG」约束，
 * 且需引入 Docling 依赖）：把本类替换为版面感知解析器，使其产出
 * 带 {@code 标题层级 / 题号 / 答案区 / 表格公式图注} 的结构化 {@link LayoutBlock}。
 */
public class LocalDeterministicDocumentParserPort implements DocumentParserPort {

    /** 占位实现不重建物理页码，恒置 1（真实解析器须给出真实页码）。 */
    private static final int PLACEHOLDER_PAGE = 1;

    /**
     * 标题行启发式：行首「第…章/节/课/目/部/分」。
     *
     * <p>⚠️ 必须用 {@link java.util.regex.Matcher#find()} 而非
     * {@code String.matches()}：后者要求<b>整串匹配</b>，
     * 而标题行后面还有正文（如「第1章 一次函数」之后有「 一次函数」），
     * 用 matches() 会永远匹配不上，标题启发式形同虚设。
     */
    private static final java.util.regex.Pattern HEADING_PATTERN =
            java.util.regex.Pattern.compile("^第.{1,6}[章节节目部分]");

    @Override
    public @NonNull ParsedDocument parse(@NonNull String documentId, @NonNull byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        List<LayoutBlock> blocks = new ArrayList<>();
        int offset = 0;
        for (String rawLine : text.split("\n", -1)) {
            String line = rawLine.replace("\r", "");
            int start = offset;
            int end = offset + line.length();
            offset = end + 1; // +1 抵消被 split 吃掉的换行符
            if (line.isBlank()) {
                continue; // 空行仅作块分隔，不产出噪声块
            }
            BlockType type = classify(line);
            boolean answerZone = type == BlockType.ANSWER || isAnswerZone(line);
            blocks.add(new LayoutBlock(
                    type,
                    line.trim(),
                    PLACEHOLDER_PAGE,
                    type.isHeading() ? headingLevel(line) : 0,
                    "",          // 占位：无章节路径识别
                    null,        // 占位：无题号识别
                    answerZone,
                    start,
                    end));
        }
        return new ParsedDocument(documentId, blocks);
    }

    /**
     * 朴素块类型判定。
     *
     * <p>⚠️ 仅看关键词/前缀，不依赖版面模型；标题识别为
     * 「含『第…章/节/课/部分』或 Markdown 标题前缀」。
     */
    private static BlockType classify(@NonNull String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("#") || HEADING_PATTERN.matcher(trimmed).find()) {
            return BlockType.HEADING;
        }
        if (isAnswerZone(trimmed)) {
            // 行级答案区（如「答案：…」「解：…」）直接标为 ANSWER，
            // 让 IngestionPipeline 取严判为答案类角色。
            return BlockType.ANSWER;
        }
        return BlockType.PARAGRAPH;
    }

    /** 标题层级启发式：章=1，节=2，课/部分=3，其余=1。 */
    private static int headingLevel(@NonNull String line) {
        if (line.contains("章")) {
            return 1;
        }
        if (line.contains("节")) {
            return 2;
        }
        return 3;
    }

    /** 答案区关键词启发式（占位，生产须由版面解析器断言）。 */
    private static boolean isAnswerZone(@NonNull String line) {
        return line.contains("答案")
                || line.contains("解：") || line.contains("解答")
                || line.contains("解析") || line.contains("参考答案")
                || line.startsWith("解 ");
    }
}
