package cn.studykid.rag.core.generation;

import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.core.retrieval.RetrievalRequest.RetrievalMode;
import cn.studykid.rag.core.version.EditionScope;

/**
 * 生成请求（RAG-D00 §2.7 决策 D-R09 的入口契约）。
 *
 * <p><b>⚠️ 与检索请求的根本区别：依据由调用方指定，而非本端重新检索</b>。
 * 调用流程是「先 {@code POST /api/v1/retrieval} 拿到命中列表，
 * 再把其中若干 {@code hitIds} 交给本端生成」。这样做有三个理由：
 * <ol>
 *   <li><b>可解释</b>：学生能看到「这段话引用了哪几处语料」，
 *       因为依据就是他刚才看到的那几条；</li>
 *   <li><b>可审计</b>：依据集合是入参，落库即可复现，
 *       不需要反推「模型当时检索到了什么」；</li>
 *   <li><b>不会二次幻觉</b>：本端不自行决定取哪些语料，
 *       也就不存在「取错了还硬答」的空间。</li>
 * </ol>
 *
 * <p><b>⚠️ 因此 hitIds 是必须逐条校验的入参，不是可选的「提示」</b>：
 * 不存在、不属于本租户、或不被当前模式允许引用的 ID，
 * 都必须<b>显式失败</b>——静默丢弃会让调用方以为依据已被采用。
 *
 * @param query 学生的原始问题（用于组织讲解结构，不用于检索）
 * @param mode 检索模式（决定哪些角色可被引用——泄题闸门在生成期同样生效）
 * @param editionScope 版本范围（生成期版本过滤与检索期同规则）
 * @param hitIds 调用方指定的依据片段 ID（来自前一次检索的命中列表）
 */
public record GenerationRequest(
        @NonNull String query,
        @NonNull RetrievalMode mode,
        @NonNull EditionScope editionScope,
        @NonNull List<String> hitIds
) {

    /** 单次生成允许的最大依据条数（与检索 topK 上限一致，防止无界查询）。 */
    public static final int MAX_HIT_IDS = 50;

    public GenerationRequest {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        if (mode == null) {
            throw new IllegalArgumentException("mode 不能为空");
        }
        if (editionScope == null) {
            throw new IllegalArgumentException("editionScope 不能为空");
        }
        if (hitIds == null) {
            throw new IllegalArgumentException("hitIds 不能为空");
        }
        if (hitIds.size() > MAX_HIT_IDS) {
            throw new IllegalArgumentException(
                    "hitIds 超过上限 " + MAX_HIT_IDS + "（当前 " + hitIds.size() + "）");
        }
        // ⚠️ 去重且保序：重复 ID 会让同一段原文在讲解里出现多次。
        hitIds = hitIds.stream().distinct().toList();
    }

    /**
     * @return 是否指定了任何依据片段。
     */
    public boolean hasHits() {
        return !hitIds.isEmpty();
    }
}
