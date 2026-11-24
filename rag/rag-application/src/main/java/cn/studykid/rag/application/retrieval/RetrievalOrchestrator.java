package cn.studykid.rag.application.retrieval;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.knowledge.KnowledgePoint;
import cn.studykid.rag.core.retrieval.RetrievalPolicy;
import cn.studykid.rag.core.retrieval.RetrievalRequest;
import cn.studykid.rag.core.retrieval.RetrievalResult;
import cn.studykid.rag.core.retrieval.RetrievalResult.HitReason;
import cn.studykid.rag.core.retrieval.RetrievalResult.RetrievalChannel;
import cn.studykid.rag.core.retrieval.RetrievalResult.ScoredChunk;
import cn.studykid.rag.core.scope.RagScopeFilter;
import cn.studykid.rag.core.version.EditionScope;
import cn.studykid.rag.ports.RetrievalPort;

/**
 * 检索编排用例（RAG-D05 §3）。
 *
 * <p><b>编排顺序不可调换</b>——这是本项目「教辅特殊性」落地的核心：
 * <pre>
 *   ① 知识点解析   → 确定检索意图的语义中心
 *   ② 多路召回     → 稠密 + 词法 并行
 *   ③ 版本过滤     → 硬过滤（串版本 = 教辅第一大事故）
 *   ④ 角色过滤     → 硬过滤（泄题 = 教辅第二大事故）
 *   ⑤ 知识点补全   → 解决碎片化（讲与练成组出现）
 *   ⑥ 重排         → 跨通道分数归一后重排
 *   ⑦ 分数门禁     → 低于阈值则判为「未找到依据」
 * </pre>
 *
 * <p><b>⚠️ 为什么版本/角色过滤必须在重排之前</b>：
 * 重排模型会基于相关性打分，它<b>不知道</b>哪些内容是答案册。
 * 若先重排后过滤，top-k 里的答案内容被滤掉后，剩下的可能不足 k 条，
 * 而被滤掉的内容<b>已经进入过上下文</b>。顺序反了，闸门就形同虚设。
 *
 * <p><b>⚠️ 分数门禁为什么必须有</b>：
 * 没有门禁时，语义检索总会返回「最不相似的那一条」——
 * 模型随后会基于这条弱相关内容编答案。这正是 RAG 系统
 * 「自信地胡说」的主要来源。门禁让<b>「找不到」成为一个合法输出</b>。
 */
public class RetrievalOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(RetrievalOrchestrator.class);

    private final RetrievalPort retrievalPort;
    private final double minScore;
    /** 批 9 / P2-1：是否回带父块（默认 false 保既有行为，装配方灰度开启）。 */
    private final boolean expandParents;

    /**
     * @param retrievalPort 检索端口
     * @param minScore 分数门禁阈值（低于此值视为未找到依据）
     */
    public RetrievalOrchestrator(RetrievalPort retrievalPort, double minScore) {
        this(retrievalPort, minScore, false);
    }

    /**
     * 批 9 完整链构造：可开启父块回带。
     *
     * @param retrievalPort 检索端口
     * @param minScore      分数门禁阈值
     * @param expandParents 是否回带父块（需配合摄取链 parent-child 开关）
     */
    public RetrievalOrchestrator(RetrievalPort retrievalPort, double minScore,
                                 boolean expandParents) {
        if (retrievalPort == null) {
            throw new IllegalArgumentException("retrievalPort 不能为空");
        }
        if (minScore < 0.0 || minScore > 1.0) {
            throw new IllegalArgumentException("minScore 必须落在 [0,1]");
        }
        this.retrievalPort = retrievalPort;
        this.minScore = minScore;
        this.expandParents = expandParents;
    }

    /**
     * 执行检索编排。
     *
     * <p><b>⚠️ {@code scope} 必须逐层透传，不得在本层「就地决定」</b>（RLS 清理 · M3）：
     * 作用域是<b>行级隔离的唯一权威</b>，判定点在<b>入口</b>（认证/租户上下文解析），
     * 编排层只是搬运工。若编排层自己合成一个 scope，就会出现
     * 「入口判的是租户 A、SQL 过滤的是租户 B」这种<b>两处各自为政</b>的隔离缺陷——
     * 而且它不会报错，只会静默返回空或返回错租户的数据。
     *
     * @param scope 行级作用域（隔离权威；逐层透传给端口）
     * @param request 检索请求
     * @return 检索结果
     */
    public RetrievalResult retrieve(RagScopeFilter scope, RetrievalRequest request) {
        // ① 知识点解析
        List<KnowledgePoint> knowledgePoints = resolveKnowledgePoints(scope, request);

        // ② 多路召回：稠密 + 词法（保留检索器给出的真实分数）
        List<ScoredChunk> candidates = new ArrayList<>(retrievalPort.search(scope, request));
        if (request.knowledgePointDirected()) {
            // ⚠️ 知识点补全引入的片段没有独立的检索分数，
            //    统一赋予「知识点补全基准分」——它必须低于真实命中分，
            //    否则补全内容会挤掉直接命中的高价值内容。
            candidates.addAll(scoreAs(candidates, retrievalPort.findByKnowledgePoint(
                    scope, request.knowledgePointId(), request.editionScope()),
                    RetrievalChannel.KNOWLEDGE_POINT, HitReason.KP_AGGREGATED));
            candidates.addAll(scoreAs(candidates, retrievalPort.findPrerequisites(
                    scope, request.knowledgePointId(), request.editionScope()),
                    RetrievalChannel.PREREQUISITE_EXPANSION, HitReason.PREREQUISITE));
        }

        // ③ 版本过滤（硬过滤）
        List<ScoredChunk> versionFiltered = candidates.stream()
                .filter(s -> request.editionScope().admits(s.chunk().edition()))
                .toList();

        // ④ 角色过滤（硬过滤）——泄题闸门
        List<ScoredChunk> roleFiltered = versionFiltered.stream()
                .filter(s -> RetrievalPolicy.allowsRole(s.chunk().role(), request.mode()))
                .toList();

        // ⑤ 知识点补全——解决碎片化
        List<ScoredChunk> completed = roleFiltered;
        if (request.knowledgePointDirected()) {
            List<Chunk> siblings = retrievalPort.findByKnowledgePoint(
                    scope, request.knowledgePointId(), request.editionScope());
            List<ScoredChunk> siblingScored = scoreAs(candidates, siblings,
                    RetrievalChannel.KNOWLEDGE_POINT, HitReason.KP_AGGREGATED);
            // ⚠️ 补全过程同样受角色过滤约束：补全不能绕过泄题闸门。
            List<String> present = roleFiltered.stream().map(s -> s.chunk().id()).toList();
            completed = new ArrayList<>(roleFiltered);
            siblingScored.stream()
                    .filter(s -> !present.contains(s.chunk().id()))
                    .filter(s -> RetrievalPolicy.allowsRole(s.chunk().role(), request.mode()))
                    .forEach(completed::add);
        }

        // ⑥ 排序：按融合分（RRF）排，叠加版本惩罚
        //    ⚠️ 排序键与门禁键**不是同一个数**：排序用融合分（RRF，只看排名），
        //    门禁用相关性分（余弦/ts_rank，0~1）。两者量纲不同，混用会废掉门禁。
        List<ScoredChunk> scored = completed.stream()
                .map(s -> applyEditionPenalty(s, request.editionScope()))
                .sorted((a, b) -> Double.compare(b.fusionScore(), a.fusionScore()))
                .toList();

        // ⑦ 分数门禁——使「未找到依据」成为合法输出
        //    ⚠️ 门禁作用在**相关性分**上（0~1，阈值按相关性标定，见 RD-Q2）。
        //    若误用融合分（RRF，上限约 0.033），门禁将恒不通过——
        //    检索会永远返回「未找到依据」，语料再全也召不回东西。
        List<ScoredChunk> above = scored.stream()
                .filter(s -> s.score() >= minScore)
                .toList();

        boolean versionMismatch = versionFiltered.stream()
                .anyMatch(s -> request.editionScope().requiresVersionDisclaimer(s.chunk().edition()));
        boolean suppressed = versionFiltered.size() > roleFiltered.size();

        // ⑧ 过滤漏斗计数（RAG-D05 §7 / 靶点 D）
        //    ⚠️ 计数点必须紧贴各环节的 List，**不能事后重算**：
        //       重算等于把过滤规则再实现一遍，两份实现必然漂移；
        //       而这份漂移会直接表现为"漏斗显示过滤掉 0 条，结果却是空的"。
        List<ScoredChunk> returned = above.stream().limit(request.topK()).toList();
        RetrievalResult.RetrievalFunnel funnel = new RetrievalResult.RetrievalFunnel(
                candidates.size(),      // ① 多路召回原始候选
                versionFiltered.size(), // ② 版本硬过滤后
                roleFiltered.size(),    // ③ 角色硬过滤后（泄题闸门）
                above.size(),           // ④ 分数门禁后
                returned.size());       // ⑤ topK 截断后

        // ⑨ 父块回带（批 9 / P2-1，可选）：命中子块后按 parent_chunk_id 取回父块送 LLM。
        //    ⚠️ 位于 topK 之后：父块是「上下文」，不挤占 topK 名额、不参与排序、不进 funnel。
        //    ⚠️ 回带父块再过一次角色闸门（RetrievalPolicy.expandParents 内部），
        //       讲解模式绝不因回带而泄题；去重保序由 expandParents 保证。
        List<ScoredChunk> delivered = expandParents
                ? withParentContext(scope, returned, request.mode())
                : returned;

        if (above.isEmpty()) {
            log.debug("检索未达分数门禁：query={} mode={} minScore={} funnel={}",
                    request.query(), request.mode(), minScore, funnel);
        }

        return new RetrievalResult(
                delivered,
                knowledgePoints,
                request.editionScope().preferred(),
                versionMismatch,
                suppressed,
                funnel,
                // 本次实际生效的门禁阈值（界面据此画刻度线，避免与配置漂移）
                minScore);
    }

    /**
     * 父块回带（批 9 / P2-1）：命中子块 → 按 parent_chunk_id 取回父块 → 过角色闸门 → 附加。
     *
     * <p>⚠️ cast 到 {@link ScoredChunk} 时，父块用<b>声明式基准分</b>
     * （{@link #COMPLETION_BASE_SCORE}）与独立通道/原因（PARENT_CONTEXT / PARENT_EXPANSION），
     * 绝不伪造「检索命中分」——父块不是被检索出来的，是沿结构回带的上下文。
     *
     * <p>⚠️ 父块来源经 {@code RetrievalPort.loadByIds}，与命中片段同一道作用域隔离
     * （RLS + 显式 tenant 谓词），他租户父块取不到；取回后再由
     * {@link RetrievalPolicy#expandParents} 按 mode 过滤角色、去重保序。
     */
    private List<ScoredChunk> withParentContext(RagScopeFilter scope,
                                                List<ScoredChunk> hits,
                                                RetrievalRequest.RetrievalMode mode) {
        if (hits.isEmpty()) {
            return hits;
        }
        List<String> parentIds = new ArrayList<>();
        for (ScoredChunk hit : hits) {
            String pid = hit.chunk().parentId();
            if (pid != null && !parentIds.contains(pid)) {
                parentIds.add(pid);
            }
        }
        if (parentIds.isEmpty()) {
            return hits;
        }
        List<Chunk> pool = new ArrayList<>(hits.stream().map(ScoredChunk::chunk).toList());
        pool.addAll(retrievalPort.loadByIds(scope, parentIds));
        List<Chunk> expanded = RetrievalPolicy.expandParents(
                hits.stream().map(ScoredChunk::chunk).toList(), pool, mode);
        // 仅新增的父块需要包装为 ScoredChunk（命中片段保持原分数/通道不变）。
        java.util.Set<String> hitIds = hits.stream().map(s -> s.chunk().id())
                .collect(java.util.stream.Collectors.toSet());
        List<ScoredChunk> result = new ArrayList<>(hits);
        for (Chunk c : expanded) {
            if (!hitIds.contains(c.id())) {
                result.add(new ScoredChunk(c, COMPLETION_BASE_SCORE, 0.0,
                        RetrievalChannel.PARENT_CONTEXT, HitReason.PARENT_EXPANSION));
            }
        }
        return List.copyOf(result);
    }

    /**
     * 知识点补全片段的相关性基准分。
     *
     * <p>⚠️ 补全片段<b>没有独立的检索分数</b>（它不是被检索出来的，
     * 是沿知识点关系补进来的），故给一个<b>声明式基准分</b>：
     * 它让补全片段能通过分数门禁（否则任何非零门禁都会把补全全部砍掉，
     * 补全功能形同虚设），同时在<b>排序上排在真实命中之后</b>
     * （补全片段的融合分为 0），符合「补全不能挤掉直接命中的高价值片段」。
     * 取 0.5 是一个保守初值，实际阈值须由评测集标定（RAG-D08 §4）。
     */
    private static final double COMPLETION_BASE_SCORE = 0.5;

    /**
     * 为补全引入的片段赋分。
     *
     * <p>⚠️ 这些片段没有独立的检索分数，故使用统一的补全基准分，
     * 并在 {@link HitReason} 中标记来源，便于审计「这条为什么会出现」。
     */
    private List<ScoredChunk> scoreAs(List<ScoredChunk> existing, List<Chunk> chunks,
                                      RetrievalChannel channel, HitReason reason) {
        List<String> present = existing.stream().map(s -> s.chunk().id()).toList();
        List<ScoredChunk> result = new ArrayList<>();
        for (Chunk chunk : chunks) {
            if (present.contains(chunk.id())) {
                continue;
            }
            result.add(new ScoredChunk(chunk, COMPLETION_BASE_SCORE, 0.0, channel, reason));
        }
        return result;
    }

    /**
     * 叠加版本惩罚。
     *
     * <p>⚠️ 这是<b>排序</b>惩罚，不是<b>过滤</b>——过滤已在 ③④ 阶段完成。
     * 二者混淆会导致「降权后仍被召回」，那正是版本串扰的成因。
     */
    private ScoredChunk applyEditionPenalty(ScoredChunk scored, EditionScope scope) {
        int penalty = scope.rankPenalty(scored.chunk().edition());
        if (penalty == 0) {
            return scored;
        }
        // ⚠️ 惩罚必须作用在**融合分**（排序键）上，且用**乘法**：
        //    ① 作用在相关性分上会把「降权」变成「过滤」——相关性 0.36 减 0.1 得 0.26，
        //       低于门禁 0.35 → 异版本内容被静默丢弃，与「降权≠过滤」的设计约定冲突；
        //    ② RRF 量纲极小（≈0.03），加法惩罚会把所有异版本分数一律压成 0，
        //       组内相对次序全部丢失；乘法则只影响名次，不破坏量纲与组内次序。
        double adjusted = scored.fusionScore() * (1.0 - (penalty / 100.0));
        return new ScoredChunk(scored.chunk(), scored.score(), adjusted,
                scored.channel(), scored.reason());
    }

    private List<KnowledgePoint> resolveKnowledgePoints(RagScopeFilter scope, RetrievalRequest request) {
        if (request.knowledgePointDirected()) {
            return List.of();
        }
        return retrievalPort.resolveKnowledgePoints(scope, request.query(), request.subject());
    }
}
