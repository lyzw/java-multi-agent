package cn.studykid.multiagent.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.studykid.multiagent.context.WaterLevel.Thresholds;
import cn.studykid.multiagent.context.WaterLevel.Tier;
import cn.studykid.multiagent.ports.model.ModelMessage;
import cn.studykid.multiagent.ports.model.ModelMessage.Role;
import cn.studykid.multiagent.ports.model.ModelMessage.ToolCallEcho;

/**
 * 压缩管线行为守卫（M5-b / 《方案》§5.3 / §6.4-③）。
 *
 * <p>⚠️ 覆盖 W0/W1/W2/W3/HARD 与二次硬顶失败，含「摘要失败不伪造」「增量只吃新轮」
 * 「配对不破」「归档净省 token」「首轮锚计入预算」等关键语义。
 *
 * <p>⚠️ <b>窗口不写魔数</b>：先用超大窗口（W0 必经）测出全窗口 {@code before}，
 * 再按 {@code window = ceil(before / targetUsage)} 反推，使 usage 精确落入目标水位带。
 * 早期版本硬编码 1000/1200/2000，token 口径一变就集体错位（5 个用例假红），
 * 且「水位触发却零裁剪」的功能空转不会被发现。
 */
class CompactionPipelineTest {

    private static final String ANCHOR = "原始任务：解方程 2x+3=7";
    private static final String SUMMARY = "{\"goal\":\"解方程\"}";

    private final HeuristicTokenCounter counter = new HeuristicTokenCounter();
    private static final Thresholds T = Thresholds.defaults();

    /** 摘要器桩：可控地返回摘要 / empty / 抛异常，并记录收到的增量。 */
    private static final class StubSummarizer implements CompactionPipeline.Summarizer {
        Optional<String> result = Optional.of(SUMMARY);
        RuntimeException throwThis = null;
        List<ModelMessage> lastDelta = List.of();

        @Override
        public Optional<String> summarize(List<ModelMessage> delta, String oldSummary) {
            lastDelta = delta;
            if (throwThis != null) {
                throw throwThis;
            }
            return result;
        }
    }

    private static ContextPolicy policy(int window, double trigger, double target) {
        return policy(window, trigger, target, ContextPolicy.defaults().recentTurns());
    }

    private static ContextPolicy policy(int window, double trigger, double target, int recentTurns) {
        ContextPolicy d = ContextPolicy.defaults();
        return new ContextPolicy(true, window, trigger, target, d.reserveRatio(),
                recentTurns, d.pinFirstUserTurn(), d.ragRatio(), d.memoryRatio(),
                d.summaryRatio(), d.strategy(), d.summarizerModel(), d.summaryMaxTokens(),
                d.summaryTimeoutMs(), d.maxCompactionsPerRun(), d.carryOver(),
                d.checkpointTtlSeconds(), d.policyVersion());
    }

    /** 一条工具轮次：ASSISTANT(tool_calls) + TOOL(result)，配对字段齐全。 */
    private static void addRound(List<ModelMessage> msgs, int n, int toolChars) {
        msgs.add(ModelMessage.assistantToolCalls("调用工具 " + n,
                List.of(new ToolCallEcho("call-" + n, "tool-" + n, "{}"))));
        msgs.add(ModelMessage.toolResult("tool-" + n,
                "工具" + n + "结果" + "x".repeat(toolChars), "call-" + n));
    }

    /**
     * 构造输入：<b>首轮锚只在 {@code firstUserTurn}</b>（独立 pinned 块，§5.2），
     * {@code messages} 只含其后的工具轮次 —— ⛔ 绝不把锚复制进 messages[0]，
     * 否则 ContextLayout 渲染时锚出现两次，estimate 也双算。
     */
    private static CompactionPipeline.Input input(String sys, int rounds, int toolChars) {
        List<ModelMessage> msgs = new ArrayList<>();
        for (int i = 0; i < rounds; i++) {
            addRound(msgs, i, toolChars);
        }
        return new CompactionPipeline.Input(sys, "{}", ANCHOR,
                List.of(), "", msgs, "当前输入", -1, null);
    }

    private static BudgetAllocator.FixedBlocks fixed(CompactionPipeline.Input in) {
        return new BudgetAllocator.FixedBlocks(in.systemPrompt(), in.toolSchemas(),
                in.firstUserTurn(), in.currentInput());
    }

    /** 超大窗口 → W0 必经 → beforeTokens 即「全窗口估算」（含首轮锚 / 围栏 / 当前输入）。 */
    private int fullBefore(CompactionPipeline.Input in) {
        ContextPolicy wide = policy(1_000_000, 0.70, 0.40);
        return new CompactionPipeline(counter).compact(in,
                BudgetAllocator.allocate(wide, counter, fixed(in)), wide, T,
                new StubSummarizer()).beforeTokens();
    }

    private static int windowFor(int before, double targetUsage) {
        return (int) Math.ceil(before / targetUsage);
    }

    @Test
    @DisplayName("✅ W0：usage=0.30 → 原样返回，零裁剪零 fallback")
    void w0PassThrough() {
        CompactionPipeline.Input in = input("系统", 2, 400);
        int before = fullBefore(in);
        ContextPolicy p = policy(windowFor(before, 0.30), 0.70, 0.40);
        ContextBudget budget = BudgetAllocator.allocate(p, counter, fixed(in));

        CompactionResult r = new CompactionPipeline(counter)
                .compact(in, budget, p, T, new StubSummarizer());

        assertThat(r.tier()).isEqualTo(Tier.W0);
        assertThat(r.fallback()).isFalse();
        assertThat(r.droppedRounds()).isZero();
        assertThat(r.snippedTools()).isEmpty();
        assertThat(r.blocks().historyMessages()).isEqualTo(in.messages());
        assertThat(r.blocks().firstUserTurn()).isEqualTo(in.firstUserTurn());
    }

    @Test
    @DisplayName("✅ W1：usage=0.65 → 老 TOOL 可逆外置，配对不破，归档净省 token")
    void w1SnipsOldToolResults() {
        CompactionPipeline.Input in = input("系统", 8, 400);
        int before = fullBefore(in);
        ContextPolicy p = policy(windowFor(before, 0.65), 0.70, 0.40, 2);
        ContextBudget budget = BudgetAllocator.allocate(p, counter, fixed(in));

        CompactionResult r = new CompactionPipeline(counter)
                .compact(in, budget, p, T, new StubSummarizer());

        assertThat(r.tier()).isEqualTo(Tier.W1);
        assertThat(r.snippedTools()).as("⛔ W1 未外置任何老工具结果").isNotEmpty();
        // ⛔ 归档行若比原文长，W1 会「触发了却增 token」= 功能空转；必须净下降。
        assertThat(r.afterTokens()).as("⛔ W1 归档后 token 不降反升").isLessThan(r.beforeTokens());
        // 外置只替换 TOOL 的 content，不动 assistant 的 tool_calls 配对。
        List<ModelMessage> hist = r.blocks().historyMessages();
        long assistantWithCalls = hist.stream().filter(m ->
                m.role() == Role.ASSISTANT && !m.toolCalls().isEmpty()).count();
        assertThat(assistantWithCalls).as("⛔ assistant 工具调用意图被破坏").isEqualTo(8);
        assertThat(hist).anySatisfy(m -> {
            if (m.role() == Role.TOOL && m.content().contains("结果已归档")) {
                assertThat(m.toolCallId()).isNotNull();
                assertThat(m.toolName()).startsWith("tool-");
            }
        });
        assertThat(r.snippedTools().get(0)).startsWith("run_message:seq=");
    }

    @Test
    @DisplayName("✅ W1：短 TOOL 结果不归档（归档行更长 = 净增 token，必须跳过）")
    void w1DoesNotInflateShortToolResults() {
        CompactionPipeline.Input in = input("系统", 8, 3);
        int before = fullBefore(in);
        ContextPolicy p = policy(windowFor(before, 0.65), 0.70, 0.40, 2);
        ContextBudget budget = BudgetAllocator.allocate(p, counter, fixed(in));

        CompactionResult r = new CompactionPipeline(counter)
                .compact(in, budget, p, T, new StubSummarizer());

        assertThat(r.tier()).isEqualTo(Tier.W1);
        assertThat(r.snippedTools()).as("⛔ 短结果被归档成更长的行，W1 反向膨胀").isEmpty();
        assertThat(r.blocks().historyMessages()).isEqualTo(in.messages());
        assertThat(r.afterTokens()).isEqualTo(r.beforeTokens());
    }

    @Test
    @DisplayName("✅ W2：usage=0.80 → 整轮裁剪，首轮锚逐字保留，配对不破，留痕")
    void w2PrunesWholeRounds() {
        CompactionPipeline.Input in = input("系统", 24, 400);
        int before = fullBefore(in);
        ContextPolicy p = policy(windowFor(before, 0.80), 0.70, 0.40);
        ContextBudget budget = BudgetAllocator.allocate(p, counter, fixed(in));

        CompactionResult r = new CompactionPipeline(counter)
                .compact(in, budget, p, T, new StubSummarizer());

        assertThat(r.tier()).isEqualTo(Tier.W2);
        assertThat(r.droppedRounds()).as("⛔ 超预算却未整轮裁剪").isGreaterThan(0);
        // 首轮锚逐字保留（ANCHOR 永不裁），且渲染后恰好出现一次。
        assertThat(r.blocks().firstUserTurn()).isEqualTo(in.firstUserTurn());
        List<ModelMessage> rendered = ContextLayout.render(r.blocks()).messages();
        assertThat(rendered).filteredOn(m -> m.content().contains("原始任务：解方程"))
                .as("⛔ 首轮锚丢失或被重复渲染").hasSize(1);
        // 无孤儿 TOOL：每个 TOOL 前面必有 ASSISTANT（配对完整）。
        boolean prevAssistant = false;
        for (ModelMessage m : r.blocks().historyMessages()) {
            if (m.role() == Role.TOOL) {
                assertThat(prevAssistant).as("⛔ 出现孤立 TOOL，供应商会 400").isTrue();
            }
            prevAssistant = m.role() == Role.ASSISTANT;
        }
        assertThat(r.blocks().notice()).as("⛔ 裁剪未留痕，模型会重复调用工具").contains("请勿重复调用");
        assertThat(r.afterTokens()).as("⛔ after 超硬顶").isLessThanOrEqualTo(budget.hardLimit());
    }

    @Test
    @DisplayName("✅ W3：usage=0.95 → 命中摘要水位，摘要块进 blocks（外部注入，本模块零 LLM）")
    void w3UsesInjectedSummary() {
        CompactionPipeline.Input in = input("系统", 6, 400);
        int before = fullBefore(in);
        ContextPolicy p = policy(windowFor(before, 0.95), 0.70, 0.40);
        ContextBudget budget = BudgetAllocator.allocate(p, counter, fixed(in));

        CompactionResult r = new CompactionPipeline(counter)
                .compact(in, budget, p, T, new StubSummarizer());

        assertThat(r.tier()).isEqualTo(Tier.W3);
        assertThat(r.blocks().summaryText()).as("⛔ W3 未使用注入的摘要").isEqualTo(SUMMARY);
        assertThat(r.fallback()).isFalse();
    }

    @Test
    @DisplayName("✅ 摘要失败（返回 empty）→ 回落 W2，fallback=true，绝不伪造摘要")
    void summarizerEmptyFallsBackNoFabrication() {
        CompactionPipeline.Input in = input("系统", 6, 400);
        int before = fullBefore(in);
        ContextPolicy p = policy(windowFor(before, 0.95), 0.70, 0.40);
        ContextBudget budget = BudgetAllocator.allocate(p, counter, fixed(in));
        StubSummarizer sum = new StubSummarizer();
        sum.result = Optional.empty();

        CompactionResult r = new CompactionPipeline(counter).compact(in, budget, p, T, sum);

        assertThat(r.fallback()).as("⛔ 摘要失败未标记 fallback").isTrue();
        assertThat(r.fallbackReason()).isEqualTo(CompactionPipeline.FALLBACK_SUMMARIZER_FAILED);
        assertThat(r.blocks().summaryText()).as("⛔ 摘要失败却塞了伪造摘要").isNull();
    }

    @Test
    @DisplayName("✅ 摘要器抛异常 → 降级不冒泡，回落 W2 并标记 fallback")
    void summarizerExceptionDegrades() {
        CompactionPipeline.Input in = input("系统", 6, 400);
        int before = fullBefore(in);
        ContextPolicy p = policy(windowFor(before, 0.95), 0.70, 0.40);
        ContextBudget budget = BudgetAllocator.allocate(p, counter, fixed(in));
        StubSummarizer sum = new StubSummarizer();
        sum.throwThis = new RuntimeException("model timeout");

        CompactionResult r = new CompactionPipeline(counter).compact(in, budget, p, T, sum);

        assertThat(r.fallback()).isTrue();
        assertThat(r.fallbackReason()).isEqualTo(CompactionPipeline.FALLBACK_SUMMARIZER_FAILED);
        assertThat(r.blocks().summaryText()).isNull();
    }

    @Test
    @DisplayName("✅ 增量只吃 index > upToSeq（NoRecursiveResummary）")
    void deltaOnlyAfterUpToSeq() {
        List<ModelMessage> msgs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            addRound(msgs, i, 400);
        }
        CompactionPipeline.Input in = new CompactionPipeline.Input(
                "系统", "{}", ANCHOR, List.of(), "", msgs, "当前输入", 8, null);
        int before = fullBefore(in);
        ContextPolicy p = policy(windowFor(before, 0.95), 0.70, 0.40);
        ContextBudget budget = BudgetAllocator.allocate(p, counter, fixed(in));
        StubSummarizer sum = new StubSummarizer();

        new CompactionPipeline(counter).compact(in, budget, p, T, sum);

        assertThat(sum.lastDelta).as("⛔ 摘要吃了旧轮：递归重摘要会重复计费")
                .isNotEmpty().isEqualTo(msgs.subList(9, msgs.size()));
        assertThat(sum.lastDelta).as("⛔ 首轮锚不应进入增量")
                .allSatisfy(m -> assertThat(m.content()).doesNotContain("原始任务"));
    }

    @Test
    @DisplayName("✅ 全窗口估算计入首轮锚：布局会发、估算必须算（防预算静默低估 RV）")
    void firstUserTurnIsCountedInFullWindow() {
        CompactionPipeline.Input empty = new CompactionPipeline.Input(
                "系统", "{}", "", List.of(), "", List.of(), "当前输入", -1, null);
        CompactionPipeline.Input big = new CompactionPipeline.Input(
                "系统", "{}", "锚".repeat(300), List.of(), "", List.of(), "当前输入", -1, null);

        int emptyBefore = fullBefore(empty);
        int bigBefore = fullBefore(big);

        assertThat(bigBefore - emptyBefore)
                .as("⛔ estimate 漏算 firstUserTurn：布局会发、估算不算，预算静默低估")
                .isGreaterThanOrEqualTo(300);
    }

    @Test
    @DisplayName("✅ 固定块超硬顶 → CONTEXT_BUDGET_EXCEEDED（fail-closed，不静默截断）")
    void hardOverflowFailsClosed() {
        // 固定块本身就超硬顶：超大 system + 极小窗口。
        CompactionPipeline.Input in = input("系统指令".repeat(200), 3, 20);
        ContextPolicy p = policy(60, 0.70, 0.40);
        ContextBudget budget = new ContextBudget(60, 6, 50, 4, 1, 0, 1, 2);

        assertThatThrownBy(() -> new CompactionPipeline(counter)
                .compact(in, budget, p, T, new StubSummarizer()))
                .isInstanceOf(ContextBudgetException.class)
                .extracting(e -> ((ContextBudgetException) e).reason())
                .isEqualTo(ContextBudgetException.Reason.CONTEXT_BUDGET_EXCEEDED);
    }

    @Test
    @DisplayName("✅ HARD 水位但固定块够小 → 收敛不抛，after ≤ hardLimit")
    void hardTierConvergesWithoutThrow() {
        CompactionPipeline.Input in = input("系统", 10, 400);
        int before = fullBefore(in);
        ContextPolicy p = policy(windowFor(before, 1.20), 0.70, 0.40);
        ContextBudget budget = BudgetAllocator.allocate(p, counter, fixed(in));

        CompactionResult r = new CompactionPipeline(counter)
                .compact(in, budget, p, T, new StubSummarizer());

        assertThat(r.blocks().historyMessages()).as("⛔ HARD 水位未裁剪").isNotEqualTo(in.messages());
        assertThat(r.afterTokens()).as("⛔ 二次硬裁后仍超硬顶").isLessThanOrEqualTo(budget.hardLimit());
    }
}
