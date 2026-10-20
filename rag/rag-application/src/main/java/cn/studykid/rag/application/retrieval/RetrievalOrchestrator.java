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

    /**
     * @param retrievalPort 检索端口
     * @param minScore 分数门禁阈值（低于此值视为未找到依据）
     */
    public RetrievalOrchestrator(RetrievalPort retrievalPort, double minScore) {
        if (retrievalPort == null) {
            throw new IllegalArgumentException("retrievalPort 不能为空");
        }
        if (minScore < 0.0 || minScore > 1.0) {
            throw new IllegalArgumentException("minScore 必须落在 [0,1]");
        }
        this.retrievalPort = retrievalPort;
        this.minScore = minScore;
    }

    /**
     * 执行检索编排。
     *
     * @param request 检索请求
     * @return 检索结果
     */
    public RetrievalResult retrieve(RetrievalRequest request) {
        // ① 知识点解析
        List<KnowledgePoint> knowledgePoints = resolveKnowledgePoints(request);

        // ② 多路召回：稠密 + 词法（保留检索器给出的真实分数）
        List<ScoredChunk> candidates = new ArrayList<>(retrievalPort.search(request));
        if (request.knowledgePointDirected()) {
            // ⚠️ 知识点补全引入的片段没有独立的检索分数，
            //    统一赋予「知识点补全基准分」——它必须低于真实命中分，
            //    否则补全内容会挤掉直接命中的高价值内容。
            candidates.addAll(scoreAs(candidates, retrievalPort.findByKnowledgePoint(
                    request.knowledgePointId(), request.editionScope()),
                    RetrievalChannel.KNOWLEDGE_POINT, HitReason.KP_AGGREGATED));
            candidates.addAll(scoreAs(candidates, retrievalPort.findPrerequisites(
                    request.knowledgePointId(), request.editionScope()),
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
                    request.knowledgePointId(), request.editionScope());
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

        // ⑥ 排序：保留真实分数，叠加版本惩罚
        List<ScoredChunk> scored = completed.stream()
                .map(s -> applyEditionPenalty(s, request.editionScope()))
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .toList();

        // ⑦ 分数门禁——使「未找到依据」成为合法输出
        List<ScoredChunk> above = scored.stream()
                .filter(s -> s.score() >= minScore)
                .toList();

        boolean versionMismatch = versionFiltered.stream()
                .anyMatch(s -> request.editionScope().requiresVersionDisclaimer(s.chunk().edition()));
        boolean suppressed = versionFiltered.size() > roleFiltered.size();

        if (above.isEmpty()) {
            log.debug("检索未达分数门禁：query={} mode={} minScore={}",
                    request.query(), request.mode(), minScore);
        }

        return new RetrievalResult(
                above.stream().limit(request.topK()).toList(),
                knowledgePoints,
                request.editionScope().preferred(),
                versionMismatch,
                suppressed);
    }

    /**
     * 知识点补全片段的基准分。
     *
     * <p>⚠️ 必须<b>低于</b>真实检索命中的典型分数，否则补全内容会挤掉
     * 直接命中的高价值片段。取 0.5 是一个保守初值，
     * 实际阈值须由评测集标定（RAG-D08 §4）。
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
            result.add(new ScoredChunk(chunk, COMPLETION_BASE_SCORE, channel, reason));
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
        double adjusted = Math.max(0.0, scored.score() - (penalty / 100.0));
        return new ScoredChunk(scored.chunk(), adjusted, scored.channel(), scored.reason());
    }

    private List<KnowledgePoint> resolveKnowledgePoints(RetrievalRequest request) {
        if (request.knowledgePointDirected()) {
            return List.of();
        }
        return retrievalPort.resolveKnowledgePoints(request.query(), request.subject());
    }
}
