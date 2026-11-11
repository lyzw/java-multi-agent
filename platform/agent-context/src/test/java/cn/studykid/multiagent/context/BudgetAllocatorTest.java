package cn.studykid.multiagent.context;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.studykid.multiagent.ports.context.ContextBlocks;
import cn.studykid.multiagent.ports.context.TokenCounter;

/**
 * 水位填充预算分配守卫（M5-a / 《方案》§5.2 [D2]，RV-1）。
 *
 * <p>⚠️ 断言不引用被测实现自己的常量（{@code GUARD_OVERHEAD} 等），
 * 全部用行为关系 + 字面量，撤掉修复点必须变红。
 */
class BudgetAllocatorTest {

    private final TokenCounter counter = new HeuristicTokenCounter();

    private static ContextPolicy policy(int window, double reserve, double rag,
                                        double memory, double summary) {
        return ContextPolicy.defaults()
                .withMaxInputTokens(window)
                .withReserveRatio(reserve);
        // rag/memory/summary 用 defaults 的 0.30/0.15/0.25；需要不同比例时另造全参。
    }

    private static ContextPolicy policyWithRatios(int window, double reserve,
                                                  double rag, double memory, double summary) {
        ContextPolicy d = ContextPolicy.defaults();
        return new ContextPolicy(true, window, d.triggerRatio(), d.targetRatio(), reserve,
                d.recentTurns(), d.pinFirstUserTurn(), rag, memory, summary,
                d.strategy(), d.summarizerModel(), d.summaryMaxTokens(), d.summaryTimeoutMs(),
                d.maxCompactionsPerRun(), d.carryOver(), d.checkpointTtlSeconds(),
                d.policyVersion());
    }

    private static BudgetAllocator.FixedBlocks fixed(String sys, String tools,
                                                     String first, String current) {
        return new BudgetAllocator.FixedBlocks(sys, tools, first, current);
    }

    @Test
    @DisplayName("✅ 预算分区自洽：window = reserve + pinned + elastic；三 cap + history 守恒")
    void budgetPartitionsAreConsistent() {
        ContextPolicy p = policyWithRatios(1_000, 0.10, 0.30, 0.15, 0.25);
        ContextBudget b = BudgetAllocator.allocate(p, counter,
                fixed("system", "{}", "首轮任务", "当前问题"));

        assertThat(b.window()).isEqualTo(1_000);
        assertThat(b.reserve()).isEqualTo(100); // ceil(1000 * 0.10)
        assertThat(b.reserve() + b.pinned() + b.elastic())
                .as("⛔ 预算分区不守恒：reserve+pinned+elastic 必须等于 window")
                .isEqualTo(b.window());
        assertThat(b.historyCap())
                .as("⛔ 三块吃满时 historyCap 应等于 elastic 扣掉三个 cap")
                .isEqualTo(b.elastic() - b.ragCap() - b.memoryCap() - b.summaryCap());
        assertThat(b.hardLimit()).isEqualTo(b.window() - b.reserve());
    }

    @Test
    @DisplayName("✅ RV-1：工具 Schema 计入 pinned — 撤掉即触发 pinned overflow")
    void toolSchemasAreCountedInPinned() {
        // 用一个大到「只有算上 tools 才会溢出」的 Schema 造挤出压力。
        String hugeTools = "{\"parameters\":{\"p\":\"string\"}}".repeat(60); // ~1800 ASCII → 600+ token
        ContextPolicy p = policy(400, 0.05, 0.30, 0.15, 0.25);

        assertThatThrownBy(() -> BudgetAllocator.allocate(p, counter,
                fixed("s", hugeTools, "锚", "q")))
                .as("⛔ tools 未计入 pinned：巨大工具 Schema 被漏算，静默超窗（RV-1 反证）")
                .isInstanceOf(ContextBudgetException.class)
                .extracting(e -> ((ContextBudgetException) e).reason())
                .isEqualTo(ContextBudgetException.Reason.CONTEXT_PINNED_OVERFLOW);
    }

    @Test
    @DisplayName("✅ fail-closed：固定块 + 预留超窗口抛 CONTEXT_PINNED_OVERFLOW（不夹紧）")
    void pinnedOverflowFailsClosed() {
        ContextPolicy p = policy(50, 0.10, 0.30, 0.15, 0.25);
        String longSystem = "系统指令".repeat(40); // 160 汉字 → >50 token

        assertThatThrownBy(() -> BudgetAllocator.allocate(p, counter,
                fixed(longSystem, "{}", "锚", "q")))
                .isInstanceOf(ContextBudgetException.class)
                .extracting(e -> ((ContextBudgetException) e).reason())
                .isEqualTo(ContextBudgetException.Reason.CONTEXT_PINNED_OVERFLOW);
    }

    @Test
    @DisplayName("✅ 额度回流：RAG/记忆/摘要用得少 → historyCap 回填（非固定切块）")
    void unusedQuotaFlowsBackToHistory() {
        ContextPolicy p = policyWithRatios(2_000, 0.10, 0.30, 0.15, 0.25);
        ContextBudget full = BudgetAllocator.allocate(p, counter,
                fixed("s", "{}", "锚", "q"));
        ContextBudget partial = BudgetAllocator.allocate(p, counter,
                fixed("s", "{}", "锚", "q"), 5, 0, 0); // RAG 只用 5

        assertThat(partial.historyCap())
                .as("⛔ RAG 未用额度未回流历史：退回固定切块（[D2] 关键差别）")
                .isGreaterThan(full.historyCap());
        assertThat(partial.historyCap()).isLessThanOrEqualTo(partial.elastic());
    }

    @Test
    @DisplayName("✅ 回填不越界：超 cap 的用量被 clamp，historyCap 永不为负")
    void backfillNeverNegative() {
        ContextPolicy p = policyWithRatios(1_000, 0.10, 0.30, 0.15, 0.25);
        ContextBudget b = BudgetAllocator.allocate(p, counter,
                fixed("s", "{}", "锚", "q"),
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);

        assertThat(b.historyCap()).isGreaterThanOrEqualTo(0);
        assertThat(b.ragCap()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("✅ 确定性：同一输入两次分配逐字段一致")
    void deterministic() {
        ContextPolicy p = policy(1_000, 0.10, 0.30, 0.15, 0.25);
        BudgetAllocator.FixedBlocks f = fixed("sys", "{}", "锚", "q");
        assertThat(BudgetAllocator.allocate(p, counter, f))
                .isEqualTo(BudgetAllocator.allocate(p, counter, f));
    }

    @Test
    @DisplayName("✅ withHistoryCap 逐字段带过（全参拷贝，铁律 #11）")
    void withHistoryCapPreservesAllFields() {
        ContextBudget b = BudgetAllocator.allocate(
                policyWithRatios(2_000, 0.10, 0.30, 0.15, 0.25), counter,
                fixed("s", "{}", "锚", "q"), 3, 4, 5);
        ContextBudget changed = b.withHistoryCap(777);

        assertThat(changed.window()).isEqualTo(b.window());
        assertThat(changed.reserve()).isEqualTo(b.reserve());
        assertThat(changed.pinned()).isEqualTo(b.pinned());
        assertThat(changed.elastic()).isEqualTo(b.elastic());
        assertThat(changed.ragCap()).isEqualTo(b.ragCap());
        assertThat(changed.memoryCap()).isEqualTo(b.memoryCap());
        assertThat(changed.summaryCap()).isEqualTo(b.summaryCap());
        assertThat(changed.historyCap()).isEqualTo(777);
    }
}
