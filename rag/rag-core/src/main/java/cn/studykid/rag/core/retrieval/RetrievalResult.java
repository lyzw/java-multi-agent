package cn.studykid.rag.core.retrieval;

import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.knowledge.KnowledgePoint;
import cn.studykid.rag.core.version.EditionCoordinate;

/**
 * 检索结果（RAG-D00 §2.9 决策 D-R11）。
 *
 * <p><b>⚠️ 检索结果必须自带「为什么被召回」的解释</b>。
 * 教辅场景的召回错误（串版本、串知识点、混入答案）如果不可解释，
 * 就无法在评测里归因，也无法在上线后被定位。
 *
 * <p>因此 {@link ScoredChunk} 除分数外，还携带
 * {@link RetrievalChannel}（召回通道）与
 * {@link HitReason}（命中原因）——二者共同构成可审计的召回解释。
 */
public record RetrievalResult(
        @NonNull List<ScoredChunk> chunks,
        @NonNull List<KnowledgePoint> knowledgePoints,
        @NonNull EditionCoordinate primaryEdition,
        @NonNull boolean versionMismatchDetected,
        @NonNull boolean answerContentSuppressed,
        /**
         * 过滤漏斗计数（RAG-D05 §7 / RAG-D12 靶点 D）。
         *
         * <p><b>⚠️ 为什么检索结果要带漏斗</b>：只给最终命中数，使用者无法区分
         * 「语料里确实没有」与「被过滤光了」——两者处置完全不同
         * （前者补语料、后者调策略）。把每层剩余量显式带出，规则效果才可验证。
         *
         * <p>缺省为空漏斗，保证既有调用方与测试不受影响。
         */
        @NonNull RetrievalFunnel funnel,
        /**
         * 本次检索实际生效的**分数门禁阈值**（RAG-D15 §六 待办 #3）。
         *
         * <p><b>⚠️ 为什么由结果携带而不是调用方自己读配置</b>：
         * 阈值来自 {@code rag.retrieval.min-score}，由编排器在构造期定格。
         * 若界面另读一份配置，两处一旦不一致（改配置未重启 / 多实例不同值），
         * 界面画的刻度线就与实际门禁**不是同一个数**——
         * 用户会看到一个"明明过了刻度线却被判依据不足"的矛盾界面。
         * 让结果把真实阈值带出来，是消除这类漂移的唯一可靠做法。
         */
        double minScore
) {

    /** 向后兼容构造（无漏斗、无阈值）。 */
    public RetrievalResult(@NonNull List<ScoredChunk> chunks,
                           @NonNull List<KnowledgePoint> knowledgePoints,
                           @NonNull EditionCoordinate primaryEdition,
                           @NonNull boolean versionMismatchDetected,
                           @NonNull boolean answerContentSuppressed) {
        this(chunks, knowledgePoints, primaryEdition,
                versionMismatchDetected, answerContentSuppressed,
                RetrievalFunnel.empty(chunks == null ? 0 : chunks.size()), 0.0);
    }

    /** 兼容构造（有漏斗、无阈值）。 */
    public RetrievalResult(@NonNull List<ScoredChunk> chunks,
                           @NonNull List<KnowledgePoint> knowledgePoints,
                           @NonNull EditionCoordinate primaryEdition,
                           @NonNull boolean versionMismatchDetected,
                           @NonNull boolean answerContentSuppressed,
                           @NonNull RetrievalFunnel funnel) {
        this(chunks, knowledgePoints, primaryEdition, versionMismatchDetected,
                answerContentSuppressed, funnel, 0.0);
    }

    public RetrievalResult {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        knowledgePoints = knowledgePoints == null ? List.of() : List.copyOf(knowledgePoints);
        if (primaryEdition == null) {
            throw new IllegalArgumentException("primaryEdition 不能为空");
        }
        if (funnel == null) {
            funnel = RetrievalFunnel.empty(chunks.size());
        }
    }

    /**
     * 过滤漏斗（每层<b>剩余</b>数量）。
     *
     * <p><b>⚠️ 语义是「剩余」不是「剔除」</b>：{@code afterRole} 表示角色过滤后还剩多少条，
     * 不是"被滤掉多少条"。用剩余量是因为界面要画逐层收窄的漏斗，
     * 且缺失某层（如未走知识点补全）时可直接沿用上一层值。
     *
     * @param candidates   ① 多路召回的原始候选数（含知识点补全引入的片段）
     * @param afterVersion ② 版本硬过滤后剩余
     * @param afterRole    ③ 角色硬过滤后剩余（泄题闸门）
     * @param afterGate    ④ 分数门禁后剩余（可交付的依据数）
     * @param returned     ⑤ 实际返回条数（被 topK 截断后）
     */
    public record RetrievalFunnel(
            int candidates,
            int afterVersion,
            int afterRole,
            int afterGate,
            int returned
    ) {

        public RetrievalFunnel {
            // ⚠️ 漏斗必须单调不增：某层比上一层还多，说明计数点写错了。
            //    与其静默传播一个自相矛盾的漏斗（会让"诊断"本身失真），不如当场拒绝。
            if (afterVersion > candidates || afterRole > afterVersion
                    || afterGate > afterRole || returned > afterGate) {
                throw new IllegalArgumentException(
                        "漏斗计数必须单调不增，实际：candidates=" + candidates
                                + " afterVersion=" + afterVersion + " afterRole=" + afterRole
                                + " afterGate=" + afterGate + " returned=" + returned);
            }
        }

        /** 空漏斗（未采集 / 结果为空）。 */
        public static @NonNull RetrievalFunnel empty(int size) {
            return new RetrievalFunnel(size, size, size, size, size);
        }

        /** @return 版本过滤剔除条数 */
        public int droppedByVersion() {
            return candidates - afterVersion;
        }

        /** @return 角色过滤剔除条数 */
        public int droppedByRole() {
            return afterVersion - afterRole;
        }

        /** @return 门禁剔除条数 */
        public int droppedByGate() {
            return afterRole - afterGate;
        }

        /**
         * 主因归因：哪一层剔除最多。
         *
         * <p>这是「召回不足 vs 过滤过严」这条诊断问题的**结论字段**——
         * 界面不必自己比较三个差值，避免各处算法漂移。
         *
         * @return NONE / ROLE / VERSION / GATE
         */
        public @NonNull String dominantDrop() {
            int v = droppedByVersion();
            int r = droppedByRole();
            int g = droppedByGate();
            if (v == 0 && r == 0 && g == 0) {
                return "NONE";
            }
            if (r >= v && r >= g) {
                return "ROLE";
            }
            return v >= g ? "VERSION" : "GATE";
        }
    }

    /** @return 是否为空结果（上层据此走「依据不足」路径）。 */
    public boolean isEmpty() {
        return chunks.isEmpty();
    }

    /**
     * @return 命中的所有版本（用于检测版本串扰）。
     */
    public @NonNull List<EditionCoordinate> editions() {
        return chunks.stream().map(c -> c.chunk().edition()).distinct().toList();
    }

    /**
     * 单个召回片段及其分数与解释。
     *
     * <p><b>⚠️ 为什么是两个分数而不是一个</b>（2026-10-23 修复）：
     * 这两个分数<b>量纲不同</b>，混用会直接废掉分数门禁。
     * <ul>
     *   <li>{@code score}——<b>相关性分</b>（0~1）：检索器给出的真实相关性，
     *       稠密通道是余弦相似度、词法通道是 {@code ts_rank}。
     *       <b>分数门禁作用在它上面</b>（门禁阈值按 0~1 相关性标定，
     *       见 RD-Q2）：低于阈值即「未找到依据」。</li>
     *   <li>{@code fusionScore}——<b>融合排序分</b>：RRF 值，
     *       只用排名、跨语料稳定，量纲极小（双通道命中时最大约 2/61 ≈ 0.033）。
     *       <b>排序与版本降权作用在它上面</b>。</li>
     * </ul>
     * ⚠️ <b>曾经的缺陷</b>：JDBC 实现把 RRF 融合分塞进 {@code score}，
     * 于是门禁（0.2/0.35）永远高于 RRF 上限（≈0.033），
     * 检索<b>恒定返回「未找到依据」</b>——语料再全也召不回任何东西。
     * 这正是端口契约所说的「门禁必须作用于检索器给出的真实相关性分数」
     * 被实现层违反的后果。
     *
     * @param chunk 被召回片段
     * @param score 相关性分（0~1，门禁作用于此）
     * @param fusionScore 融合排序分（RRF，排序作用于此）
     * @param channel 召回通道
     * @param reason 命中原因
     */
    public record ScoredChunk(
            @NonNull Chunk chunk,
            double score,
            double fusionScore,
            @NonNull RetrievalChannel channel,
            @NonNull HitReason reason
    ) {

        public ScoredChunk {
            if (chunk == null) {
                throw new IllegalArgumentException("chunk 不能为空");
            }
            if (score < 0.0 || score > 1.0) {
                throw new IllegalArgumentException("score 必须落在 [0,1]");
            }
            if (fusionScore < 0.0 || fusionScore > 1.0) {
                throw new IllegalArgumentException("fusionScore 必须落在 [0,1]");
            }
            if (channel == null) {
                throw new IllegalArgumentException("channel 不能为空");
            }
            if (reason == null) {
                throw new IllegalArgumentException("reason 不能为空");
            }
        }
    }

    /** 召回通道。 */
    public enum RetrievalChannel {
        /** 向量语义召回。 */
        DENSE_VECTOR,
        /** 关键词/BM25 召回。 */
        LEXICAL,
        /** 沿知识点聚合召回。 */
        KNOWLEDGE_POINT,
        /** 沿先修关系扩展召回。 */
        PREREQUISITE_EXPANSION,
        /** 版本内同章节邻接扩展。 */
        SECTION_NEIGHBOR,
        /**
         * 生成期依据（B5）。
         *
         * <p>⚠️ 这不是检索通道：这些片段<b>不是被本端检索出来的</b>，
         * 而是调用方从前一次检索结果里指定后交来生成的。
         * 单列一个值是为了让「这条为什么出现」的审计解释保持诚实——
         * 若复用 {@link #DENSE_VECTOR}，会让人以为本端做过向量召回。
         */
        GENERATION_BASIS
    }

    /** 命中原因。 */
    public enum HitReason {
        /** 查询词直接命中。 */
        DIRECT_MATCH,
        /** 别名/规范名命中。 */
        ALIAS_MATCH,
        /** 知识点聚合带入。 */
        KP_AGGREGATED,
        /** 先修依赖带入。 */
        PREREQUISITE,
        /** 同章节邻接。 */
        NEIGHBOR,
        /** 答案类内容被模式过滤排除（保留在审计中）。 */
        SUPPRESSED_BY_MODE,
        /**
         * 由调用方指定为生成依据（B5）。
         *
         * <p>与 {@link RetrievalChannel#GENERATION_BASIS} 配对：
         * 说明「这条片段的出现是调用方的选择，不是检索器的判断」。
         */
        CITED_AS_BASIS
    }
}
