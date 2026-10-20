package cn.studykid.rag.core.retrieval;

import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.chunk.ChunkRole;
import cn.studykid.rag.core.retrieval.RetrievalRequest.RetrievalMode;
import cn.studykid.rag.core.version.EditionScope;

/**
 * 检索期过滤与编排规则（RAG-D00 §2.9 决策 D-R11 / D-R12）。
 *
 * <p><b>⚠️ 这个类是本项目「教辅特殊性」落地的关键</b>：
 * 三类教辅特有问题各自对应一条<b>检索期硬过滤</b>：
 * <table border="1">
 *   <caption>教辅特殊性 → 过滤规则</caption>
 *   <tr><th>教辅特性</th><th>过滤规则</th><th>失效后果</th></tr>
 *   <tr><td>版本差异</td><td>{@link #applyEditionFilter}</td><td>串版本：页码对不上</td></tr>
 *   <tr><td>习题与答案分离</td><td>{@link #applyRoleFilter}</td><td>泄题</td></tr>
 *   <tr><td>知识点碎片化</td><td>{@link #applyKnowledgePointCompletion}</td><td>只讲不练 / 只练不讲</td></tr>
 * </table>
 *
 * <p><b>⚠️ 为什么过滤必须在检索期而非生成期</b>：
 * 一旦答案册内容进入了 prompt，即使模型「自律地不提」，
 * 也已经构成<b>上下文泄露</b>——模型可能以「暗示」形式泄漏
 * （如「由上一步可得该方程的解为 x=4」）。
 * 生成期的 prompt 约束不可靠；<b>不进 prompt</b>才是可靠边界。
 */
public final class RetrievalPolicy {

    private RetrievalPolicy() {
    }

    /**
     * 版本过滤。
     *
     * @param chunks 候选片段
     * @param scope 版本范围
     * @return 保留的片段
     */
    public static @NonNull List<Chunk> applyEditionFilter(@NonNull List<Chunk> chunks,
                                                          @NonNull EditionScope scope) {
        return chunks.stream()
                .filter(c -> scope.admits(c.edition()))
                .toList();
    }

    /**
     * 角色过滤——防止泄题的第一道闸门。
     *
     * @param chunks 候选片段
     * @param mode 检索模式
     * @return 保留的片段
     *
     * <p><b>⚠️ 规则表</b>：
     * <ul>
     *   <li>{@code EXPLAIN}：排除 {@link ChunkRole#EXAMPLE_SOLUTION} 与
     *       {@link ChunkRole#EXERCISE_ANSWER}；<b>保留</b>
     *       {@link ChunkRole#EXERCISE_HINT}（提示不含答案）。</li>
     *   <li>{@code SOLVE}：排除 {@link ChunkRole#EXERCISE_ANSWER}；
     *       允许 {@code EXAMPLE_SOLUTION}（标准解法是学习材料，
     *       且它支撑的是<b>示范</b>而非本题答案）。</li>
     *   <li>{@code VERIFY}：全部保留（核对场景本就需要标准答案）。</li>
     *   <li>{@code OVERVIEW}：排除答案类，保留题目与例题。</li>
     * </ul>
     */
    public static @NonNull List<Chunk> applyRoleFilter(@NonNull List<Chunk> chunks,
                                                       @NonNull RetrievalMode mode) {
        return chunks.stream()
                .filter(c -> allowsRole(c.role(), mode))
                .toList();
    }

    /**
     * 判断某角色在给定模式下是否允许进入结果集。
     *
     * <p>⚠️ 这是<b>唯一的角色规则来源</b>：{@link #applyRoleFilter} 与
     * 编排层都调用它，避免两处规则漂移——规则漂移是「泄题闸门偶尔失效」
     * 最常见的成因（改了一处忘了另一处）。
     *
     * @param role 片段角色
     * @param mode 检索模式
     * @return 是否允许
     */
    public static boolean allowsRole(@NonNull ChunkRole role, @NonNull RetrievalMode mode) {
        return switch (mode) {
            case EXPLAIN, OVERVIEW -> !role.isAnswerBearing();
            // ⚠️ SOLVE 允许例题解答（标准解法是学习材料），
            //    但禁止习题标准答案（那等于直接给本题答案）。
            case SOLVE -> role != ChunkRole.EXERCISE_ANSWER;
            case VERIFY -> true;
        };
    }

    /**
     * 知识点完整性补全——解决碎片化。
     *
     * <p><b>⚠️ 为什么需要它</b>：一次检索通常只命中「讲解片段」，
     * 漏掉「配套习题」。但学生问「二次函数的顶点」时，
     * 一个<b>只有定义没有练习</b>的答案是残缺的。
     *
     * <p>因此：若请求已指定知识点，则把该知识点下<b>尚未被召回</b>的
     * 其他角色片段（尤其是 {@link ChunkRole#EXERCISE} 与
     * {@link ChunkRole#EXERCISE_HINT}）补进来，
     * 使「讲解 + 练习 + 提示」成组出现。
     *
     * @param chunks 已召回片段
     * @param siblings 同一知识点下的全部片段
     * @param request 检索请求
     * @return 补全后的片段
     */
    public static @NonNull List<Chunk> applyKnowledgePointCompletion(
            @NonNull List<Chunk> chunks,
            @NonNull List<Chunk> siblings,
            @NonNull RetrievalRequest request) {

        if (!request.knowledgePointDirected()) {
            return chunks;
        }
        // ⚠️ 补全过程同样受角色过滤约束：补全不能绕过泄题闸门。
        List<Chunk> allowed = applyRoleFilter(siblings, request.mode());
        List<String> present = chunks.stream().map(Chunk::id).toList();
        List<Chunk> additions = allowed.stream()
                .filter(c -> !present.contains(c.id()))
                .toList();
        if (additions.isEmpty()) {
            return chunks;
        }
        List<Chunk> merged = new java.util.ArrayList<>(chunks);
        merged.addAll(additions);
        return List.copyOf(merged);
    }

    /**
     * 判断本次检索是否存在版本串扰。
     *
     * @param chunks 过滤后的片段
     * @param scope 版本范围
     * @return 是否存在非首选版本的内容
     */
    public static boolean detectVersionMismatch(@NonNull List<Chunk> chunks,
                                                 @NonNull EditionScope scope) {
        return chunks.stream()
                .anyMatch(c -> scope.requiresVersionDisclaimer(c.edition()));
    }

    /**
     * 判断本次检索是否因模式过滤丢弃过答案类内容。
     *
     * <p>用于审计：说明「本次未给出标准答案」是<b>策略主动为之</b>，
     * 而非「语料里没有」。
     */
    public static boolean detectSuppressedAnswers(@NonNull List<Chunk> candidates,
                                                  @NonNull RetrievalMode mode) {
        long total = candidates.size();
        long kept = applyRoleFilter(candidates, mode).size();
        return total > kept;
    }
}
