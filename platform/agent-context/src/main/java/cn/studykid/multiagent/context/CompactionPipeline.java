package cn.studykid.multiagent.context;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.ports.context.ContextBlocks;
import cn.studykid.multiagent.ports.context.TokenCounter;
import cn.studykid.multiagent.ports.model.ModelMessage;
import cn.studykid.multiagent.ports.model.ModelMessage.Role;
import cn.studykid.multiagent.context.RoundSplitter.RoundSegment;
import cn.studykid.multiagent.context.WaterLevel.Thresholds;

/**
 * 上下文压缩管线：W1 可逆外置 → W2 整轮裁剪 → W3 增量摘要（《方案》§5.3 / §6.4-③，M5-b）。
 *
 * <p>⚠️ <b>零 LLM / 零 IO / 零请求字节变更</b>：本类是纯算法。
 * W3 摘要通过注入的 {@link Summarizer} 由<b>外部</b>（M7 的 IncrementalSummarizer，走
 * ModelGateway）产出；本类只负责「喂增量、拿结果、失败回落」，绝不自己调模型。
 * M5 阶段不被任何生产链路调用，新增它不改变在途请求。
 *
 * <p>⚠️ <b>首轮锚 / 工具配对是硬约束</b>：丢弃只针对最早的可丢 TURN，
 * ANCHOR（USER）永不丢；W1 只替换 TOOL 的 content，不动 assistant 的 tool_calls 配对。
 *
 * <p>⚠️ <b>摘要失败绝不伪造</b>：{@link Summarizer} 返回 empty / 抛异常 → 回落 W2、
 * {@code fallback=true} 且给出稳定原因码，而不是塞一段编造的摘要进上下文。
 *
 * <p>⚠️ <b>seq 坐标系</b>：{@link Input#messages()} 按历史顺序排列，其 <b>list index 即逻辑 seq</b>；
 * {@code upToSeq} 在同一坐标系。M7 接线时负责把 DB {@code run_message.seq}
 * 映射到此坐标（按 seq 升序读出即可），算法层不认识 DB 的稀疏序号。
 */
public final class CompactionPipeline {

    /** 摘要器：输入「增量消息 + 旧摘要」，产出新摘要文本；empty = 无摘要可用（回落 W2）。 */
    @FunctionalInterface
    public interface Summarizer {
        @NonNull Optional<String> summarize(@NonNull List<ModelMessage> delta,
                                            @Nullable String oldSummary);
    }

    /** 压缩输入（六块 + 消息序列 + checkpoint 状态）。全参构造是唯一构造。 */
    public record Input(
            @NonNull String systemPrompt,
            @NonNull String toolSchemas,
            @NonNull String firstUserTurn,
            @NonNull List<String> ragBlocks,
            @NonNull String memoryBlock,
            @NonNull List<ModelMessage> messages,
            @NonNull String currentInput,
            /** checkpoint 单调游标：summarizer 只吃 index > upToSeq 的增量。 */
            long upToSeq,
            /** 旧摘要（来自 checkpoint；无则 null）。 */
            @Nullable String oldSummary) {
        public Input {
            systemPrompt = systemPrompt == null ? "" : systemPrompt;
            toolSchemas = toolSchemas == null ? "" : toolSchemas;
            firstUserTurn = firstUserTurn == null ? "" : firstUserTurn;
            ragBlocks = ragBlocks == null ? List.of() : List.copyOf(ragBlocks);
            memoryBlock = memoryBlock == null ? "" : memoryBlock;
            messages = messages == null ? List.of() : List.copyOf(messages);
            currentInput = currentInput == null ? "" : currentInput;
        }
    }

    /** 摘要失败的稳定原因码（供 fallback 指标按维度聚合）。 */
    static final String FALLBACK_SUMMARIZER_FAILED = "SUMMARIZER_FAILED";
    static final String FALLBACK_HARD_TRIM = "HARD_TRIM";

    private final @NonNull TokenCounter counter;

    public CompactionPipeline(@NonNull TokenCounter counter) {
        this.counter = counter;
    }

    /**
     * 执行压缩。纯函数：相同输入（含相同 summarizer 行为）恒等输出。
     */
    public @NonNull CompactionResult compact(@NonNull Input input,
                                             @NonNull ContextBudget budget,
                                             @NonNull ContextPolicy policy,
                                             @NonNull Thresholds thresholds,
                                             @NonNull Summarizer summarizer) {
        int before = estimate(input, input.messages(), null, null);
        WaterLevel.Tier tier = WaterLevel.decide(before, budget.window(), policy, thresholds);

        // W0：不压缩，原样返回（仅计量）。
        if (tier == WaterLevel.Tier.W0) {
            CompactedBlocks pass = blocks(input, input.messages(), null, null);
            return new CompactionResult(pass, tier, false, null, 0, List.of(),
                    before, estimate(input, input.messages(), null, null));
        }
        boolean hard = tier == WaterLevel.Tier.HARD;

        // ── W1：老 TOOL 结果可逆外置（零 LLM）─────────────────────────
        W1Result w1 = snipOldToolResults(input.messages(), policy.recentTurns(),
                input.upToSeq(), counter);
        List<ModelMessage> afterW1 = w1.messages();
        List<String> snipped = w1.snippedRefs();
        int currentTier = WaterLevel.Tier.W1.ordinal();

        // ── W2：整轮裁剪（零 LLM）───────────────────────────────────
        // ⚠️ 只有达到 W2 及以上才整轮裁剪；W1 仅做可逆外置。
        // ⚠️ 裁剪目标是 targetRatio×window（把用量从 trigger 压回 target），
        //    ⛔ 不是 hardLimit(0.90×window)——否则 usage=0.70 时 before 本就 <hardLimit，
        //    W2 会「触发了却不裁」，变成功能空转（挤出压力单测抓到）。
        boolean wantsPrune = tier.ordinal() >= WaterLevel.Tier.W2.ordinal();
        W2Result w2 = wantsPrune
                ? pruneRounds(input, afterW1, budget, targetTokens(budget, policy), counter)
                : new W2Result(afterW1, null, 0);
        List<ModelMessage> kept = w2.messages();
        String notice = w2.notice();
        int droppedRounds = w2.droppedRounds();
        boolean fallback = false;
        String fallbackReason = null;
        String summaryText = null;
        if (droppedRounds > 0) {
            currentTier = WaterLevel.Tier.W2.ordinal();
        }

        // ── W3：增量结构化摘要（一次 LLM，由外部摘要器产出）────────────
        boolean wantsSummary = (tier == WaterLevel.Tier.W3 || hard)
                && policy.strategy() == ContextPolicy.Strategy.TIERED;
        if (wantsSummary) {
            List<ModelMessage> delta = messagesAfter(input.messages(), input.upToSeq());
            Optional<String> merged;
            try {
                merged = summarizer.summarize(delta, input.oldSummary());
            } catch (RuntimeException e) {
                merged = Optional.empty();
            }
            if (merged.isPresent() && !merged.get().isEmpty()) {
                summaryText = merged.get();
                currentTier = WaterLevel.Tier.W3.ordinal();
            } else {
                // ⛔ 摘要失败：回落 W2，如实标记，绝不伪造摘要。
                fallback = true;
                fallbackReason = FALLBACK_SUMMARIZER_FAILED;
            }
        }

        // ── 二次硬顶校验：after ≤ window - reserve ────────────────────
        CompactedBlocks blocks = blocks(input, kept, notice, summaryText);
        int after = estimate(input, blocks.historyMessages(), notice, summaryText);
        if (after > budget.hardLimit()) {
            W2Result hardTrim = hardTrim(blocks, budget, counter);
            blocks = blocks(input, hardTrim.messages(), hardTrim.notice(), summaryText);
            notice = hardTrim.notice();
            droppedRounds += hardTrim.droppedRounds();
            after = estimate(input, blocks.historyMessages(), notice, summaryText);
            fallback = true;
            if (fallbackReason == null) {
                fallbackReason = FALLBACK_HARD_TRIM;
            }
            if (after > budget.hardLimit()) {
                throw new ContextBudgetException(
                        ContextBudgetException.Reason.CONTEXT_BUDGET_EXCEEDED,
                        "二次硬裁后 " + after + " 仍超硬顶 " + budget.hardLimit()
                                + "，固定块需瘦身（绝不静默截断）");
            }
        }

        WaterLevel.Tier finalTier = switch (currentTier) {
            case 1 -> WaterLevel.Tier.W1;
            case 2 -> WaterLevel.Tier.W2;
            case 3 -> WaterLevel.Tier.W3;
            default -> WaterLevel.Tier.W0;
        };
        return new CompactionResult(blocks, finalTier, fallback, fallbackReason,
                droppedRounds, snipped, before, after);
    }

    // ── W1 ────────────────────────────────────────────────────────────

    private record W1Result(@NonNull List<ModelMessage> messages,
                            @NonNull List<String> snippedRefs) {
    }

    /**
     * 把老 TOOL 结果替换为一行结论 + refId，保留 assistant 的 tool_calls 配对。
     * 判定「老」：index &lt;= upToSeq，或不在最近 recentTurns 轮内。
     *
     * <p>⚠️ <b>只外置「归档行严格短于原文」的 TOOL</b>（M5-b 实测补强）：
     * 归档行含 refId + 结论，本身有固定 token 成本。若某条 TOOL 原文很短
     * （如 {@code "ok"}），替换反而膨胀上下文 —— W1 变成「触发了却增 token」
     * 的功能空转。逐条按真实 counter 比较，只在外置确实省 token 时才做。
     */
    private static @NonNull W1Result snipOldToolResults(@NonNull List<ModelMessage> messages,
                                                        int recentTurns,
                                                        long upToSeq,
                                                        @NonNull TokenCounter counter) {
        List<RoundSegment> segs = RoundSplitter.split(messages);
        long droppableTurns = segs.stream().filter(RoundSegment::droppable).count();
        long oldTurnCut = Math.max(0, droppableTurns - Math.max(0, recentTurns));
        long turnIndex = 0;
        List<ModelMessage> out = new ArrayList<>();
        List<String> snipped = new ArrayList<>();
        for (RoundSegment seg : segs) {
            if (!seg.droppable()) {
                out.addAll(seg.messages()); // ANCHOR 永不外置
                continue;
            }
            boolean oldByTurn = turnIndex < oldTurnCut;
            turnIndex++;
            for (int k = 0; k < seg.messages().size(); k++) {
                ModelMessage m = seg.messages().get(k);
                int seq = seg.fromSeq() + k;
                if (m.role() == Role.TOOL && (oldByTurn || seq <= upToSeq)) {
                    String ref = "run_message:seq=" + seq;
                    // ⚠️ 只替换 content，保留 toolName / toolCallId / toolCalls 配对结构。
                    ModelMessage archived = new ModelMessage(Role.TOOL,
                            "[tool:" + nullToEmpty(m.toolName()) + "] 结果已归档（refId=" + ref
                                    + "），结论：" + oneLine(m.content()),
                            m.toolName(), m.toolCallId(), m.toolCalls());
                    // ⛔ 归档行更长 = 外置是净增 token：跳过（保留原文），不计入 snipped。
                    //    counter 只比较 content，配对开销两侧相同，不影响结论。
                    if (countContent(counter, archived.content()) >= countContent(counter, m.content())) {
                        out.add(m);
                        continue;
                    }
                    snipped.add(ref);
                    out.add(archived);
                } else {
                    out.add(m);
                }
            }
        }
        return new W1Result(List.copyOf(out), List.copyOf(snipped));
    }

    // ── W2 ────────────────────────────────────────────────────────────

    private record W2Result(@NonNull List<ModelMessage> messages,
                            @Nullable String notice,
                            int droppedRounds) {
    }

    /**
     * 整轮裁剪：从最早 TURN 丢到全窗口落入 {@code targetTokens}；ANCHOR 保留；生成占位符 notice。
     * ⚠️ target 是「压回到 targetRatio」的目标，不是硬顶；硬顶由二次校验单独兜底。
     * ⚠️ 若固定块已超 target（连 ANCHOR + 固定块都放不下），尽力丢完可丢轮即止，
     *    绝不丢 ANCHOR、绝不静默截断用户输入；超硬顶交给二次校验 fail-closed。
     */
    private static @NonNull W2Result pruneRounds(@NonNull Input input,
                                                 @NonNull List<ModelMessage> afterW1,
                                                 @NonNull ContextBudget budget,
                                                 int targetTokens,
                                                 @NonNull TokenCounter counter) {
        CompactionPipeline pipe = new CompactionPipeline(counter);
        if (pipe.estimate(input, afterW1, null, null) <= targetTokens) {
            return new W2Result(afterW1, null, 0);
        }
        List<RoundSegment> segs = RoundSplitter.split(afterW1);
        Set<String> droppedTools = new LinkedHashSet<>();
        List<String> droppedRefs = new ArrayList<>();
        int droppedRounds = 0;
        List<ModelMessage> current = afterW1;
        boolean[] dropped = new boolean[segs.size()];

        for (int i = 0; i < segs.size(); i++) {
            RoundSegment seg = segs.get(i);
            if (!seg.droppable()) {
                continue;
            }
            dropped[i] = true;
            droppedRounds++;
            droppedRefs.addAll(seg.sourceRefs());
            for (ModelMessage m : seg.messages()) {
                if (m.role() == Role.TOOL && m.toolName() != null) {
                    droppedTools.add(m.toolName());
                }
            }
            current = flatten(segs, dropped);
            if (pipe.estimate(input, current, null, null) <= targetTokens) {
                break;
            }
        }
        String notice = droppedRounds == 0 ? null
                : buildNotice(droppedRounds, droppedRefs, droppedTools);
        return new W2Result(current, notice, droppedRounds);
    }

    /** W2 裁剪目标：把用量压回 targetRatio × window（§2.3 / [D3]）。 */
    private static int targetTokens(@NonNull ContextBudget budget, @NonNull ContextPolicy policy) {
        return (int) (budget.window() * policy.targetRatio());
    }

    /** 二次硬顶：以当前 notice 为基线，再丢最早一半可丢轮（确定性），直到显著收缩。 */
    private static @NonNull W2Result hardTrim(@NonNull CompactedBlocks blocks,
                                              @NonNull ContextBudget budget,
                                              @NonNull TokenCounter counter) {
        List<RoundSegment> segs = RoundSplitter.split(blocks.historyMessages());
        int droppable = (int) segs.stream().filter(RoundSegment::droppable).count();
        if (droppable == 0) {
            return new W2Result(blocks.historyMessages(), blocks.notice(), 0);
        }
        int toDrop = Math.max(1, droppable / 2);
        Set<String> droppedTools = new LinkedHashSet<>();
        List<String> droppedRefs = new ArrayList<>();
        int dropped = 0;
        boolean[] drop = new boolean[segs.size()];
        for (int i = 0; i < segs.size() && dropped < toDrop; i++) {
            if (!segs.get(i).droppable()) {
                continue;
            }
            drop[i] = true;
            dropped++;
            droppedRefs.addAll(segs.get(i).sourceRefs());
            for (ModelMessage m : segs.get(i).messages()) {
                if (m.role() == Role.TOOL && m.toolName() != null) {
                    droppedTools.add(m.toolName());
                }
            }
        }
        String notice = mergeNotice(blocks.notice(),
                buildNotice(dropped, droppedRefs, droppedTools));
        return new W2Result(flatten(segs, drop), notice, dropped);
    }

    // ── 辅助 ──────────────────────────────────────────────────────────

    /** 只吃 index > upToSeq 的增量（NoRecursiveResummary）。 */
    static @NonNull List<ModelMessage> messagesAfter(@NonNull List<ModelMessage> messages,
                                                     long upToSeq) {
        List<ModelMessage> delta = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (i > upToSeq) {
                delta.add(messages.get(i));
            }
        }
        return List.copyOf(delta);
    }

    private static @NonNull List<ModelMessage> flatten(@NonNull List<RoundSegment> segs,
                                                       @NonNull boolean[] dropped) {
        List<ModelMessage> out = new ArrayList<>();
        for (int i = 0; i < segs.size(); i++) {
            if (!dropped[i]) {
                out.addAll(segs.get(i).messages());
            }
        }
        return List.copyOf(out);
    }

    private static @NonNull CompactedBlocks blocks(@NonNull Input input,
                                                   @NonNull List<ModelMessage> history,
                                                   @Nullable String notice,
                                                   @Nullable String summary) {
        return new CompactedBlocks(input.systemPrompt(), input.toolSchemas(),
                input.firstUserTurn(), notice, summary, input.ragBlocks(),
                input.memoryBlock(), history, input.currentInput());
    }

    /**
     * 全窗口估算 = {@link ContextLayout#countRendered}（先渲染再计数）。
     *
     * <p>⚠️ <b>estimate 与 render 必须共用唯一口径</b>：曾出现本方法漏算
     * {@code firstUserTurn}（布局会发、估算不算）导致预算静默低估。改为
     * 「组装 CompactedBlocks → 渲染 → 计数」后，布局每加一块估算自动跟随，
     * 结构上不可能漏算首轮锚 / 围栏标记 / 当前输入。
     */
    private int estimate(@NonNull Input input, @NonNull List<ModelMessage> history,
                         @Nullable String notice, @Nullable String summary) {
        return ContextLayout.countRendered(blocks(input, history, notice, summary), counter);
    }

    private static @NonNull String buildNotice(int droppedRounds,
                                               @NonNull List<String> droppedRefs,
                                               @NonNull Set<String> droppedTools) {
        StringBuilder b = new StringBuilder();
        b.append("（上下文预算裁剪：前 ").append(droppedRounds)
                .append(" 轮工具交互已移除，引用：").append(String.join(",", droppedRefs)).append("。");
        if (!droppedTools.isEmpty()) {
            b.append("期间已调用过：").append(String.join("、", droppedTools))
                    .append("。请勿重复调用。");
        }
        b.append("移除是系统侧行为，任务本身未变。");
        return b.toString();
    }

    private static @NonNull String mergeNotice(@Nullable String old, @NonNull String extra) {
        return old == null || old.isEmpty() ? extra : old + "\n" + extra;
    }

    private static @NonNull String oneLine(@NonNull String text) {
        if (text == null || text.isEmpty()) {
            return "（空结果）";
        }
        String first = text.lines().findFirst().orElse(text).trim();
        return first.length() > 60 ? first.substring(0, 60) + "…" : first;
    }

    /** 用真实 counter 计量单条 content（隔离出配对字段，两侧开销相同）。 */
    private static int countContent(@NonNull TokenCounter counter, @NonNull String content) {
        return counter.count(new ContextBlocks("", "", "", List.of(), List.of(content), ""));
    }

    private static @NonNull String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
