package cn.studykid.rag.premise.role;

import java.util.regex.Pattern;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.core.chunk.ChunkRole;
import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.LayoutBlock;

/**
 * 角色判定规则（RAG-D26 阶段 0.3：从 {@code IngestionPipeline.resolveRole} 原样搬迁）。
 *
 * <p><b>⚠️ 本类是搬运不是重写，判定语义必须与既有管道逐条等价</b>。
 * 等价性由既有 {@code IngestionPipelineTest} 的全部角色用例
 * + 新增 {@code BlockRoleResolverParityTest} 共同守护——
 * 这些测试<b>一条不改地通过</b>，就是「搬迁没有改坏」的证明。
 *
 * <p><b>判定顺序即安全顺序（RAG-D04 §3.1）</b>：
 * 本判定表产出的角色直接决定检索期泄题闸门（{@code RetrievalPolicy.applyRoleFilter}）
 * 会不会把片段放进讲解模式的上下文。
 * {@code EXPLANATION} 不是答案类角色 → 会被召回 →
 * 一旦答案区内容被判成 EXPLANATION，标准答案就泄给了还没作答的学生。
 * 因此每一条都<b>取严</b>：能判成答案类的一律判成答案类。
 *
 * <table border="1">
 *   <caption>判定表（RAG-D04 §3.1，与既有管道同表）</caption>
 *   <tr><th>版面事实</th><th>判定</th></tr>
 *   <tr><td>公式/表格/图注 且在答案区</td><td>{@code EXERCISE_ANSWER}（行 5，最严）</td></tr>
 *   <tr><td>在答案区 且有题号（含继承）</td><td>{@code EXERCISE_ANSWER}（行 1）</td></tr>
 *   <tr><td>在答案区 且无题号</td><td>{@code EXAMPLE_SOLUTION}（行 2）</td></tr>
 *   <tr><td>块类型 EXERCISE</td><td>{@code EXERCISE}（行 3）</td></tr>
 *   <tr><td>块类型 ANSWER</td><td>{@code EXERCISE_ANSWER}（行 4，取严）</td></tr>
 *   <tr><td>块首确定性前缀（解：/ 解答：/ 答：/ 答案：/ Key:/ Answer:）且不在答案区</td>
 *       <td>{@code EXERCISE_ANSWER}/{@code EXAMPLE_SOLUTION}（行 7，学生用书无答案附录补丁）</td></tr>
 *   <tr><td>其余</td><td>{@code EXPLANATION}（行 6）</td></tr>
 * </table>
 *
 * <p><b>⚠️ 判定基于版面事实，不基于内容语义</b>：
 * {@code inAnswerZone} 与 {@code exerciseNo} 来自解析器的版面识别。
 * 用模型判角色会把「讲评」误判为「答案」——误判为答案的代价是<b>泄题</b>，
 * 误判为讲解的代价仅是「不够具体」，两类错误代价不对称，宁可过判为讲解类安全方向。
 */
public final class BlockRoleResolver {

    private BlockRoleResolver() {
    }

    /**
     * 解答引导语前缀（与既有管道的 {@code SOLUTION_PREFIX} 同一正则）。
     *
     * <p>⚠️ 仅匹配<b>块首</b>的强信号，<b>绝不</b>扫全文关键词——
     * 历史事故：按全文关键词（如 "answers"）判答案区，曾把 89% 的题干误判成答案区。
     * 「解：」是确定性解答引导语，不会出现在题干指令里；
     * 「Answer the questions」的 Answer 后跟空格 + 动词而非冒号，故不命中。
     * 「解释：/ 解读：/ 解析式：」等以「解」开头但非解答引导语的，
     * 也因后接非冒号而不命中。
     */
    private static final Pattern SOLUTION_PREFIX = Pattern.compile(
            "^\\s*(?:解|解答|解之|答|答案|答曰|求解结果|Key|Answer|Answers?)\\s*[:：]"
                    // ⚠️ 答案区标题的块首形态（取严补丁，RAG-D26 防漂移守卫抓出的缺口）：
                    //    「Answer Key」「参考答案」是答案区标题，但不满脚冒号前缀
                    //    （Answer 后是空格+Key）。HybridChunker 的 merge_peers 可能把
                    //    答案区标题合并进 chunk 块首——文本级兜底不认它就漏判（泄题方向）。
                    //    与 LayoutPredicates.isAnswerZoneHeading 的短语保持同集。
                    + "|^\\s*(?:Answer\\s+Key|Answers?\\s+to|Answer\\s+Sheet|参考答案|答案与解析|练习答案|习题答案|答案)");

    /**
     * 角色判定（与既有 {@code resolveRole} 逐条等价）。
     *
     * @param block               版面块
     * @param effectiveExerciseNo 有效题号（块自身题号，否则继承上文最近题号）
     * @return 片段角色
     */
    public static @NonNull ChunkRole resolveRole(LayoutBlock block, String effectiveExerciseNo) {
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
                // ④' 内容兜底（学生用书无答案附录时的关键补丁，RAG-D04 §3.1 行 7）。
                ChunkRole contentRole = resolveAnswerBearingByContent(block.text(), effectiveExerciseNo);
                if (contentRole != null) {
                    return contentRole;
                }
                return ChunkRole.EXPLANATION;
        }
    }

    /**
     * 内容兜底：识别「块首确定性前缀」的解答 / 答案
     * （与既有 {@code resolveAnswerBearingByContent} 等价）。
     *
     * @param text                块文本
     * @param effectiveExerciseNo 有效题号（块自身题号，否则继承上文题号）
     * @return 命中时返回答案承载角色；否则返回 null（交回 EXPLANATION 兜底）
     */
    public static ChunkRole resolveAnswerBearingByContent(String text, String effectiveExerciseNo) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if (!SOLUTION_PREFIX.matcher(text).find()) {
            return null;
        }
        // 沿用行 1/行 2：有题号归属习题答案，无题号归属例题解答（都是答案承载内容）。
        return effectiveExerciseNo != null ? ChunkRole.EXERCISE_ANSWER : ChunkRole.EXAMPLE_SOLUTION;
    }

    /**
     * 提取块首命中的解答前缀（无则 null）
     * （与既有 {@code matchedSolutionPrefix} 等价）。
     *
     * <p>⚠️ 「判定」与「记录」必须使用<b>同一份规则</b>：
     * 若采集时另写一个正则，判定改了采集没改，校对页就会展示一个
     * 与真实判定依据不符的前缀——那比不显示更糟。
     *
     * @param text 块文本
     * @return 命中的前缀原文（如「解：」）；未命中或空文本为 null
     */
    public static String matchedSolutionPrefix(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        var m = SOLUTION_PREFIX.matcher(text);
        return m.find() ? m.group().trim() : null;
    }

    /**
     * 有效题号计算（与既有管道的「先算题号再判角色」顺序一致）：
     * 块自身题号优先，否则继承上文最近一次出现的题号。
     *
     * <p>⚠️ 答案册里的答案块通常<b>不带自己的题号</b>，它归属的是上文那道题。
     * 若只看块自身题号，「习题的答案」会被判成「例题解答」甚至「讲解」——
     * 后者会让标准答案在讲解模式下被召回（泄题）。
     *
     * @param block               当前块
     * @param inheritedExerciseNo 上文继承的题号（调用方跨块维护）
     * @return 有效题号（可能为 null）
     */
    public static String effectiveExerciseNo(LayoutBlock block, String inheritedExerciseNo) {
        String own = block.exerciseNo();
        return own != null ? own : inheritedExerciseNo;
    }

    /**
     * 题号抽取（premise 侧的规则副本，与 infrastructure 的
     * {@code LayoutPredicates.extractExerciseNo} 同正则）。
     *
     * <p>⚠️ 为什么要副本：{@code BlockRoleResolver} 住在 premise（零框架依赖、
     * 不依赖 infrastructure），而题号形态判定是版面规则——HybridChunker 的
     * 角色回填需要在 chunk 文本级兜底抽题号（合并可能把题干并进 chunk）。
     * 两处正则必须同步修改（测试两侧各有一条相同用例守护）。
     *
     * @param text 文本
     * @return 题号；无则 null
     */
    public static String extractExerciseNo(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        java.util.regex.Matcher m = EXERCISE_NO.matcher(text);
        if (m.find()) {
            for (int g = 1; g <= 4; g++) {
                if (m.group(g) != null) {
                    return m.group(g);
                }
            }
        }
        return null;
    }

    /** 题号形态（沪教版实测；与 {@code LayoutPredicates} 同正则，见上注释）。 */
    private static final java.util.regex.Pattern EXERCISE_NO = java.util.regex.Pattern.compile(
            "^\\s*(?:[(（]?\\s*(\\d{1,3})\\s*[.、)）]"
                    + "|[(（]\\s*(\\d{1,3})\\s*[)）]"
                    + "|Exercise\\s+(\\d{1,3})"
                    + "|(\\d{1,2})\\s+(?=[A-Za-z]))");

    /**
     * @return 判定表使用的版面事实快照（供 {@code LayoutFacts} 采集，与判定同源）
     */
    public static @NonNull String solutionPrefixPattern() {
        return SOLUTION_PREFIX.pattern();
    }
}
