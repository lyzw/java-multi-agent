package cn.studykid.multiagent.context;

import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.ports.context.ContextBlocks;
import cn.studykid.multiagent.ports.context.TokenCounter;

/**
 * 全窗口 Token 预算分配（《方案》§5.2 / §6.4-①，M5-a）。
 *
 * <p>⚠️ <b>固定块超窗一律 fail-closed</b>：{@code pinned + reserve > window} 抛
 * {@link ContextBudgetException.Reason#CONTEXT_PINNED_OVERFLOW}，提示「系统提示 / 工具定义需瘦身」，
 * ⛔ 绝不静默夹紧、绝不截断用户输入（那会丢任务锚）。
 *
 * <p>⚠️ <b>工具 Schema 必须计入 pinned</b>（RV-1 反向验证点）：漏算它会让预算退回
 * 「只看消息序列」的旧口径，供应商侧静默超窗。{@code HeuristicTokenCounter} 已按六块计数，
 * 这里把 tools 放进 {@link ContextBlocks#toolSchemas()} 块即强制计入。
 *
 * <p>⚠️ <b>三个比例是上限 cap，未用额度回流历史</b>（[D2]）：{@code historyCap} 按
 * RAG / 记忆 / 摘要的<b>实际用量</b>（各自 clamp 到 cap）回填，避免「2 个命中白占 30%」。
 *
 * <p>⚠️ 纯函数：不读时钟 / 租户 / 配置 / DB；同一输入恒等输出。
 */
public final class BudgetAllocator {

    /**
     * 围栏标记 + 消息封装的额外 token 预留。取偏保守一侧（高估 → 早压缩）。
     * ⚠️ 守卫测试不引用本常量，用行为断言锁定，避免「改常量=改测试」的自证。
     */
    static final int GUARD_OVERHEAD = 32;

    private BudgetAllocator() {
    }

    /** 不可压缩的固定块（system + tools + 首轮任务锚 + 当前输入）。 */
    public record FixedBlocks(
            @NonNull String systemPrompt,
            @NonNull String toolSchemas,
            @NonNull String firstUserTurn,
            @NonNull String currentInput) {
        public FixedBlocks {
            systemPrompt = systemPrompt == null ? "" : systemPrompt;
            toolSchemas = toolSchemas == null ? "" : toolSchemas;
            firstUserTurn = firstUserTurn == null ? "" : firstUserTurn;
            currentInput = currentInput == null ? "" : currentInput;
        }
    }

    /**
     * 按 cap 分配预算（三块都吃满时的历史保底），渲染阶段再按实际用量回填 historyCap。
     *
     * @param policy 已由 {@link ContextPolicyResolver} 裁决的策略（window / reserve 已定）
     * @param counter 全窗口计数器
     * @param fixed   固定块
     * @return 预算分配
     * @throws ContextBudgetException 固定块 + 预留超出窗口（fail-closed）
     */
    public static @NonNull ContextBudget allocate(@NonNull ContextPolicy policy,
                                                  @NonNull TokenCounter counter,
                                                  @NonNull FixedBlocks fixed) {
        // ⚠️ 三块传「满额」→ clamp 到各自 cap → historyCap 得到**最坏情况保底**
        //   （RAG/记忆/摘要都吃满时历史还能拿到的最小值）。渲染阶段按实际用量回填只会
        //   让 historyCap 变大（额度回流），绝不会让分配阶段虚高到渲染后超预算。
        return allocate(policy, counter, fixed,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /**
     * 按实际用量回填 historyCap 的分配（§5.2 额度回流）。
     *
     * @param actualRagTokens     RAG 实际用量（自行 clamp 到 ragCap）
     * @param actualMemoryTokens  记忆实际用量
     * @param actualSummaryTokens 摘要实际用量
     */
    public static @NonNull ContextBudget allocate(@NonNull ContextPolicy policy,
                                                  @NonNull TokenCounter counter,
                                                  @NonNull FixedBlocks fixed,
                                                  int actualRagTokens,
                                                  int actualMemoryTokens,
                                                  int actualSummaryTokens) {
        int window = policy.maxInputTokens();
        if (window <= 0) {
            throw new ContextBudgetException(
                    ContextBudgetException.Reason.CONTEXT_BUDGET_EXCEEDED,
                    "有效窗口必须为正数: " + window);
        }
        int reserve = (int) Math.ceil(window * policy.reserveRatio());

        // ⚠️ 让计数器按「system + tools + 首轮锚 + 当前输入」六块口径计 pinned。
        //    toolSchemas 在此计入（RV-1），firstUserTurn / currentInput 永不裁剪故也计入。
        ContextBlocks pinnedBlocks = new ContextBlocks(
                fixed.systemPrompt(), fixed.toolSchemas(), "",
                List.of(), List.of(fixed.firstUserTurn()), fixed.currentInput());
        int pinned = counter.count(pinnedBlocks) + GUARD_OVERHEAD;

        if ((long) pinned + reserve > window) {
            throw new ContextBudgetException(
                    ContextBudgetException.Reason.CONTEXT_PINNED_OVERFLOW,
                    "固定块 " + pinned + " + 预留 " + reserve + " 超过窗口 " + window
                            + "，系统提示 / 工具定义需瘦身");
        }
        int elastic = window - reserve - pinned;
        int ragCap = (int) (elastic * policy.ragRatio());
        int memoryCap = (int) (elastic * policy.memoryRatio());
        int summaryCap = (int) (elastic * policy.summaryRatio());

        int usedRag = clamp(actualRagTokens, ragCap);
        int usedMemory = clamp(actualMemoryTokens, memoryCap);
        int usedSummary = clamp(actualSummaryTokens, summaryCap);
        int historyCap = elastic - usedRag - usedMemory - usedSummary;

        return new ContextBudget(window, reserve, pinned, elastic,
                ragCap, memoryCap, summaryCap, historyCap);
    }

    private static int clamp(int value, int cap) {
        if (value <= 0) {
            return 0;
        }
        return Math.min(value, cap);
    }
}
