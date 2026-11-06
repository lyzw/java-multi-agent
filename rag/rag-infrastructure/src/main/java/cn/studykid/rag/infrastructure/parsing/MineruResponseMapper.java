package cn.studykid.rag.infrastructure.parsing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.LayoutBlock;

/**
 * MinerU MiddleJson → {@link LayoutBlock} 映射（RAG-D26 阶段 1.2）。
 *
 * <p><b>⚠️ 本类的映射规则来自实测响应（2026-11-06，MinerU 4.1.7），不是文档推断</b>：
 * <pre>
 * mineru-kit parse x.pdf -f middle_json → { "schema":"docvortex.middle", "schema_version":"2.0",
 *   "metadata": { "producer": {"name":"mineru","version":"4.1.7"}, ... },
 *   "extensions": { "mineru": {"tier":"flash","parse_mode":"ocr"} },
 *   "pages": [ { "page_idx": 0, "blocks": [ { "type","index","bbox","content","level?" } ] } ] }
 * </pre>
 *
 * <p><b>实测确认的关键事实</b>（实现据此写死，不得凭文档想象修改）：
 * <ol>
 *   <li><b>{@code page_idx} 从 0 起</b>：官方文档明示与 CLI 页范围（1 起）不同，
 *       映射时必须 +1——{@code LayoutBlock} 契约要求页码 ≥ 1
 *       （{@code rag_chunk.page_number >= 1} 约束会拒绝 0）；</li>
 *   <li><b>块类型共 29 种</b>（{@code mineru.types.BlockType}），实测教材出现 12 种：
 *       {@code text / doc_title / paragraph_title / header / footer / page_number /
 *       page_footnote / aside_text / image / table / chart / index}；</li>
 *   <li><b>标题层级只在标题块上携带</b>：{@code doc_title}（文档标题，level=1）与
 *       {@code paragraph_title}（段落标题，实测 level=2）；其余块无 {@code level} 字段；</li>
 *   <li><b>bbox 是 0-1 归一化坐标</b>（实测值 ≤ 1.0，与页面 pt 尺寸无关），
 *       仅用于几何参考，不参与切分；</li>
 *   <li><b>content 是数组</b>：每个元素 {@code {"type":"text","content":"…"}}；
 *       图片块的元素是 {@code image_body}（content 为空串）——
 *       文本提取时按元素类型过滤，只拼 {@code text} 元素。</li>
 * </ol>
 *
 * <p><b>⚠️ 四个 Java 侧计算的口径与 Docling 完全同源</b>
 * （{@link LayoutPredicates} 共享）：
 * {@code exerciseNo}（正则）、{@code inAnswerZone}（标题驱动状态机）、
 * {@code sectionPath}（标题栈）、{@code charStart/charEnd}（按阅读顺序累加）。
 * 「换解析器」不得改变任何判定语义。
 *
 * <p><b>⚠️ 阅读顺序</b>：MinerU 已做阅读顺序恢复，{@code pages[i].blocks[j]}
 * 的数组顺序即权威顺序（等同 Docling 的 {@code body.children}），
 * 直接按页序 + 块序遍历，不重排。
 */
public class MineruResponseMapper {

    /** 页眉降级判定：跨 ≥5 页重复的短行（与 Docling 同阈值，见 predicates）。 */
    private static final int HEADER_MIN_PAGES = LayoutPredicates.REPEATED_HEADER_MIN_PAGES;

    /** 页眉候选行长度上限。 */
    private static final int HEADER_MAX_LEN = LayoutPredicates.REPEATED_HEADER_MAX_LEN;

    private final ObjectMapper mapper;

    public MineruResponseMapper(ObjectMapper mapper) {
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
    }

    /** @return 共享的 ObjectMapper（后端客户端解析任务响应复用同一实例）。 */
    public ObjectMapper getObjectMapper() {
        return mapper;
    }

    /**
     * 把 MinerU 的 MiddleJson 映射为块序列。
     *
     * @param documentId  文档 ID
     * @param middleJson  {@code middle_json.json} 的原始字符串
     * @return 块序列（按阅读顺序；页码已 +1 换算为 1 基）
     * @throws MineruUnavailableException 响应缺字段 / schema 不符 / 产出 0 块
     */
    public List<LayoutBlock> map(String documentId, String middleJson) {
        JsonNode root;
        try {
            root = mapper.readTree(middleJson);
        } catch (Exception e) {
            throw new MineruUnavailableException("MinerU MiddleJson 不是合法 JSON：" + e.getMessage(), e);
        }
        // ① schema 识别：官方文档要求按序列化的 schema 键识别（不能只看版本号）
        String schema = root.path("schema").asText("");
        if (!"docvortex.middle".equals(schema)) {
            throw new MineruUnavailableException(
                    "MinerU 响应 schema 不是 docvortex.middle（实际: "
                            + (schema.isEmpty() ? "缺失" : schema) + "）——请用 middle_json 输出");
        }
        JsonNode pages = root.path("pages");
        if (!pages.isArray() || pages.isEmpty()) {
            throw new MineruUnavailableException("MinerU MiddleJson 缺少 pages 数组");
        }

        // ② 先扫描一遍统计「跨页重复短行」（页眉候选）——与 Docling 的
        //    findRepeatedShortLines 同口径，但 MinerU 直接给了 header/footer 类型，
        //    此处只兜底「未被标为 header 但实际是页眉」的 text 块。
        Set<String> repeatedShortLines = findRepeatedShortLines(pages);

        // ③ 按页序 + 块序生成块（阅读顺序权威来源）
        List<LayoutBlock> out = new ArrayList<>();
        int charCursor = 0;
        StringBuilder sectionPath = new StringBuilder();
        // ⚠️ 标题栈：与 Docling mapper 的 replaceAtLevel 同语义
        //    （level 值栈 + 标题栈平行，非连续 level 也能正确建树）。
        List<Integer> pathLevels = new ArrayList<>();
        List<String> pathTitles = new ArrayList<>();
        // ⚠️ 答案区是跨块状态（同 Docling 的 inAnswerZone 槽）
        boolean[] inAnswerZone = {false};

        for (JsonNode page : pages) {
            int pageIdx = page.path("page_idx").asInt(-1);
            // ⚠️⚠️ 0 基 → 1 基换算（官方文档明示 page_idx 与 CLI 页范围相差 1）；
            //    负值或缺失说明响应异常，报错而非瞎填（瞎填页码会让「可回取原文」失效）。
            if (pageIdx < 0) {
                throw new MineruUnavailableException(
                        "MinerU 页缺少 page_idx（documentId=" + documentId + "）");
            }
            int page1 = pageIdx + 1;

            for (JsonNode block : page.path("blocks")) {
                LayoutBlock b = toBlock(block, page1, charCursor, sectionPath,
                        pathLevels, pathTitles, inAnswerZone, repeatedShortLines);
                if (b != null) {
                    out.add(b);
                    charCursor = b.charEnd();
                }
            }
        }
        if (out.isEmpty()) {
            throw new MineruUnavailableException(
                    "MinerU 解析产出 0 个块（documentId=" + documentId + "，可能纯图片或解析失败）");
        }
        return out;
    }

    /**
     * 单块 → LayoutBlock。
     */
    private LayoutBlock toBlock(JsonNode block, int page1, int charCursor,
                                StringBuilder sectionPath,
                                List<Integer> pathLevels, List<String> pathTitles,
                                boolean[] inAnswerZone, Set<String> repeatedShortLines) {
        String rawType = block.path("type").asText("");
        BlockType type = mapBlockType(rawType);
        // content 数组：只拼 text 元素（image_body 等视觉元素无文本）
        StringBuilder textBuf = new StringBuilder();
        for (JsonNode c : block.path("content")) {
            if ("text".equals(c.path("type").asText(""))) {
                String piece = c.path("content").asText("");
                if (!piece.isBlank()) {
                    if (textBuf.length() > 0) {
                        textBuf.append('\n');
                    }
                    textBuf.append(piece.trim());
                }
            }
        }
        String text = textBuf.toString();
        if (text.isBlank()) {
            text = LayoutPredicates.placeholderFor(type);
        }
        String trimmed = text.trim();

        // 标题层级：仅标题块携带（实测 doc_title=1 / paragraph_title=2）
        int level = block.path("level").asInt(0);
        if (type == BlockType.HEADING && level <= 0) {
            level = 1; // ⚠️ 标题必须有层级，否则路径推进退化（与 Docling mapper 同兜底）
        }

        // 页眉降级：未被标为 header/footer/page_number，但文本是跨页重复短行
        // 且不在第 1 页（封面书名是真实内容）——与 Docling mapper 同口径。
        if (type == BlockType.HEADING && !trimmed.isEmpty() && page1 > 1
                && repeatedShortLines.contains(trimmed)) {
            type = BlockType.BOILERPLATE;
        }

        // ⚠️ 答案区状态推进（标题驱动，同 Docling 的状态机语义）
        boolean isAnswerHeading = type == BlockType.HEADING
                && LayoutPredicates.isAnswerZoneHeading(trimmed);
        if (isAnswerHeading) {
            inAnswerZone[0] = true;
        } else if (inAnswerZone[0] && type == BlockType.HEADING) {
            inAnswerZone[0] = false;
        }
        boolean answerZone = inAnswerZone[0];

        // ⚠️ 章节路径推进（只让 level 1~5 的非噪声标题推进——同 Docling 的双闸）
        if (LayoutPredicates.advancesSectionPath(type, level, trimmed)) {
            replaceAtLevel(pathLevels, pathTitles, level,
                    LayoutPredicates.stripPageHeaderSuffix(trimmed));
        }
        String section = sectionPath(sectionPath, pathTitles);

        int start = charCursor;
        int end = start + trimmed.length();
        return new LayoutBlock(type, trimmed, page1, level, section,
                LayoutPredicates.extractExerciseNo(trimmed), answerZone, start, end);
    }

    /**
     * 由标题栈构建当前章节路径（「 / 」分隔，同 Docling mapper）。
     */
    private static String sectionPath(StringBuilder buf, List<String> titles) {
        buf.setLength(0);
        for (int i = 0; i < titles.size(); i++) {
            if (i > 0) {
                buf.append(" / ");
            }
            buf.append(titles.get(i));
        }
        return buf.toString().trim();
    }

    /**
     * 把新标题写入路径的对应层级（与 Docling mapper 的 replaceAtLevel 逐行同语义：
     * level 值栈定位槽位，同级替换、变浅弹出、超深替换末槽）。
     */
    private static void replaceAtLevel(List<Integer> levelStack, List<String> titleStack,
                                       int level, String title) {
        int idx = levelStack.indexOf(level);
        if (idx >= 0) {
            titleStack.set(idx, title);
            while (titleStack.size() > idx + 1) {
                titleStack.remove(titleStack.size() - 1);
                levelStack.remove(levelStack.size() - 1);
            }
        } else if (!levelStack.isEmpty() && level < levelStack.get(levelStack.size() - 1)) {
            while (!levelStack.isEmpty() && levelStack.get(levelStack.size() - 1) > level) {
                titleStack.remove(titleStack.size() - 1);
                levelStack.remove(levelStack.size() - 1);
            }
            if (titleStack.size() >= 3) { // MAX_SECTION_DEPTH（同 Docling 的 3 层）
                titleStack.set(titleStack.size() - 1, title);
                levelStack.set(levelStack.size() - 1, level);
            } else {
                titleStack.add(title);
                levelStack.add(level);
            }
        } else {
            if (titleStack.size() >= 3) {
                titleStack.set(titleStack.size() - 1, title);
                levelStack.set(levelStack.size() - 1, level);
            } else {
                titleStack.add(title);
                levelStack.add(level);
            }
        }
    }

    /**
     * 跨页重复短行统计（页眉候选；与 Docling 的 findRepeatedShortLines 同口径：
     * 长度 ≤30、出现在 ≥5 个不同页）。
     */
    private Set<String> findRepeatedShortLines(JsonNode pages) {
        Map<String, Set<Integer>> lineToPages = new HashMap<>();
        for (JsonNode page : pages) {
            int pageIdx = page.path("page_idx").asInt(0);
            for (JsonNode block : page.path("blocks")) {
                String text = blockText(block);
                if (text.isEmpty() || text.length() > HEADER_MAX_LEN) {
                    continue;
                }
                lineToPages.computeIfAbsent(text, k -> new HashSet<>()).add(pageIdx);
            }
        }
        Set<String> headers = new HashSet<>();
        lineToPages.forEach((text, pgs) -> {
            // ⚠️ 阈值 5（与 Docling 同）：「英语」这类书名在正文页脚也出现，
            //    阈值 3 会把封面书名一并降级（Docling 实测发生过）。
            if (pgs.size() >= HEADER_MIN_PAGES && pgs.size() > 1) {
                headers.add(text);
            }
        });
        return headers;
    }

    /** 拼接块内全部 text 元素（统计用，忽略视觉元素）。 */
    private static String blockText(JsonNode block) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode c : block.path("content")) {
            if ("text".equals(c.path("type").asText(""))) {
                String piece = c.path("content").asText("").trim();
                if (!piece.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(piece);
                }
            }
        }
        return sb.toString().trim();
    }

    /**
     * MinerU 块类型 → {@link BlockType}（实测出现的 12 种已覆盖；未知类型当段落，
     * <b>不丢弃</b>——丢弃会让正文莫名变少，「少」在评测里表现为召回不全）。
     *
     * <p>⚠️ 页眉页脚/页码/脚注是跨页重复噪声，直接 BOILERPLATE
     * （MinerU 的版面模型显式给了这些类别——这是它对教材的核心优势，
     * 不需要像 Docling 那样靠统计兜底）。
     *
     * @param mineruType MinerU 块类型串
     * @return 块类型
     */
    static BlockType mapBlockType(String mineruType) {
        return switch (mineruType) {
            // ⚠️ 两个标题类型都映射 HEADING：doc_title（文档/章标题，level=1）、
            //    paragraph_title（节/小节标题，实测 level=2）。
            //    层级推进由 level + isNoiseHeading 双闸控制（同 Docling）。
            case "doc_title", "paragraph_title", "title" -> BlockType.HEADING;
            case "list" -> BlockType.LIST_ITEM;
            case "table", "table_body", "table_caption", "table_footnote" -> BlockType.TABLE;
            case "image", "image_body", "image_caption", "image_footnote",
                 "chart", "chart_body", "chart_caption", "chart_footnote" -> BlockType.FIGURE;
            case "equation", "equation_number" -> BlockType.FORMULA;
            case "code", "code_body", "code_caption", "code_footnote",
                 "algorithm_body" -> BlockType.FORMULA; // 代码块按公式承载（保留原文，不入正文段）
            // ⚠️ 版面噪声：MinerU 显式分类（教材核心优势），直接判噪
            case "header", "footer", "page_number", "page_footnote" -> BlockType.BOILERPLATE;
            // aside_text（旁注）/ ref_text（参考文献）/ index（索引）/ phonetic 等按段落保留
            default -> BlockType.PARAGRAPH;
        };
    }

    /**
     * MinerU 解析不可用（与 {@link DoclingUnavailableException} 同位的 fail-closed 异常）。
     */
    public static class MineruUnavailableException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public MineruUnavailableException(String message) {
            super(message);
        }

        public MineruUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
