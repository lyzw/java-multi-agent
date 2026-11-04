package cn.studykid.rag.api.retrieval;

import java.util.List;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.retrieval.RetrievalResult;
import cn.studykid.rag.core.retrieval.RetrievalResult.RetrievalChannel;
import cn.studykid.rag.core.retrieval.RetrievalResult.ScoredChunk;

/**
 * 检索结果 DTO（RAG-D00 §2.9）。
 *
 * <p>⚠️ 每个命中携带「为什么被召回」的解释（通道 + 命中原因），
 * 使召回错误可审计（RAG-D00 D-R11）。{@link HitDto#locator} 即面向学生的引用文本，
 * 使回答可回溯到「哪一册、第几页、第几题」。
 */
public record RetrievalResultDto(
        List<HitDto> hits,
        boolean versionMismatchDetected,
        boolean answerContentSuppressed,
        String primaryEdition,
        /**
         * 过滤漏斗（RAG-D12 靶点 D）。
         *
         * <p><b>⚠️ 为什么要对外暴露</b>：管理台「召回解释（质检）」页的诊断问题是
         * 「这次没结果，是语料里没有、还是被过滤光了」——只给命中数回答不了。
         * 漏斗把每层剩余量摊开，规则效果才可验证（RAG-D05 §7）。
         */
        FunnelDto funnel,
        /**
         * 本次检索实际生效的分数门禁阈值（RAG-D15 §六 待办 #3）。
         *
         * <p>⚠️ 由服务端随响应下发，前端**不得**再硬编码 0.38：
         * 阈值可配置（{@code RAG_RETRIEVAL_MIN_SCORE}），
         * 写死会让界面刻度线与真实门禁不是同一个数——
         * 出现"明明过了刻度线却被判依据不足"的矛盾界面。
         */
        double minScore
) {

    /** 向后兼容构造（无漏斗）。 */
    public RetrievalResultDto(List<HitDto> hits,
                              boolean versionMismatchDetected,
                              boolean answerContentSuppressed,
                              String primaryEdition) {
        this(hits, versionMismatchDetected, answerContentSuppressed, primaryEdition, null, 0.0);
    }

    /** 兼容构造（有漏斗、无阈值）。 */
    public RetrievalResultDto(List<HitDto> hits,
                              boolean versionMismatchDetected,
                              boolean answerContentSuppressed,
                              String primaryEdition,
                              FunnelDto funnel) {
        this(hits, versionMismatchDetected, answerContentSuppressed, primaryEdition, funnel, 0.0);
    }

    public static RetrievalResultDto from(RetrievalResult result) {
        List<HitDto> hits = result.chunks().stream().map(HitDto::from).toList();
        return new RetrievalResultDto(hits, result.versionMismatchDetected(),
                result.answerContentSuppressed(), result.primaryEdition().display(),
                FunnelDto.from(result.funnel()), result.minScore());
    }

    /**
     * 过滤漏斗视图。
     *
     * @param candidates       多路召回原始候选数
     * @param afterVersion     版本硬过滤后剩余
     * @param afterRole        角色硬过滤后剩余（泄题闸门）
     * @param afterGate        分数门禁后剩余
     * @param returned         实际返回条数（topK 截断后）
     * @param droppedByVersion 版本过滤剔除数
     * @param droppedByRole    角色过滤剔除数
     * @param droppedByGate    门禁剔除数
     * @param dominantDrop     主因归因：NONE / ROLE / VERSION / GATE
     */
    public record FunnelDto(
            int candidates,
            int afterVersion,
            int afterRole,
            int afterGate,
            int returned,
            int droppedByVersion,
            int droppedByRole,
            int droppedByGate,
            String dominantDrop
    ) {

        static FunnelDto from(RetrievalResult.RetrievalFunnel f) {
            return new FunnelDto(
                    f.candidates(), f.afterVersion(), f.afterRole(), f.afterGate(), f.returned(),
                    f.droppedByVersion(), f.droppedByRole(), f.droppedByGate(), f.dominantDrop());
        }
    }

    /**
     * 单个召回片段（含可展示引用与召回解释）。
     *
     * @param chunkId 片段 ID
     * @param text 片段正文
     * @param role 片段角色（决定是否在讲解模式下被召回）
     * @param score 相关性分（0~1，低于门禁的已被编排层过滤）。
     *              ⚠️ 这是<b>相关性</b>不是「排名」：稠密通道是余弦相似度、
     *              词法通道是 ts_rank。排序另由 RRF 融合分决定（内部字段，不对外暴露）。
     * @param edition 版本展示名
     * @param locator 面向学生的引用文本（册/章/页/题）
     * @param channel 召回通道（稠密/词法/知识点聚合…）
     * @param reason 命中原因
     */
    public record HitDto(
            String chunkId, String text, String role, double score,
            String edition, String locator, String channel, String reason
    ) {

        static HitDto from(ScoredChunk scored) {
            Chunk c = scored.chunk();
            return new HitDto(
                    c.id(), c.text(), c.role().name(), scored.score(),
                    c.edition().display(), c.locator().display(),
                    scored.channel().name(), scored.reason().name());
        }
    }
}
