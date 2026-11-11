package cn.studykid.multiagent.context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;

/**
 * RAG 命中块 token 配额内核（《方案》§4.1 / §5.1 / §6.4，M10-a）。
 *
 * <p>⚠️ <b>纯算法、零接线</b>：本类不依赖 Spring / JDBC / IO，不被任何生产链路调用；
 * 它把「命中块按 token 配额裁剪」的五个动作内聚为确定性纯函数：
 * <ol>
 *   <li>去重（按 {@code chunkId}，保留最优候选）；</li>
 *   <li>确定性二次排序（score 降序 → locator 升序 → chunkId 升序 → text 升序）；</li>
 *   <li>按 token 配额动态 top-k（不是固定条数）；</li>
 *   <li>单块正文按 token 截断到 {@code maxHitTokens}；</li>
 *   <li>放不下的块只留 {@code chunkId + locator} 引用，支持按需回取。</li>
 * </ol>
 *
 * <p>⚠️ <b>三条硬不变量（撤掉对应修复点必须有测试变红）</b>：
 * <ul>
 *   <li><b>硬顶</b>：{@code afterTokens ≤ tokenBudget} —— 引用也扣减剩余预算，
 *       连引用都放不下就丢弃，绝不让「引用比原文贵」把上下文悄悄撑爆；</li>
 *   <li><b>严格净省</b>：{@code savedTokens = beforeTokens - afterTokens ≥ 0}，
 *       且只要发生去重 / 截断 / 降级 / 丢弃，就严格 {@code > 0}；
 *       {@code beforeTokens} 按<b>含重复的原始全量</b>统计，故每个裁剪动作都体现为节省；</li>
 *   <li><b>确定性</b>：全序含 {@code chunkId} / {@code text} 末级 tie-break，
 *       输出与输入顺序无关，不依赖 TimSort 稳定性。</li>
 * </ul>
 *
 * <p>⚠️ <b>计数口径由调用方注入</b>：{@link TextTokens} 只估算一段正文文本的 token；
 * 每条命中的元数据（chunkId/score/locator/edition/role/reason + JSON 标点 + 围栏）
 * 由调用方算成 {@link Candidate#overheadTokens()} 一并计入。这样算法层不耦合任何
 * 序列化格式，M10 接线时由 agent-worker 做薄适配。
 *
 * <p>⚠️ <b>{@link TextTokens#count(String)} 必须对字符串长度单调不减</b>：
 * 单块截断依赖「前缀越长 token 不减」寻找最长可容纳前缀。
 */
public final class RagHitQuota {

    /** 正文文本 token 估算（纯函数，单调不减）。 */
    @FunctionalInterface
    public interface TextTokens {
        int count(@NonNull String text);
    }

    /** 一条「只留 chunkId + locator」引用行的 token 估算。 */
    @FunctionalInterface
    public interface RefTokens {
        int count(@NonNull String chunkId, @NonNull String locator);
    }

    /**
     * 一条候选命中。{@code overheadTokens} 是除正文外该命中渲染的全部 token 开销。
     * 全参构造唯一，fail-closed 校验身份与数值。
     */
    public record Candidate(
            @NonNull String chunkId,
            @NonNull String text,
            double score,
            @NonNull String locator,
            int overheadTokens) {
        public Candidate {
            if (chunkId == null || chunkId.isBlank()) {
                throw new IllegalArgumentException("Candidate.chunkId 不得为空（fail-closed）");
            }
            if (text == null) {
                throw new IllegalArgumentException("Candidate.text 不得为 null（fail-closed）");
            }
            if (locator == null || locator.isBlank()) {
                throw new IllegalArgumentException("Candidate.locator 不得为空（fail-closed）");
            }
            if (overheadTokens < 0) {
                throw new IllegalArgumentException("Candidate.overheadTokens 不得为负");
            }
            if (!Double.isFinite(score)) {
                throw new IllegalArgumentException("Candidate.score 必须是有限数");
            }
        }
    }

    /** 配额策略：全部为正且自洽，fail-closed。 */
    public record Options(
            int tokenBudget,
            int maxHits,
            int maxHitTokens,
            int minKeptTokens) {
        public Options {
            if (tokenBudget <= 0) {
                throw new IllegalArgumentException("tokenBudget 必须为正");
            }
            if (maxHits <= 0) {
                throw new IllegalArgumentException("maxHits 必须为正");
            }
            if (maxHitTokens <= 0) {
                throw new IllegalArgumentException("maxHitTokens 必须为正");
            }
            if (minKeptTokens <= 0 || minKeptTokens > maxHitTokens) {
                throw new IllegalArgumentException("minKeptTokens 必须在 (0, maxHitTokens] 区间");
            }
        }
    }

    /** 保留的命中（正文可能被截断）。 */
    public record KeptHit(
            @NonNull String chunkId,
            @NonNull String text,
            @NonNull String locator,
            double score,
            int tokens,
            boolean truncated) {
    }

    /** 降级引用：只留定位信息，不含正文，支持按需回取。 */
    public record RefHit(
            @NonNull String chunkId,
            @NonNull String locator,
            int tokens) {
    }

    /** 裁剪结果。{@code kept}/{@code refs} 不可变；统计口径见类注释。 */
    public record Result(
            @NonNull List<KeptHit> kept,
            @NonNull List<RefHit> refs,
            int beforeTokens,
            int afterTokens,
            int savedTokens,
            int droppedCount,
            int duplicateCount,
            boolean truncated) {
        public Result {
            kept = List.copyOf(kept);
            refs = List.copyOf(refs);
            if (beforeTokens < 0 || afterTokens < 0 || savedTokens < 0
                    || droppedCount < 0 || duplicateCount < 0) {
                throw new IllegalArgumentException("RagHitQuota.Result 统计不得为负");
            }
        }
    }

    /** 单块截断后追加的省略标记（计入 token 上限）。 */
    static final String ELLIPSIS = "…";

    private RagHitQuota() {
    }

    /**
     * 按配额裁剪命中块。纯函数：相同输入（含相同计数函数行为）恒等输出。
     */
    public static @NonNull Result allocate(
            @NonNull List<Candidate> hits,
            @NonNull Options options,
            @NonNull TextTokens textTokens,
            @NonNull RefTokens refTokens) {
        if (hits == null || options == null || textTokens == null || refTokens == null) {
            throw new IllegalArgumentException("RagHitQuota.allocate 参数不得为 null（fail-closed）");
        }

        // beforeTokens：按含重复的原始全量统计，使去重也体现为严格净省。
        int before = 0;
        for (Candidate h : hits) {
            if (h == null) {
                throw new IllegalArgumentException("hits 不得含 null 元素（fail-closed）");
            }
            before += fullTokens(h, textTokens);
        }

        // ① 去重：chunkId 唯一，保留全序最优候选（score/locator/text/overhead）。
        Map<String, Candidate> unique = new LinkedHashMap<>();
        for (Candidate h : hits) {
            Candidate prev = unique.get(h.chunkId());
            if (prev == null || isBetter(h, prev)) {
                unique.put(h.chunkId(), h);
            }
        }
        int duplicates = hits.size() - unique.size();

        // ② 确定性二次排序：末级 tie-break 到 chunkId/text，输出与输入顺序无关。
        List<Candidate> ordered = new ArrayList<>(unique.values());
        ordered.sort(Comparator.comparingDouble(Candidate::score).reversed()
                .thenComparing(Candidate::locator)
                .thenComparing(Candidate::chunkId)
                .thenComparing(Candidate::text));

        List<KeptHit> kept = new ArrayList<>();
        List<RefHit> refs = new ArrayList<>();
        int remaining = options.tokenBudget();
        int dropped = 0;
        boolean truncated = false;

        for (Candidate h : ordered) {
            int full = fullTokens(h, textTokens);

            // ③ 全文保留：单块未超限、kept 未满、剩余预算装得下。
            if (kept.size() < options.maxHits()
                    && full <= options.maxHitTokens()
                    && remaining >= full) {
                kept.add(new KeptHit(h.chunkId(), h.text(), h.locator(), h.score(), full, false));
                remaining -= full;
                continue;
            }

            // ④ 单块截断：只在能留下「最小有效块」时才截，否则降级引用。
            if (kept.size() < options.maxHits() && remaining >= options.minKeptTokens()) {
                int cap = Math.min(options.maxHitTokens(), remaining);
                int bodyCap = cap - h.overheadTokens();
                if (bodyCap > 0) {
                    String cut = truncateToCap(h.text(), bodyCap, textTokens);
                    int cutTokens = h.overheadTokens() + textTokens.count(cut);
                    if (!cut.isEmpty()
                            && cutTokens >= options.minKeptTokens()
                            && cutTokens <= cap) {
                        kept.add(new KeptHit(h.chunkId(), cut, h.locator(), h.score(),
                                cutTokens, true));
                        truncated = true;
                        remaining -= cutTokens;
                        continue;
                    }
                }
            }

            // ⑤ 降级引用：只在引用「严格比原文便宜」且剩余预算装得下时保留；
            //    否则丢弃。⛔ 绝不留一个比原文还贵的引用（会把上下文静默撑爆）。
            int refCost = refTokens.count(h.chunkId(), h.locator());
            if (refCost < full && remaining >= refCost) {
                refs.add(new RefHit(h.chunkId(), h.locator(), refCost));
                remaining -= refCost;
            } else {
                dropped++;
            }
        }

        int after = 0;
        for (KeptHit k : kept) {
            after += k.tokens();
        }
        for (RefHit r : refs) {
            after += r.tokens();
        }
        if (after > options.tokenBudget()) {
            throw new IllegalStateException("RagHitQuota 硬顶被突破：after=" + after
                    + " budget=" + options.tokenBudget());
        }
        return new Result(kept, refs, before, after, before - after,
                dropped, duplicates, truncated);
    }

    private static int fullTokens(Candidate h, TextTokens textTokens) {
        return h.overheadTokens() + textTokens.count(h.text());
    }

    /** 全序比较：score 降序 → locator → chunkId → text → overhead，完全确定性。 */
    private static boolean isBetter(Candidate a, Candidate b) {
        if (a.score() != b.score()) {
            return a.score() > b.score();
        }
        int byLocator = a.locator().compareTo(b.locator());
        if (byLocator != 0) {
            return byLocator < 0;
        }
        int byChunk = a.chunkId().compareTo(b.chunkId());
        if (byChunk != 0) {
            return byChunk < 0;
        }
        int byText = a.text().compareTo(b.text());
        if (byText != 0) {
            return byText < 0;
        }
        return a.overheadTokens() < b.overheadTokens();
    }

    /**
     * 找最长前缀使 {@code prefix + ELLIPSIS} 的 token ≤ cap。
     * 依赖 {@link TextTokens#count(String)} 单调不减，首个超 cap 即停。
     */
    private static @NonNull String truncateToCap(
            @NonNull String text, int cap, @NonNull TextTokens textTokens) {
        if (textTokens.count(text) <= cap) {
            return text;
        }
        String best = "";
        for (int k = 1; k <= text.length(); k++) {
            String candidate = text.substring(0, k) + ELLIPSIS;
            if (textTokens.count(candidate) > cap) {
                break;
            }
            best = candidate;
        }
        return best;
    }
}
