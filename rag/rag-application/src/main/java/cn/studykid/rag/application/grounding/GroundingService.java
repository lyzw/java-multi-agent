package cn.studykid.rag.application.grounding;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.citation.Citation;
import cn.studykid.rag.core.grounding.GroundingPolicy;
import cn.studykid.rag.core.grounding.GroundingVerdict;
import cn.studykid.rag.core.retrieval.RetrievalResult;
import cn.studykid.rag.core.retrieval.RetrievalResult.ScoredChunk;
import cn.studykid.rag.ports.EntailmentPort;

/**
 * 引用装配与接地校验用例（RAG-D06 §3）。
 *
 * <p><b>⚠️ 本类实现的是「输出可信」的最后一道闸门</b>。它回答一个问题：
 * <blockquote>
 * 回答中的每一句断言，是否都能追溯到<b>已授权语料中的具体位置</b>，
 * 且该位置的内容<b>真的支撑</b>这句断言？
 * </blockquote>
 *
 * <p><b>校验顺序不可调换</b>（成本从低到高，失败即短路）：
 * <pre>
 *   ① 存在性校验（不依赖模型）：chunkId 存在？引文是子串？版本匹配？
 *   ② 角色校验（不依赖模型）：是否引用了当前模式禁止的内容？
 *   ③ 冲突检测（不依赖模型）：引用之间是否自相矛盾？
 *   ④ 蕴含判定（依赖模型）：片段是否真的支撑这句断言？
 *   ⑤ 整体裁决（策略）：可否交付？
 * </pre>
 *
 * <p><b>⚠️ 为什么 ①②③ 必须在 ④ 之前</b>：前三个是<b>确定性</b>判定，
 * 成本近零且结论绝对可靠。模型的蕴含判定存在误判（既可能误放行，
 * 也可能误杀）。若把模型判定放在前面，一个<b>根本不存在</b>的引用
 * 会先被送去问模型「这段是否支撑那句」——得到的答案毫无意义，
 * 还白白消耗模型调用预算。
 */
public class GroundingService {

    private final EntailmentPort entailmentPort;
    private final GroundingPolicy policy;

    /**
     * @param entailmentPort 蕴含判定端口
     * @param policy 接地策略
     */
    public GroundingService(EntailmentPort entailmentPort, GroundingPolicy policy) {
        if (entailmentPort == null) {
            throw new IllegalArgumentException("entailmentPort 不能为空");
        }
        if (policy == null) {
            throw new IllegalArgumentException("policy 不能为空");
        }
        this.entailmentPort = entailmentPort;
        this.policy = policy;
    }

    /**
     * 对一次生成结果做完整接地校验。
     *
     * @param claims 回答中的断言列表
     * @param citations 回答携带的引用
     * @param retrieved 本次检索到的片段（用于存在性校验）
     * @return 接地校验结果
     */
    public @NonNull GroundingResult verify(@NonNull List<String> claims,
                                           @NonNull List<Citation> citations,
                                           @NonNull List<ScoredChunk> retrieved) {
        // ① 存在性校验
        boolean existenceOk = entailmentPort.verifyExistence(citations);
        List<Citation> invalid = citations.stream()
                .filter(c -> retrieved.stream().noneMatch(s -> c.pointsTo(s.chunk())))
                .toList();
        if (!invalid.isEmpty()) {
            return GroundingResult.rejected(
                    "引用指向不存在的语料位置（" + invalid.size() + " 条）", claims);
        }
        if (!existenceOk) {
            return GroundingResult.rejected("引用存在性校验未通过", claims);
        }

        // ② 角色校验——泄题闸门
        List<Citation> forbidden = citations.stream()
                .filter(c -> !policy.allowsRole(roleOf(c, retrieved)))
                .toList();
        if (!forbidden.isEmpty()) {
            return GroundingResult.rejected(
                    "回答引用了当前模式禁止的内容（" + forbidden.size() + " 条）", claims);
        }

        // ③ 冲突检测
        String conflict = entailmentPort.detectConflict(citations);
        if (conflict != null) {
            return GroundingResult.rejected("引用内容存在冲突：" + conflict, claims);
        }

        // ④ 蕴含判定
        List<GroundingVerdict> verdicts = new ArrayList<>();
        for (String claim : claims) {
            List<Citation> supporting = citations.stream()
                    .filter(c -> c.supportedClaim().equals(claim))
                    .toList();
            if (supporting.isEmpty()) {
                // ⚠️ 无引用支撑的断言直接判为未接地，不送去问模型。
                verdicts.add(new GroundingVerdict(GroundingVerdict.Verdict.UNGROUNDED,
                        claim, List.of(), "该断言没有任何引用支撑", 0.0));
                continue;
            }
            String evidence = supporting.stream()
                    .map(c -> c.verbatimQuote())
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("");
            double score = entailmentPort.entailment(claim, evidence);
            GroundingVerdict.Verdict verdict = score >= policy.entailmentThreshold()
                    ? GroundingVerdict.Verdict.GROUNDED
                    : (score > 0.0 ? GroundingVerdict.Verdict.PARTIALLY_GROUNDED
                                   : GroundingVerdict.Verdict.UNGROUNDED);
            verdicts.add(new GroundingVerdict(verdict, claim, supporting, null, score));
        }

        // ⑤ 整体裁决
        GroundingPolicy.DeliveryDecision decision = policy.decide(verdicts);
        return new GroundingResult(decision.deliverable(), decision.reason(),
                decision.flaggedClaims(), verdicts);
    }

    private cn.studykid.rag.core.chunk.ChunkRole roleOf(Citation citation, List<ScoredChunk> retrieved) {
        return retrieved.stream()
                .map(ScoredChunk::chunk)
                .filter(c -> c.id().equals(citation.chunkId()))
                .map(Chunk::role)
                .findFirst()
                .orElse(cn.studykid.rag.core.chunk.ChunkRole.EXPLANATION);
    }

    /**
     * 接地校验结果。
     *
     * @param deliverable 是否允许交付
     * @param reason 理由
     * @param flaggedClaims 被标记的断言
     * @param verdicts 逐条判定
     */
    public record GroundingResult(
            boolean deliverable,
            @NonNull String reason,
            @NonNull List<String> flaggedClaims,
            @NonNull List<GroundingVerdict> verdicts
    ) {

        public GroundingResult {
            flaggedClaims = flaggedClaims == null ? List.of() : List.copyOf(flaggedClaims);
            verdicts = verdicts == null ? List.of() : List.copyOf(verdicts);
        }

        static @NonNull GroundingResult rejected(@NonNull String reason,
                                                 @NonNull List<String> claims) {
            return new GroundingResult(false, reason, claims, List.of());
        }

        /**
         * @param retrievalResult 检索结果
         * @return 供用户阅读的「依据说明」
         */
        public static @NonNull String evidenceSummary(@NonNull RetrievalResult retrievalResult) {
            if (retrievalResult.isEmpty()) {
                return "未在已授权语料中检索到相关依据。";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("依据：").append(retrievalResult.chunks().size()).append(" 处语料片段");
            if (retrievalResult.versionMismatchDetected()) {
                sb.append("；部分内容来自其他版本教材，已标注差异");
            }
            if (retrievalResult.answerContentSuppressed()) {
                sb.append("；标准答案内容按讲解策略未纳入");
            }
            return sb.toString();
        }
    }
}
