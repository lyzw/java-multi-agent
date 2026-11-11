package cn.studykid.multiagent.context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.context.StructuredState.Artifact;
import cn.studykid.multiagent.context.StructuredState.Fact;
import cn.studykid.multiagent.context.StructuredState.ToolCall;
import cn.studykid.multiagent.ports.model.ModelMessage;

/**
 * 会话级滚动状态的<b>增量合并</b>内核（《方案》§4.3 [D8] / §5.3 / §5.4，M5-c）。
 *
 * <p>⚠️ <b>零 LLM / 零 IO / 零请求字节变更</b>：本类是纯算法。W3 的「抽取」由
 * 外部注入的 {@link DeltaExtractor} 提供（M7 的 IncrementalSummarizer，走 ModelGateway），
 * 本类只负责「喂增量、校验逐字、确定性合并、失败回落」，绝不自己调模型。
 * M5 阶段不被任何生产链路调用。
 *
 * <p>⚠️ <b>只吃 {@code seq > upToSeq} 的增量</b>（NoRecursiveResummary）：
 * 已吸收的轮次一律不重喂抽取器，否则「把旧摘要 + 新轮次一起再摘要」会逐轮漂移，
 * 且重复计费。没有新轮次时<b>抽取器一次都不调用</b>（零成本、零副作用）。
 * 坐标用显式 {@link SeqMessage#seq()}：M7 直接传 DB {@code run_message.seq}（可稀疏），
 * 不使用 list index，避免与 {@code CompactionPipeline} 的坐标假设耦合。
 *
 * <p>⚠️ <b>关键 span 逐字校验</b>：{@code constraints / userCorrections / facts.value}
 * 必须逐字符出现在增量原文里（§5.3 规则 ⑤「宁可多留不可改写」）。抽取器若把
 * UUID / 精确数值 / 「不要做 X」负向知识改写或近似，整批回落（fail-closed），
 * 绝不把失真状态写进 checkpoint。
 *
 * <p>⚠️ <b>确定性合并</b>：列表「旧值原序 + 新值去重追加」，事实按 key 排序，
 * 引用按 refId 去重且首次位置固定。同一旧状态 + 同一增量恒等输出 →
 * {@link StructuredStateCodec#digest} 稳定 → 幂等键 / 重投缓存可复用（§5.6）。
 */
public final class RollingStateMerger {

    /** 摘要失败的稳定原因码（供 fallback 指标按维度聚合）。 */
    static final String FALLBACK_EXTRACTOR_FAILED = "EXTRACTOR_FAILED";
    static final String FALLBACK_VERBATIM_VIOLATION = "VERBATIM_VIOLATION";
    static final String FALLBACK_POLICY_VERSION_MISMATCH = "POLICY_VERSION_MISMATCH";

    /**
     * 带 seq 坐标的消息；{@code seq} 是 checkpoint 单调游标坐标系（DB run_message.seq）。
     */
    public record SeqMessage(long seq, @NonNull ModelMessage message) {
    }

    /**
     * 增量抽取器：输入「旧状态 + 仅新轮次」，产出状态片段；empty = 抽取失败（回落）。
     * ⚠️ 由外部（M7）注入并走 ModelGateway；本模块零 LLM。
     */
    @FunctionalInterface
    public interface DeltaExtractor {
        @NonNull Optional<StructuredState> extract(@NonNull StructuredState oldState,
                                                   @NonNull List<ModelMessage> newMessages);
    }

    /**
     * 合并结果。{@code state} 在回落 / 无增量时恒等于输入旧状态（引用级不变），
     * 便于上层判断「本轮没有可写 checkpoint 的新状态」。
     *
     * @param state            合并后的状态（或回落的旧状态）
     * @param processedMessages 实际送进抽取器的新轮次条数
     * @param skippedMessages   {@code seq <= upToSeq} 被跳过条数（本不该重摘要）；
     *                          策略版本不符时连增量都未检视，故恒为 0
     * @param advanced          单调游标是否前移（= 是否产生了可持久化的新状态）
     * @param fallback          是否发生回落（抽取失败 / 逐字违规 / 版本不符）
     * @param fallbackReason    回落原因码（{@code null} = 无回落）
     * @param discardCheckpoint 版本不符 ⇒ 上层须丢弃旧 checkpoint 从 run_message 重建
     */
    public record MergeResult(@NonNull StructuredState state,
                              int processedMessages,
                              int skippedMessages,
                              boolean advanced,
                              boolean fallback,
                              @Nullable String fallbackReason,
                              boolean discardCheckpoint) {
        public MergeResult {
            state = state == null ? StructuredState.empty() : state;
        }
    }

    private RollingStateMerger() {
    }

    /**
     * 增量合并。纯函数：相同输入（含相同抽取器行为）恒等输出。
     *
     * @param oldState      旧状态（来自 checkpoint；无则 {@link StructuredState#empty()}）
     * @param delta         本轮可见的消息（可含已吸收的旧轮次，由本方法按 seq 过滤）
     * @param policyVersion 当前策略指纹；与 {@code oldState.policyVersion()} 不符即丢弃重建
     * @param extractor     外部抽取器（零 LLM 由本类保证：只在有新轮次时调用一次）
     */
    public static @NonNull MergeResult merge(@NonNull StructuredState oldState,
                                             @NonNull List<SeqMessage> delta,
                                             @NonNull String policyVersion,
                                             @NonNull DeltaExtractor extractor) {
        // 版本不符 = checkpoint 失效，绝不「复用旧策略的摘要」（§4.3）。
        if (!oldState.policyVersion().isEmpty()
                && !oldState.policyVersion().equals(policyVersion)) {
            return new MergeResult(oldState, 0, 0, false, true,
                    FALLBACK_POLICY_VERSION_MISMATCH, true);
        }

        List<ModelMessage> newMessages = new ArrayList<>();
        int skipped = 0;
        long maxSeq = oldState.upToSeq();
        for (SeqMessage sm : delta) {
            // ⛔ 只吃严格大于游标的增量；撤掉此闸门 = 递归重摘要（RV-28 反证点）。
            if (sm.seq() > oldState.upToSeq()) {
                newMessages.add(sm.message());
                maxSeq = Math.max(maxSeq, sm.seq());
            } else {
                skipped++;
            }
        }
        if (newMessages.isEmpty()) {
            // 无新轮次：抽取器一次都不调用（NoRecursiveResummary / 零计费）。
            return new MergeResult(oldState, 0, skipped, false, false, null, false);
        }

        List<String> violations;
        Optional<StructuredState> extracted;
        try {
            extracted = extractor.extract(oldState, List.copyOf(newMessages));
        } catch (RuntimeException e) {
            return new MergeResult(oldState, 0, skipped, false, true,
                    FALLBACK_EXTRACTOR_FAILED, false);
        }
        if (extracted == null || extracted.isEmpty()) {
            return new MergeResult(oldState, 0, skipped, false, true,
                    FALLBACK_EXTRACTOR_FAILED, false);
        }
        StructuredState fragment = extracted.get();

        // ⛔ 关键 span 必须逐字出现在增量原文；改写/近似即整批回落（绝不伪造状态）。
        violations = verifyVerbatim(fragment, newMessages);
        if (!violations.isEmpty()) {
            return new MergeResult(oldState, newMessages.size(), skipped, false, true,
                    FALLBACK_VERBATIM_VIOLATION, false);
        }

        // newMessages 非空（上方早返回保证）⇒ firstSeq 必命中真实 seq。
        // 取 min 保留最早覆盖范围：摘要链一旦丢头就不能假装 fromSeq 前移（RV-32）。
        long fromSeq = Math.min(oldState.fromSeq(), firstSeq(delta, oldState.upToSeq()));
        StructuredState merged = new StructuredState(
                fragment.goal().isEmpty() ? oldState.goal() : fragment.goal(),
                mergeList(oldState.constraints(), fragment.constraints()),
                mergeList(oldState.userCorrections(), fragment.userCorrections()),
                mergeList(oldState.decisions(), fragment.decisions()),
                mergeList(oldState.rejectedApproaches(), fragment.rejectedApproaches()),
                mergeList(oldState.openItems(), fragment.openItems()),
                mergeArtifacts(oldState.artifacts(), fragment.artifacts()),
                mergeFacts(oldState.facts(), fragment.facts()),
                mergeToolCalls(oldState.toolCalls(), fragment.toolCalls()),
                fromSeq,
                Math.max(oldState.upToSeq(), maxSeq),
                policyVersion);
        return new MergeResult(merged, newMessages.size(), skipped, true, false, null, false);
    }

    /** 第一个被吸收的 seq（游标之后的最小 seq）。 */
    private static long firstSeq(@NonNull List<SeqMessage> delta, long upToSeq) {
        long first = Long.MAX_VALUE;
        for (SeqMessage sm : delta) {
            if (sm.seq() > upToSeq) {
                first = Math.min(first, sm.seq());
            }
        }
        return first == Long.MAX_VALUE ? 0 : first;
    }

    /**
     * 校验逐字 span：{@code constraints / userCorrections / facts.value} 必须
     * 逐字符出现在<b>至少一条新消息</b>的 content 里（不跨消息拼接，防假阳性）。
     * 返回违规 span 列表（稳定顺序），空 = 通过。
     */
    static @NonNull List<String> verifyVerbatim(@NonNull StructuredState fragment,
                                                @NonNull List<ModelMessage> newMessages) {
        List<String> spans = new ArrayList<>();
        spans.addAll(fragment.constraints());
        spans.addAll(fragment.userCorrections());
        for (Fact f : fragment.facts()) {
            spans.add(f.value());
        }
        List<String> violations = new ArrayList<>();
        for (String span : spans) {
            if (span.isEmpty()) {
                continue;
            }
            boolean found = false;
            for (ModelMessage m : newMessages) {
                if (m.content().contains(span)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                violations.add(span);
            }
        }
        return List.copyOf(violations);
    }

    /** 旧值原序 + 新值去重追加（逐字保留，绝不 trim / 归一化 / 改写）。 */
    private static @NonNull List<String> mergeList(@NonNull List<String> oldValues,
                                                   @NonNull List<String> newValues) {
        List<String> out = new ArrayList<>(oldValues.size() + newValues.size());
        out.addAll(oldValues);
        for (String v : newValues) {
            if (!out.contains(v)) {
                out.add(v);
            }
        }
        return List.copyOf(out);
    }

    /** 事实按 key 去重：新值覆盖旧值，位置固定为首次出现；最终按 key 排序 → 确定性 JSON。 */
    private static @NonNull List<Fact> mergeFacts(@NonNull List<Fact> oldFacts,
                                                  @NonNull List<Fact> newFacts) {
        Map<String, String> byKey = new LinkedHashMap<>();
        for (Fact f : oldFacts) {
            byKey.put(f.key(), f.value());
        }
        for (Fact f : newFacts) {
            byKey.put(f.key(), f.value());
        }
        List<Fact> out = new ArrayList<>(byKey.size());
        for (String key : new ArrayList<>(byKey.keySet())) {
            out.add(new Fact(key, byKey.get(key)));
        }
        out.sort((a, b) -> a.key().compareTo(b.key()));
        return List.copyOf(out);
    }

    /** 引用按 refId 去重：新值覆盖旧值（更精确的 oneLine），位置固定为首次出现。 */
    private static @NonNull List<Artifact> mergeArtifacts(@NonNull List<Artifact> oldItems,
                                                          @NonNull List<Artifact> newItems) {
        Map<String, Artifact> byRef = new LinkedHashMap<>();
        for (Artifact a : oldItems) {
            byRef.put(a.refId(), a);
        }
        for (Artifact a : newItems) {
            byRef.put(a.refId(), a);
        }
        return List.copyOf(new ArrayList<>(byRef.values()));
    }

    /** 工具调用按 resultRef 去重：新值覆盖旧值，位置固定为首次出现。 */
    private static @NonNull List<ToolCall> mergeToolCalls(@NonNull List<ToolCall> oldItems,
                                                          @NonNull List<ToolCall> newItems) {
        Map<String, ToolCall> byRef = new LinkedHashMap<>();
        for (ToolCall t : oldItems) {
            byRef.put(t.resultRef(), t);
        }
        for (ToolCall t : newItems) {
            byRef.put(t.resultRef(), t);
        }
        return List.copyOf(new ArrayList<>(byRef.values()));
    }
}
