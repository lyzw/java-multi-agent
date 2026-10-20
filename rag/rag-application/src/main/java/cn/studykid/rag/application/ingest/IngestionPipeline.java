package cn.studykid.rag.application.ingest;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.chunk.ChunkRole;
import cn.studykid.rag.core.knowledge.KnowledgePoint;
import cn.studykid.rag.core.version.EditionCoordinate;
import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.DocumentParserPort;
import cn.studykid.rag.ports.LayoutBlock;
import cn.studykid.rag.ports.ParsedDocument;

/**
 * 入库切分用例（RAG-D04 §3）。
 *
 * <p><b>⚠️ 切分质量是教辅 RAG 的第一瓶颈</b>，比检索算法更关键。
 * 检索算法再好，解析时把公式 OCR 成乱码、丢掉题号、
 * 把答案区混进正文，召回就必然出错——而且<b>这种错误在评测里
 * 表现为「召回不全」而非「答错」</b>，极易被归因错误地漏检。
 *
 * <p><b>切分策略：结构优先，语义兜底</b>。
 * <ul>
 *   <li><b>结构切分</b>（首选）：以版面结构为边界——
 *       标题块开启新节，题目块自成单元，答案块自成单元。</li>
 *   <li><b>语义兜底</b>：结构不可得时，退化为带重叠的固定窗口切分，
 *       且<b>重叠量必须大于 0</b>——教辅的定义与适用条件常被窗口切开。</li>
 * </ul>
 *
 * <p><b>⚠️ 角色判定基于版面，不基于模型</b>：
 * {@code inAnswerZone} 与 {@code exerciseNo} 来自解析器的版面识别。
 * 若用模型判断角色，模型会把「讲评」误判为「答案」——
 * 而误判为答案的代价是<b>泄题</b>，误判为讲解的代价仅是<b>不够具体</b>。
 * 两类错误代价不对称，因此<b>宁可过判为讲解</b>（更安全），
 * 并要求人工抽检（见 RAG-D08 §5）。
 */
public class IngestionPipeline {

    private final DocumentParserPort parserPort;

    /**
     * @param parserPort 解析端口
     */
    public IngestionPipeline(DocumentParserPort parserPort) {
        if (parserPort == null) {
            throw new IllegalArgumentException("parserPort 不能为空");
        }
        this.parserPort = parserPort;
    }

    /**
     * 解析并切分文档。
     *
     * @param documentId 文档 ID
     * @param documentTitle 文档标题
     * @param edition 版本坐标
     * @param content 文档内容
     * @param knowledgePoints 知识点映射表（用于挂接知识点）
     * @return 切分后的片段
     */
    public @NonNull List<Chunk> ingest(@NonNull String documentId,
                                       @NonNull String documentTitle,
                                       @NonNull EditionCoordinate edition,
                                       @NonNull byte[] content,
                                       @NonNull List<KnowledgePoint> knowledgePoints) {
        ParsedDocument parsed = parserPort.parse(documentId, content);
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder sectionPath = new StringBuilder();
        String currentExerciseNo = null;

        for (LayoutBlock block : parsed.blocks()) {
            // ① 标题块推进章节路径
            if (block.type().isHeading()) {
                updateSectionPath(sectionPath, block);
                currentExerciseNo = null;
                continue;
            }
            // ② 噪声块直接丢弃
            if (block.type().isNoise()) {
                continue;
            }
            // ③ 有效题号＝块自身题号，否则继承上文最近一次出现的题号
            //    ⚠️ 必须**先**算出有效题号再判角色：
            //    答案册里的答案块通常**不带自己的题号**，
            //    它归属的是上文那道题。若只看块自身题号，
            //    「习题的答案」会被判成「例题解答」甚至「讲解」——
            //    后者会让标准答案在讲解模式下被召回（泄题）。
            String effectiveExerciseNo =
                    block.exerciseNo() != null ? block.exerciseNo() : currentExerciseNo;
            // ④ 答案区判定——先于角色推断，且优先
            ChunkRole role = resolveRole(block, effectiveExerciseNo);
            if (role == ChunkRole.BOILERPLATE) {
                continue;
            }
            // ⑤ 题号推进
            if (block.exerciseNo() != null) {
                currentExerciseNo = block.exerciseNo();
            }
            // ⑥ 生成片段
            //    ⚠️ 章节路径取值见 resolveSectionPath：标题上下文优先，
            //    无标题上下文时回退到解析器给出的路径。
            Chunk.Locator locator = new Chunk.Locator(
                    block.pageNumber(),
                    resolveSectionPath(sectionPath, block),
                    currentExerciseNo,
                    block.charStart(),
                    block.charEnd());
            List<String> kpIds = matchKnowledgePoints(block.text(), knowledgePoints);
            chunks.add(new Chunk(
                    chunkId(documentId, block),
                    documentId,
                    edition,
                    role,
                    block.text().trim(),
                    locator,
                    kpIds,
                    currentExerciseNo,
                    contentHash(block.text())));
        }
        return List.copyOf(chunks);
    }

    /**
     * 角色判定（RAG-D04 §3.1 判定表的实现）。
     *
     * <p><b>⚠️ 判定顺序即安全顺序</b>：本方法产出的角色直接决定
     * {@code RetrievalPolicy.applyRoleFilter} 会不会把该片段放进讲解模式的上下文。
     * {@link ChunkRole#EXPLANATION} <b>不是</b>答案类角色 → 它<b>会</b>被召回 →
     * 一旦答案区的内容被判成 EXPLANATION，<b>标准答案就泄给了还没作答的学生</b>。
     * 因此本表的每一条都<b>取严</b>：能判成答案类的一律判成答案类。
     *
     * <table border="1">
     *   <caption>判定表（RAG-D04 §3.1）</caption>
     *   <tr><th>版面事实</th><th>判定</th></tr>
     *   <tr><td>公式/表格/图注 且在答案区</td><td>{@code EXERCISE_ANSWER}（行 5，最严）</td></tr>
     *   <tr><td>在答案区 且有题号（含继承）</td><td>{@code EXERCISE_ANSWER}（行 1）</td></tr>
     *   <tr><td>在答案区 且无题号</td><td>{@code EXAMPLE_SOLUTION}（行 2）</td></tr>
     *   <tr><td>块类型 EXERCISE</td><td>{@code EXERCISE}（行 3）</td></tr>
     *   <tr><td>块类型 ANSWER</td><td>{@code EXERCISE_ANSWER}（行 4，取严）</td></tr>
     *   <tr><td>其余</td><td>{@code EXPLANATION}（行 6）</td></tr>
     * </table>
     *
     * <p>⚠️ <b>行 2 的「无题号」曾经被实现成 {@code EXPLANATION}</b>——
     * 那不是「不够具体」，而是<b>把答案判成了讲解</b>：
     * 讲解模式下该片段会被原样送进 prompt → <b>泄题</b>。
     * 本方法的判据只看<b>版面事实</b>（答案区标识 + 题号），不看内容。
     *
     * @param block 版面块
     * @param effectiveExerciseNo 有效题号（块自身题号，否则继承上文最近题号）
     * @return 片段角色
     */
    private ChunkRole resolveRole(LayoutBlock block, String effectiveExerciseNo) {
        boolean answerZone = block.inAnswerZone();
        switch (block.type()) {
            case FORMULA:
            case TABLE:
            case FIGURE:
                // ⚠️ 公式/表格/图注：若位于答案区，仍属答案承载内容（行 5）。
                //    取 EXERCISE_ANSWER（而非 EXAMPLE_SOLUTION）——后者在 SOLVE 模式下
                //    是允许的，标准答案在解题模式下被召回同样是泄题。
                return answerZone ? ChunkRole.EXERCISE_ANSWER : ChunkRole.EXPLANATION;
            case HEADING:
            case BOILERPLATE:
                return ChunkRole.BOILERPLATE;
            case EXERCISE:
                return ChunkRole.EXERCISE;
            case ANSWER:
                // 行 4：块类型已由解析器断言「这是答案内容」，无题号时无法判定
                //        是例题解答还是习题答案 → 取严判为 EXERCISE_ANSWER。
                return ChunkRole.EXERCISE_ANSWER;
            case PARAGRAPH:
            case LIST_ITEM:
            default:
                if (answerZone) {
                    // 行 1 / 行 2
                    return effectiveExerciseNo != null
                            ? ChunkRole.EXERCISE_ANSWER
                            : ChunkRole.EXAMPLE_SOLUTION;
                }
                return ChunkRole.EXPLANATION;
        }
    }

    /**
     * 章节路径取值（RAG-D04 §5.2 / §5.3）。
     *
     * <p><b>⚠️ 两个来源，一个权威</b>：
     * <ol>
     *   <li><b>标题上下文</b>（流水线按 §5.3 的层级截断规则累积）——
     *       这是<b>文档规定的规范机制</b>：只有它实现了「按层级截断而非追加」，
     *       追加会产出「第3章 &gt; 3.1 &gt; 3.1.1 &gt; 3.2」这种错误路径，
     *       在引用展示时学生会找不到对应位置。</li>
     *   <li><b>解析器给出的 {@code LayoutBlock.sectionPath}</b>——
     *       解析器是版面事实的权威，但它的路径<b>不经过层级截断</b>。</li>
     * </ol>
     *
     * <p><b>取值规则</b>：有标题上下文时用累积路径（规范机制优先，
     * 它保证路径与本文档的标题块自洽）；<b>没有标题上下文</b>时
     * 累积路径无从产生，回退到解析器路径——此时解析器路径是唯一可用的版面事实，
     * 直接丢弃它会让定位信息退化为空串，<b>引用无法核对</b>（RAG-D04 §5.2）。
     *
     * @param accumulated 流水线累积的标题路径
     * @param block 版面块
     * @return 最终章节路径
     */
    private String resolveSectionPath(StringBuilder accumulated, LayoutBlock block) {
        String fromHeadings = accumulated.toString();
        if (!fromHeadings.isBlank()) {
            return fromHeadings;
        }
        String fromParser = block.sectionPath();
        return fromParser == null ? "" : fromParser;
    }

    /**
     * 章节路径推进。
     *
     * <p>⚠️ 标题层级变化时<b>截断</b>路径而非追加——
     * 否则路径会变成「第 3 章 > 3.1 > 3.1.1 > 3.1.1.1」的累积错误。
     */
    private void updateSectionPath(StringBuilder path, LayoutBlock block) {
        int level = Math.max(1, block.headingLevel());
        String[] parts = path.toString().split(" > ");
        int keep = Math.min(parts.length, level - 1);
        StringBuilder rebuilt = new StringBuilder();
        for (int i = 0; i < keep; i++) {
            if (!parts[i].isBlank()) {
                if (rebuilt.length() > 0) {
                    rebuilt.append(" > ");
                }
                rebuilt.append(parts[i]);
            }
        }
        if (rebuilt.length() > 0) {
            rebuilt.append(" > ");
        }
        rebuilt.append(block.text().trim());
        path.setLength(0);
        path.append(rebuilt);
    }

    /**
     * 知识点匹配。
     *
     * <p>⚠️ 当前实现为<b>别名/规范名的确定性匹配</b>，不做模型抽取。
     * 理由：知识点挂错的后果是「检索到错误知识点的内容」，
     * 而模型抽取的误挂率不可控且不可复现。
     * 模型抽取作为<b>辅助信号</b>引入时，必须与确定性匹配<b>并存</b>，
     * 且其结果标记为「低置信」，不得单独决定挂接（RAG-D04 §3.4）。
     */
    private List<String> matchKnowledgePoints(String text, List<KnowledgePoint> knowledgePoints) {
        String normalized = KnowledgePoint.normalize(text);
        List<String> matched = new ArrayList<>();
        for (KnowledgePoint kp : knowledgePoints) {
            if (kp.matches(normalized)) {
                matched.add(kp.id());
            }
        }
        return matched;
    }

    private String chunkId(String documentId, LayoutBlock block) {
        return documentId + ":" + block.pageNumber() + ":" + block.charStart();
    }

    private String contentHash(String text) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // ⚠️ SHA-256 是 JDK 必备算法；若不可用说明运行环境异常，不应静默降级。
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
