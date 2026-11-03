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

    /**
     * 参与章节路径推进的最大标题层级。
     *
     * <p>⚠️ Docling 的 level=6 表示「单元内小节」（实测 410 个标题里 409 个是它），
     * 它们几乎每页出现；若参与推进，路径会无限累积。
     */
    private static final int MAX_SECTION_LEVEL = 5;

    /**
     * 章节路径最大层数（册 > 单元 > 小节）。
     */
    private static final int MAX_SECTION_DEPTH = 3;

    private final ObjectMapper mapper;

    public DoclingResponseMapper(ObjectMapper mapper) {
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
    }

    /**
     * 把 Docling 的完整响应映射为块序列。
     *
     * @param documentId 文档 ID（写入 {@link cn.studykid.rag.ports.ParsedDocument}）
     * @param responseBody Docling {@code /v1/convert/file} 的原始响应
     * @return 块序列（按阅读顺序）
     * @throws DoclingDocumentParserPort.DoclingUnavailableException 响应缺字段或解析失败
     */
    public List<LayoutBlock> map(String responseBody) {
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
        List<LayoutBlock> out = new ArrayList<>();
        int charCursor = 0;
        StringBuilder sectionPath = new StringBuilder();
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
                    LayoutBlock b = toBlock(grand.path("$ref").asText(""), byRef, typeByRef,
                            charCursor, sectionPath, inAnswerZone, repeatedShortLines);
                    if (b != null) {
                        out.add(b);
                        charCursor = b.charEnd();
                    }
                }
                continue;
            }
            LayoutBlock block = toBlock(ref, byRef, typeByRef, charCursor, sectionPath,
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
    private LayoutBlock toBlock(String ref, Map<String, JsonNode> byRef,
                                Map<String, BlockType> typeByRef, int charCursor,
                                StringBuilder sectionPath, boolean[] inAnswerZone,
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
        if (text.isBlank()) {
            text = switch (type) {
                case TABLE -> "[表格]";
                case FIGURE -> "[图]";
                case FORMULA -> "[公式]";
                default -> "[空块]";
            };
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

        // 章节路径：标题块推进，其余块沿用当前路径
        String section = sectionPath.toString().trim();
        // ⚠️⚠️ 只让 level 1~5 的标题推进路径。
        //    实测沪教版教材：410 个 section_header 里 409 个是 level=6，
        //    那是「单元内小节」（B1 Look and say / Word study / C5 Further thinking），
        //    几乎每页都有。若让它们参与推进，路径会变成
        //    「英语 / 英语 / 2 Rules around us Unit / Festivals across cultures / …」
        //    无限累积 —— 实测 94% 的块路径超 60 字符，引用溯源彻底失效。
        if (type == BlockType.HEADING && level >= 1 && level <= MAX_SECTION_LEVEL
                && !isNoiseHeading(trimmed)) {
            // ⚠️⚠️ 层级推进的语义是**按槽位替换**，不是追加。
            //    首版用「截断到 level-1 再追加」，但 level-1 的处理有 off-by-one：
            //    实测上册得到「Starter 1 / Starter 2 / Starter 3」——
            //    三个**同级**（lvl=3）的标题被串成一条链，
            //    而正确结果应是「Starter 3」（同级替换）。
            // ⚠️ 标题文本里的页眉后缀要剥掉再入路径：
            //    实测下册 p38 的标题是 'Festivals across cultures 上海教育出版社'
            //    —— 标题是真的，但它**带着**页眉。直接入路径会让 1438 个片段的
            //    sectionPath 都含「上海教育出版社」（页眉噪声）。
            //    正解：路径里只留标题主体，页眉部分丢弃（页眉本身另有专门的降级逻辑）。
            // ⚠️ 先判断这是「新分区」还是「子标题」：
            //    同一页或紧邻页（≤1 页）的标题视为**子标题**（沿用父层级）；
            //    跨页较多（>1 页）且 level 不更深时视为**新的顶层分区**（清空路径）。
            // ⚠️ 判据必须是「比当前**最深**层级更浅或持平」——不是「比栈首更浅」。
            //    实测上册 p105 'Exploring China'(lvl=1) → p108 'Exploring the world'(lvl=3)
            //    → p109 'Bridging cultures'(lvl=4)：用栈首(lvl=1)比较时 lvl=3/4 都不满足，
            //    于是路径一条条往上叠，p103~p116 累积出 236 个超长路径。
            //    改成与「最深层级」比较后，同级/更浅的跨页标题会正确开启新分区。
            int deepest = pathLevels.isEmpty() ? Integer.MAX_VALUE
                    : pathLevels.get(pathLevels.size() - 1);
            boolean newPartition = page - lastHeadingPage > 1 && level <= deepest;
            if (newPartition) {
                // 新的顶层分区：清空路径，从这一条重新开始
                pathLevels.clear();
                pathTitles.clear();
            }
            replaceAtLevel(sectionPath, level, stripPageHeaderSuffix(trimmed));
            section = sectionPath.toString().trim();
            lastHeadingPage = page;
            if (pathLevels.isEmpty()) {
                topLevelPage = page;
            }
        }

        // ⚠️ 答案区状态推进（**只由标题驱动**，见 isAnswerZoneHeading 的血泪注释）：
        //    ① 本块是「答案区标题」→ 进入答案区；
        //    ② 已是答案区时，遇到**任意**新标题即退出
        //       （答案区不会被下一个单元/章节的内容延续）；
        //    ③ 其余块沿用当前状态。
        boolean isAnswerHeading = type == BlockType.HEADING && isAnswerZoneHeading(trimmed);
        if (isAnswerHeading) {
            inAnswerZone[0] = true;
        } else if (inAnswerZone[0] && type == BlockType.HEADING) {
            inAnswerZone[0] = false;
        }
        boolean answerZone = inAnswerZone[0];

        return new LayoutBlock(type, trimmed, page, level, section,
                extractExerciseNo(trimmed), answerZone, start, end);
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
     * 剥掉标题里的页眉后缀。
     *
     * <p>⚠️ 实测：Docling 会把同一行的页眉拼进标题文本
     * （如 'Festivals across cultures 上海教育出版社'）。
     * 标题本身要保留，页眉部分要去掉——否则路径里会带满页眉噪声。
     *
     * @param text 原始标题
     * @return 剥掉页眉后的标题（若剥完为空则返回原值）
     */
    private static String stripPageHeaderSuffix(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String stripped = text.replaceAll("上海教育出版社", "").trim();
        return stripped.isEmpty() ? text : stripped;
    }

    /**
     * 判断标题是否为「不该进入章节路径的噪声标题」。
     *
     * <p><b>⚠️ 实测依据</b>：沪教版教材的封面/封底标题会让路径变成
     * 「英语 / 英语 / 2 Rules around us Unit / …」——
     * 「英语」出现两次（封面 + 内页），「上海教育出版社」是页眉噪声。
     * 这些内容本身是真实正文（不能丢），只是<b>不该作为章节层级</b>。
     *
     * @param text 标题文本
     * @return 是噪声标题返回 true
     */
    private static boolean isNoiseHeading(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String t = text.trim();
        // 出版社名（页眉/封底）
        if (t.contains("出版社")) {
            // ⚠️⚠️ 但「<真实标题> 上海教育出版社」这种**标题带页眉后缀**的情况
            //    是真实内容（实测下册 p38 'Festivals across cultures 上海教育出版社'，
            //    lvl=4，是「2 Rules around us」下的子节）。
            //    直接判噪声会把它整条丢掉 → 章节层级缺失。
            //    正解：剥掉页眉后缀后仍剩有意义内容，才算噪声标题。
            String stripped = t.replaceAll("上海教育出版社", "")
                    .replaceAll("[\\s·、,，]+$", "").trim();
            if (stripped.isEmpty()) {
                return true;
            }
            // 剥掉后仍有内容 → 是带页眉的真实标题，不算噪声
            return false;
        }
        // 书名/册次等封面元素
        if (t.equals("英语") || t.equals("ENGLISH") || t.equals("YINGYU")) {
            return true;
        }
        if (t.contains("义务教育教科书") || t.contains("五·四学制")
                || t.contains("课标") || t.contains("后 记") || t.contains("后记")) {
            return true;
        }
        // 纯页码
        if (t.matches("\\d{1,3}")) {
            return true;
        }
        // ⚠️ 目录页标题：实测上册第 7 页有 lvl=2 的「Contents」，
        //    若参与路径推进，正文所有片段的路径都会以「Contents / ...」开头，
        //    引用溯源时看到的是目录而不是真实章节。
        if (t.equals("Contents") || t.equals("目录") || t.equals("Table of contents")) {
            return true;
        }
        // ⚠️⚠️ 「疑似正文被误标为标题」：实测上册 p116 的歌词页被 Docling 标成 lvl=1
        //    （'2. (Page 3) Big E, little e, she sells seashells…'），
        //    这些是**歌词正文**，不是章节标题。它们作为顶层标题入路径后，
        //    实测产生 168~170 个超长路径。
        //    判据：**以「序号 + (Page N)」开头** —— 教材正文交叉引用的典型特征。
        if (t.matches("^\\d{1,3}\\.\\s*\\(Page\\s+\\d+\\).*")) {
            return true;
        }
        // ⚠️ 过长的标题多半是正文句子而非标题（真实章节标题通常 < 60 字符）
        if (t.length() > 60) {
            return true;
        }
        return false;
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
            // ⚠️ 阈值 5（不是 3）：实测「英语」这类书名/册次词在正文页脚也出现，
            //    用 3 会把封面上的书名一并降级成页眉（实测确实发生了）。
            //    真实页眉（出版社名）几乎每页都有，跨页数远大于 5。
            if (pages.size() >= 5 && pages.size() > 1) {
                headers.add(text);
            }
        });
        return headers;
    }

    /**
     * 抽取题号（如 {@code 1.}、{@code (2)}、{@code Exercise 3}）。
     *
     * @param text 块文本
     * @return 题号；无则 null
     */
    static String extractExerciseNo(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        // 实测教材题号形态（沪教版）：
        //   "1. What is your name?"        → 1.
        //   "2 Work out the rules"         → 2  （⚠️ 无点号，首版漏了这种，导致 1871 块只抽到 3 个）
        //   "（1）Listen and match"         → （1）
        //   "Exercise 3"                   → Exercise 3
        //   "1 Read and answer"            → 1
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^\\s*(?:[(（]?\\s*(\\d{1,3})\\s*[.、)）]"
                        + "|[(（]\\s*(\\d{1,3})\\s*[)）]"
                        + "|Exercise\\s+(\\d{1,3})"
                        // 行首 1~2 位数 + 空格 + 大写字母开头（如 "2 Work out"），
                        // 且后面必须是字母而非纯数字（排除 "2024 年" 这类年份）
                        + "|(\\d{1,2})\\s+(?=[A-Za-z]))")
                .matcher(text);
        if (m.find()) {
            for (int g = 1; g <= 4; g++) {
                if (m.group(g) != null) {
                    return m.group(g);
                }
            }
        }
        return null;
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
        String t = text == null ? "" : text.trim();
        if (t.isEmpty() || t.length() > 40) {
            // ⚠️ 答案区标题通常很短；过长的文本是段落，不是标题
            return false;
        }
        String lower = t.toLowerCase();
        // 必须「以」答案区字样开头或整块就是这几类短标题
        return lower.startsWith("answer key") || lower.startsWith("answers to")
                || lower.startsWith("answer sheet") || lower.equals("answers")
                || t.startsWith("参考答案") || t.startsWith("答案与解析")
                || t.startsWith("答案") || t.startsWith("练习答案")
                || t.startsWith("习题答案");
    }

    /**
     * 把新标题写入路径的<b>对应层级</b>，替换该层及更深的内容。
     *
     * <p>路径是<b>按层级排列的栈</b>：level=1 占第 1 槽，level=2 占第 2 槽……
     * 新标题进来时，它<b>占据</b>自己那一槽，<b>清掉它后面的所有槽</b>。
     *
     * <p>例：路径已有 {@code "Part I / Unit 1"}（Unit 1 是 level 2），
     * 现在来了个 level=2 的 {@code "Unit 2"} → 结果应为 {@code "Part I / Unit 2"}，
     * 而<b>不是</b> {@code "Part I / Unit 1 / Unit 2"}。
     *
     * @param path  路径（原地修改）
     * @param level 新标题层级（1 基）
     * @param title 新标题文本
     */
    private void replaceAtLevel(StringBuilder path, int level, String title) {
        // ⚠️⚠️ 必须按「**level 值序列**」而非「level-1 索引」定位槽位。
        //    实测上册的 level 序列是 1,2,3,5 混用（不是连续的 1,2,3,4）：
        //      p2  '英语'         lvl=1
        //      p7  'Contents'     lvl=2
        //      p11 'Starter 1'    lvl=3
        //      p17 'To-do list'  lvl=5   ← 跳过了 4
        //    用 level-1 当索引时，lvl=5 会落到第 5 槽（超深度），
        //    而 lvl=3 的三个兄弟（Starter 1/2/3）本该互相替换，
        //    结果却被串成「Starter 1 / Starter 2 / Starter 3」的链。
        //
        //    正解：维护一个「已见 level 值」的栈——
        //      新标题的 level 若已在栈中 → 替换该槽并截断其后的槽；
        //      若不在栈中 → 追加到栈顶（更深一层）。
        //    这样 level 值不连续也能得到正确的树。
        List<Integer> levelStack = pathLevels;
        List<String> titleStack = pathTitles;

        int idx = levelStack.indexOf(level);
        if (idx >= 0) {
            // 同级 → 替换并截断更深的部分
            titleStack.set(idx, title);
            while (titleStack.size() > idx + 1) {
                titleStack.remove(titleStack.size() - 1);
                levelStack.remove(levelStack.size() - 1);
            }
        } else if (!levelStack.isEmpty() && level < levelStack.get(levelStack.size() - 1)) {
            // ⚠️⚠️ level **变小** = 层级变浅（回到更上层），不是「更深一层」。
            //    实测上册：p18 'Starter 4'(lvl=3) 之后 p19 出现 'Part I'(lvl=1)，
            //    若按「不在栈中 → 追加」处理，会得到「Starter 4 / Part I」——
            //    Part I 是**顶层**分区，不可能挂在 Starter 4 之下。
            //    正解：弹出所有比它深的槽，把它放到剩下的最后一位。
            while (!levelStack.isEmpty() && levelStack.get(levelStack.size() - 1) > level) {
                titleStack.remove(titleStack.size() - 1);
                levelStack.remove(levelStack.size() - 1);
            }
            if (titleStack.size() >= MAX_SECTION_DEPTH) {
                titleStack.set(titleStack.size() - 1, title);
                levelStack.set(levelStack.size() - 1, level);
            } else {
                titleStack.add(title);
                levelStack.add(level);
            }
        } else {
            // 更深 → 追加一层
            if (titleStack.size() >= MAX_SECTION_DEPTH) {
                // ⚠️ 超过最大深度：替换最后一槽（不丢标题，只是不再细分）
                titleStack.set(titleStack.size() - 1, title);
                levelStack.set(levelStack.size() - 1, level);
            } else {
                titleStack.add(title);
                levelStack.add(level);
            }
        }
        // 重建
        path.setLength(0);
        for (int i = 0; i < titleStack.size(); i++) {
            if (i > 0) {
                path.append(" / ");
            }
            path.append(titleStack.get(i));
        }
    }

    /**
     * 上一个标题所在页码（用于判断标题是否开启了新的顶层分区）。
     *
     * <p>⚠️ 之所以要它：Docling 的 level 值**不可靠**（实测会跳号：
     * 上册出现 1→3→5，下册出现 1→3→4），纯靠数值比较会把
     * 'Part I'(lvl=1) 与 'Starter 1'(lvl=3) 判成父子，
     * 而实测二者其实是**平级的顶层分区**（Part I 在 p19、Starter 1 在 p11，
     * 是两套独立的编排）。
     * 页码是最可靠的信号：真正的子标题紧跟父标题出现在同一页或紧邻页。
     */
    private int lastHeadingPage = -1;

    /**
     * 当前路径的顶层标题所在页码（用于判定「新标题是顶层分区还是子标题」）。
     */
    private int topLevelPage = -1;

    /**
     * 路径对应的 level 值栈（与 {@link #pathTitles} 平行）。
     *
     * <p>⚠️ 见 {@link #replaceAtLevel}：level 值不连续（实测 1,2,3,5），
     * 因此**不能**用 level-1 当数组下标，必须单独记录每个槽的 level 值。
     */
    private final List<Integer> pathLevels = new ArrayList<>();

    /** 路径各槽的标题文本（与 {@link #pathLevels} 平行）。 */
    private final List<String> pathTitles = new ArrayList<>();
}
