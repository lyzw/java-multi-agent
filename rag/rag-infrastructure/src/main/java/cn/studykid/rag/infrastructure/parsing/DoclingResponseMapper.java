package cn.studykid.rag.infrastructure.parsing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.LayoutBlock;
import cn.studykid.rag.premise.model.PremiseBlock;
import cn.studykid.rag.premise.normalize.SectionPathStack;
import cn.studykid.rag.premise.normalize.TableGrid;

/**
 * Docling 响应 → {@link LayoutBlock} 映射（V1.0.2 T-4 / §4 Z5a）。
 *
 * <p><b>⚠️ 本类的映射规则来自实测响应，不是文档推断</b>。
 * 2026-11-02 对 {@code quay.io/docling-project/docling-serve} 实测确认：
 * <pre>
 * POST /v1/convert/file  (multipart: files=@x.pdf, to_formats=json)
 *   → { "status":"success", "document": { "json_content": { ... } } }
 * json_content = {
 *   "texts":   [ { "self_ref","label","text","level","prov":[{"page_no","bbox":{l,t,r,b}}] } ],
 *   "tables":  [...], "pictures": [...], "groups": [...], "furniture": [...],
 *   "body":    { "children": [ {"$ref":"#/texts/0"}, {"$ref":"#/groups/0"}, ... ] }
 * }
 * </pre>
 *
 * <p><b>⚠️ 三个只有实测才知道的坑</b>：
 * <ol>
 *   <li><b>路径不是</b> {@code /v1/document/convert}，而是 {@code /v1/convert/file}；
 *       form 字段名是 <b>{@code files}</b>（复数），且必须带 {@code to_formats=json}。</li>
 *   <li><b>正文在</b> {@code document.json_content} <b>下</b>，且它可能是
 *       <b>对象也可能是 JSON 字符串</b>（不同版本行为不同）——本类两者都处理。</li>
 *   <li><b>正文块的唯一权威顺序是</b> {@code body.children} 的 {@code $ref} 序列，
 *       <b>不是</b> {@code texts} 数组下标。{@code texts} 里还混着 {@code furniture}
 *       （页眉页脚）与未被正文引用的块，直接遍历 {@code texts} 会把噪声当正文、
 *       把阅读顺序搞乱。</li>
 * </ol>
 *
 * <p><b>⚠️ 四个必须在 Java 侧计算的字段</b>（Docling 不直接给）：
 * {@code exerciseNo}（正则+继承）、{@code inAnswerZone}（三层判定）、
 * {@code sectionPath}（标题栈）、{@code charStart/charEnd}（按阅读顺序累加）。
 */
public class DoclingResponseMapper {

    private final ObjectMapper mapper;

    public DoclingResponseMapper(ObjectMapper mapper) {
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
    }

    /**
     * 从 Docling 完整响应中提取 {@code document.json_content}（对象或字符串皆处理），
     * 供分块桥复用（RAG-D26 §2.4 纪律①：HybridChunker 必须吃<b>解析阶段</b>的
     * DoclingDocument——二次解析会让 item_refs 与块对不上，角色回填必然错位）。
     *
     * <p>⚠️ 本方法只做提取不做映射；失败抛
     * {@link DoclingDocumentParserPort.DoclingUnavailableException}（与 map 同纪律）。
     *
     * @param responseBody Docling {@code /v1/convert/file} 的原始响应
     * @return json_content 的原始 JSON 串
     */
    public String extractJsonContent(String responseBody) {
        JsonNode root;
        try {
            root = mapper.readTree(responseBody);
        } catch (Exception e) {
            throw new DoclingDocumentParserPort.DoclingUnavailableException(
                    "Docling 响应不是合法 JSON：" + e.getMessage(), e);
        }
        String status = root.path("status").asText("");
        if ("failure".equalsIgnoreCase(status)) {
            String err = root.path("errors").toString();
            throw new DoclingDocumentParserPort.DoclingUnavailableException(
                    "Docling 解析失败：" + (err.isBlank() ? "未知原因" : err));
        }
        JsonNode jsonContent = root.path("document").path("json_content");
        if (jsonContent.isMissingNode() || jsonContent.isNull()) {
            throw new DoclingDocumentParserPort.DoclingUnavailableException(
                    "Docling 响应缺少 document.json_content（to_formats 是否漏了 json？）");
        }
        return jsonContent.isTextual() ? jsonContent.asText() : jsonContent.toString();
    }

    /**
     * 把 Docling 的完整响应映射为块序列。
     *
     * @param documentId 文档 ID（写入 {@link cn.studykid.rag.ports.ParsedDocument}）
     * @param responseBody Docling {@code /v1/convert/file} 的原始响应
     * @return 块序列（按阅读顺序）
     * @throws DoclingDocumentParserPort.DoclingUnavailableException 响应缺字段或解析失败
     */
    /**
     * 既有契约：映射为无扩展信息的块序列（供 {@code DocumentParserPort} 与既有测试使用）。
     * 委托给 {@link #mapToPremiseBlocks} 后恒等解包，两条路径共用同一份语义计算。
     */
    public List<LayoutBlock> map(String responseBody) {
        return mapToPremiseBlocks(responseBody).stream()
                .map(PremiseBlock::toLayoutBlock)
                .toList();
    }

    /**
     * P1-3（RAG-D28）：映射为<b>带几何</b>的归一化块序列。
     *
     * <p>bbox 来自 Docling 实测字段 {@code prov[0].bbox{l,t,r,b}}，转成
     * {@code [l,t,r,b]} 四元组（原样保留 pt 坐标系）。confidence 原始响应无此字段，
     * 保持 {@code NaN}（⛔ 不伪造数据）。
     */
    public List<PremiseBlock> mapToPremiseBlocks(String responseBody) {
        JsonNode root;
        try {
            root = mapper.readTree(responseBody);
        } catch (Exception e) {
            throw new DoclingDocumentParserPort.DoclingUnavailableException(
                    "Docling 响应不是合法 JSON：" + e.getMessage(), e);
        }
        // ⚠️ 先看 status：失败时 document 可能为空，直接往下取会 NPE
        String status = root.path("status").asText("");
        if ("failure".equalsIgnoreCase(status)) {
            String err = root.path("errors").toString();
            throw new DoclingDocumentParserPort.DoclingUnavailableException(
                    "Docling 解析失败：" + (err.isBlank() ? "未知原因" : err));
        }

        JsonNode jsonContent = root.path("document").path("json_content");
        if (jsonContent.isMissingNode() || jsonContent.isNull()) {
            throw new DoclingDocumentParserPort.DoclingUnavailableException(
                    "Docling 响应缺少 document.json_content（to_formats 是否漏了 json？）");
        }
        // ⚠️ 实测不同版本：json_content 可能是对象，也可能是 JSON 字符串
        if (jsonContent.isTextual()) {
            try {
                jsonContent = mapper.readTree(jsonContent.asText());
            } catch (Exception e) {
                throw new DoclingDocumentParserPort.DoclingUnavailableException(
                        "json_content 是字符串但无法解析：" + e.getMessage(), e);
            }
        }

        // ① 索引所有块：self_ref → 节点（texts / tables / pictures）
        Map<String, JsonNode> byRef = new HashMap<>();
        Map<String, BlockType> typeByRef = new HashMap<>();
        indexBlocks(jsonContent.path("texts"), byRef, typeByRef);
        indexBlocks(jsonContent.path("tables"), byRef, typeByRef);
        indexBlocks(jsonContent.path("pictures"), byRef, typeByRef);

        // ② 识别「被 Docling 误标为标题的页眉」
        //    实测：沪教版教材的页眉「上海教育出版社」被 Docling 标成 section_header，
        //    222 次出现在正文里 → 每个片段都带这句噪声，
        //    既污染词法索引，也让「章节路径」反复被它推进。
        //    判据：**跨页重复的短行**即页眉（教材每页都印）。
        Set<String> repeatedShortLines = findRepeatedShortLines(jsonContent);

        // ③ 按 body.children 的 $ref 顺序生成块（阅读顺序的唯一权威来源）
        List<PremiseBlock> out = new ArrayList<>();
        int charCursor = 0;
        // ⚠️ P1-4：与 SectionPathResolver / MinerU mapper 共用同一份章节路径状态机，
        //    彻底移除两份重复的 replaceAtLevel 实现（分隔符与 level 栈口径不再漂移）。
        SectionPathStack sectionPath = new SectionPathStack();
        // ⚠️⚠️ 答案区是**跨块状态**，不是单块属性：
        //    实测发现「Answer Key」标题块被判 YES，但其下方真正的答案内容却是 no——
        //    这样答案会以讲解身份进入检索，**直接泄题**。
        //    正确语义：一旦遇到答案区标题，其后所有块都属答案区，直到遇到
        //    「下一个顶层新章节标题」（新的 section_header）才可能退出。
        boolean[] inAnswerZone = {false};
        for (JsonNode child : jsonContent.path("body").path("children")) {
            String ref = child.path("$ref").asText("");
            if (ref.isEmpty()) {
                continue;
            }
            // ⚠️ groups 是「组」而非内容块（如列表容器），其内部 children 需展开。
            if (ref.startsWith("#/groups/")) {
                for (JsonNode grand : child.path("children")) {
                    PremiseBlock b = toBlock(grand.path("$ref").asText(""), byRef, typeByRef,
                            charCursor, sectionPath, inAnswerZone, repeatedShortLines);
                    if (b != null) {
                        out.add(b);
                        charCursor = b.charEnd();
                    }
                }
                continue;
            }
            PremiseBlock block = toBlock(ref, byRef, typeByRef, charCursor, sectionPath,
                    inAnswerZone, repeatedShortLines);
            if (block != null) {
                out.add(block);
                charCursor = block.charEnd();
            }
        }
        if (out.isEmpty()) {
            throw new DoclingDocumentParserPort.DoclingUnavailableException(
                    "Docling 解析产出 0 个块（文档可能是纯图片或解析失败）");
        }
        return out;
    }

    /**
     * 单个 ref → LayoutBlock。
     *
     * @param ref         形如 {@code #/texts/3}
     * @param byRef       索引
     * @param typeByRef   类型索引
     * @param charCursor  当前字符游标
     * @param sectionPath 章节路径（会被标题块推进）
     * @return 块；该 ref 无内容时返回 null
     */
    private PremiseBlock toBlock(String ref, Map<String, JsonNode> byRef,
                                 Map<String, BlockType> typeByRef, int charCursor,
                                 SectionPathStack sectionPath, boolean[] inAnswerZone,
                                 Set<String> repeatedShortLines) {
        JsonNode node = byRef.get(ref);
        if (node == null) {
            return null;
        }
        BlockType type = typeByRef.getOrDefault(ref, BlockType.PARAGRAPH);
        // ⚠️⚠️ 文本来源有两个，顺序不可颠倒：
        //    ① `text`：普通文本块的内容；
        //    ② `orig`：**公式块的原文** —— 实测数学教材里 label=formula 的
        //       节点 `text` 恒为 ''，内容只存在于 `orig`
        //       （如 '例３ 画一条数轴，并用数轴上的点表示下列各数：…'）。
        //    首版只读 `text`，导致 18 个公式块全部退化成 '[图]' 占位符，
        //    **数学教材的核心内容（例题/公式）整段丢失**。
        String text = node.path("text").asText("");
        if (text.isBlank() && type == BlockType.FORMULA) {
            text = node.path("orig").asText("");
        }
        // ⚠️ P1-5：表格内容不在 `text` 里，而在 `data.table_cells[]`。
        //    首版只读 text → 表格整册退化成 [表格] 占位符（表格内容 100% 丢失）。
        //    这里按 Docling TableCell schema 真实重建；解析不出才保留占位符（fail-soft-but-honest）。
        TableGrid tableGrid = null;
        if (type.isTable()) {
            tableGrid = TableContentExtractor.fromDocling(node);
            if (tableGrid != null) {
                text = tableGrid.toMarkdown();
            }
        }
        if (text.isBlank()) {
            text = LayoutPredicates.placeholderFor(type);
        }
        String trimmed = text.trim();

        int page = 1;
        JsonNode prov = node.path("prov");
        if (prov.isArray() && !prov.isEmpty()) {
            page = Math.max(1, prov.get(0).path("page_no").asInt(1));
        }

        // ⚠️ 页眉降级：Docling 把教材页眉「上海教育出版社」误标成 section_header，
        //    实测 222 次出现在正文里 → 每个片段都带这句噪声，
        //    既污染词法索引，也让章节路径反复被它推进。
        //    判据：跨 ≥5 页重复的短行，且**不在第 1 页**（封面上的书名是真实内容）。
        //    降级为 BOILERPLATE 后由 IngestionPipeline.isNoise() 丢弃。
        if (type == BlockType.HEADING && !trimmed.isEmpty() && page > 1
                && repeatedShortLines.contains(trimmed)) {
            type = BlockType.BOILERPLATE;
        }

        int level = node.path("level").asInt(0);
        if (type == BlockType.HEADING && level <= 0) {
            level = 1; // ⚠️ 标题必须有层级，否则章节路径推进逻辑会退化
        }

        int start = charCursor;
        int end = start + trimmed.length();

        // 章节路径：标题块推进，其余块沿用当前路径。
        // ⚠️⚠️ 推进闸（只让 level 1~5 的非噪声标题推进）仍在此处——
        //    实测沪教版 410 个 section_header 里 409 个是 level=6（单元内小节），
        //    若让它们推进会无限累积（94% 的块路径超 60 字符，引用溯源失效）。
        // ⚠️ P1-4：闸后的「层级槽位替换 / 跨页新分区 / 页眉剥除 / 分隔符」全部委托
        //    SectionPathStack——与 MinerU mapper 和 SectionPathResolver 共用同一份实现。
        //    原血泪细节（同级替换、level 不连续、比最深层级、236 字符超长路径）
        //    已逐条搬进 SectionPathStack 类注释，勿在此处复活第二份算法。
        if (LayoutPredicates.advancesSectionPath(type, level, trimmed)) {
            sectionPath.advance(page, level, trimmed);
        }
        String section = sectionPath.render();

        // ⚠️ 答案区状态推进（**只由标题驱动**，见 isAnswerZoneHeading 的血泪注释）：
        //    ① 本块是「答案区标题」→ 进入答案区；
        //    ② 已是答案区时，遇到**任意**新标题即退出
        //       （答案区不会被下一个单元/章节的内容延续）；
        //    ③ 其余块沿用当前状态。
        // ⚠️ 状态机推进与 MinerU 共享（LayoutPredicates.advanceAnswerZone）。
        LayoutPredicates.advanceAnswerZone(type, trimmed, inAnswerZone);
        boolean answerZone = inAnswerZone[0];

        LayoutBlock layout = new LayoutBlock(type, trimmed, page, level, section,
                extractExerciseNo(trimmed), answerZone, start, end);
        // ⚠️ P1-3：bbox 来自 prov[0].bbox{l,t,r,b}，转成 [l,t,r,b]（原样 pt 坐标）。
        //    confidence 原始响应无此字段 → NaN（⛔ 不伪造），用 hasConfidence() 判定未知。
        return new PremiseBlock(layout, extractBbox(prov), Double.NaN, tableGrid);
    }

    /**
     * P1-3：解析 Docling 块级 bbox（{@code prov[0].bbox{l,t,r,b}} → {@code [l,t,r,b]}）。
     *
     * <p>⚠️ 缺字段/缺任一坐标/非数值时返回 {@code null}（={@code hasBbox()==false}），
     * <b>不得</b>用 {@code [0,0,0,0]} 冒充——空框会让引用回看定位到页面左上角。
     */
    static double[] extractBbox(JsonNode prov) {
        if (prov == null || !prov.isArray() || prov.isEmpty()) {
            return null;
        }
        JsonNode bbox = prov.get(0).path("bbox");
        if (!bbox.isObject()) {
            return null;
        }
        JsonNode l = bbox.path("l");
        JsonNode t = bbox.path("t");
        JsonNode r = bbox.path("r");
        JsonNode b = bbox.path("b");
        if (!l.isNumber() || !t.isNumber() || !r.isNumber() || !b.isNumber()) {
            return null;
        }
        return new double[]{l.asDouble(), t.asDouble(), r.asDouble(), b.asDouble()};
    }

    /**
     * 索引 Docling 的 texts / tables / pictures。
     *
     * @param arr       JSON 数组
     * @param byRef     ref → 节点
     * @param typeByRef ref → 块类型
     */
    private void indexBlocks(JsonNode arr, Map<String, JsonNode> byRef,
                             Map<String, BlockType> typeByRef) {
        if (!arr.isArray()) {
            return;
        }
        for (JsonNode n : arr) {
            String ref = n.path("self_ref").asText("");
            if (ref.isEmpty()) {
                continue;
            }
            byRef.put(ref, n);
            if (ref.startsWith("#/tables/")) {
                typeByRef.put(ref, BlockType.TABLE);
            } else if (ref.startsWith("#/pictures/")) {
                typeByRef.put(ref, BlockType.FIGURE);
            } else {
                typeByRef.put(ref, mapLabel(n.path("label").asText("")));
            }
        }
    }

    /**
     * Docling label → BlockType（实测出现的 label 已覆盖）。
     *
     * <p>⚠️ 未知 label 一律当段落，<b>不</b>丢弃：丢弃会让正文莫名变少，
     * 而「少」在评测里表现为召回不全，比「多一段」更难排查。
     *
     * @param label Docling 的 label
     * @return 块类型
     */
    static BlockType mapLabel(String label) {
        return switch (label) {
            case "section_header", "title" -> BlockType.HEADING;
            case "list_item" -> BlockType.LIST_ITEM;
            case "table" -> BlockType.TABLE;
            case "picture" -> BlockType.FIGURE;
            case "formula", "equation" -> BlockType.FORMULA;
            // ⚠️ 页眉页脚是跨页重复噪声，交给 IngestionPipeline.isNoise() 丢弃
            case "page_header", "page_footer" -> BlockType.BOILERPLATE;
            default -> BlockType.PARAGRAPH;
        };
    }

    /**
     * 找出「跨页重复出现的短行」——即页眉。
     *
     * <p><b>⚠️ 判据</b>：同一文本在<b>不少于 3 个不同页</b>上出现，且长度 ≤ 30。
     * 教材每页都印的出版社名/册次就满足；正文里偶然重复的句子不会跨 3 页以上。
     *
     * @param jsonContent DoclingDocument
     * @return 页眉文本集合
     */
    private Set<String> findRepeatedShortLines(JsonNode jsonContent) {
        Map<String, Set<Integer>> lineToPages = new HashMap<>();
        for (JsonNode t : jsonContent.path("texts")) {
            JsonNode prov = t.path("prov");
            if (!prov.isArray() || prov.isEmpty()) {
                continue;
            }
            int page = prov.get(0).path("page_no").asInt(0);
            String text = t.path("text").asText("").trim();
            if (text.isEmpty() || text.length() > 30) {
                continue;
            }
            lineToPages.computeIfAbsent(text, k -> new HashSet<>()).add(page);
        }
        Set<String> headers = new HashSet<>();
        lineToPages.forEach((text, pages) -> {
            // ⚠️ 阈值与最小页数语义统一在 {@link LayoutPredicates}（RAG-D26 阶段 1.2）：
            //    阈值 5 的实测依据（书名/册次词在正文页脚也出现，3 会误降级封面书名）
            //    记录在 predicates 侧，双 mapper 共享。
            if (pages.size() > 1 && LayoutPredicates.isRepeatedHeaderLine(
                    new ArrayList<>(pages))) {
                headers.add(text);
            }
        });
        return headers;
    }

    /**
     * 抽取题号（如 {@code 1.}、{@code (2)}、{@code Exercise 3}）。
     *
     * <p>⚠️ 委托 {@link LayoutPredicates}（RAG-D26 阶段 1.2 抽取的共享版面判定）：
     * MinerU 与 Docling 必须用同一套题号规则，两份正则会静默漂移。
     * 本方法保留为兼容入口（既有测试引用）；规则修正只改 predicates。
     *
     * @param text 块文本
     * @return 题号；无则 null
     */
    static String extractExerciseNo(String text) {
        return LayoutPredicates.extractExerciseNo(text);
    }

    /**
     * 答案区<b>标题</b>判定（只认「像标题的」答案区标记）。
     *
     * <p><b>⚠️⚠️ 这里踩过一个真实缺陷，务必保留此注释</b>：
     * 首版把正文里出现 {@code answers} 也算命中，结果 4 个<b>题干</b>
     * （如 “Underline the answers to the questions…”）被误判为答案区起点，
     * 而答案区状态又一直延续到下一个顶层标题 ——
     * 最终 <b>1671/1871（89%）</b> 的块被判成答案区，整本教材几乎全被过滤。
     *
     * <p>教训：<b>「是否处于答案区」必须由标题驱动，不能由正文关键词驱动</b>。
     * 正文里出现 answer/答案 是教学语言的一部分（题干经常提到它），
     * 用它判区等于把整本书判成答案。
     *
     * @param text 块文本
     * @return 是答案区标题返回 true
     */
    static boolean isAnswerZoneHeading(String text) {
        // ⚠️ 委托 {@link LayoutPredicates}（RAG-D26 阶段 1.2 共享判定）：
        //    89% 事故的教训注释随实现移至 predicates，双 mapper 共享同一份规则。
        return LayoutPredicates.isAnswerZoneHeading(text);
    }

    // ⚠️ P1-4：原 replaceAtLevel + pathLevels/pathTitles/lastHeadingPage/topLevelPage
    //    四个跨块状态已全部收敛到 SectionPathStack（rag-premise/normalize）。
    //    这些状态的血泪注释（level 不连续 1,2,3,5、同级替换、236 字符超长路径、
    //    页码作为新分区信号）随实现迁移至 SectionPathStack 类注释——勿在此复活第二份算法。
}
