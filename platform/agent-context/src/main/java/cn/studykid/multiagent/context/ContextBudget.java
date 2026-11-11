package cn.studykid.multiagent.context;

import org.jspecify.annotations.NonNull;

/**
 * 全窗口 Token 预算的分配结果（《方案》§5.2 水位填充 [D2]，M5-a）。
 *
 * <p>⚠️ <b>为什么是「水位填充」而非「固定切块」</b>：三个比例
 * （{@code ragRatio}/{@code memoryRatio}/{@code summaryRatio}）是<b>上限 cap 而非目标配额</b>。
 * 某块没用完的额度必须回流给历史（{@code historyCap} 在渲染时按实际用量回填），
 * 否则「RAG 只有 2 个命中却白占 30% 预算」会静默浪费上下文。
 *
 * <p>⚠️ <b>全参构造是唯一构造 + 唯一拷贝点</b>（铁律 #11 / 项目事故）：
 * 所有投影必须经 {@link #copy}，新增字段时签名一变、所有 with 方法编译失败，
 * 强制逐字段带过，杜绝「加了字段却静默清成默认」。
 *
 * @param window      有效输入硬顶（已由 {@link ContextPolicyResolver} 三层 MIN 裁决）
 * @param reserve     估算误差 + 输出预留（{@code ceil(window × reserveRatio)}）
 * @param pinned      不可压缩固定块（system + tools + 首轮锚 + 当前输入 + 围栏开销）
 * @param elastic     可压缩弹性池 = {@code window - reserve - pinned}
 * @param ragCap      RAG 命中块 token 上限
 * @param memoryCap   跨 Run 记忆块 token 上限
 * @param summaryCap  结构化摘要块 token 上限
 * @param historyCap  历史对话 token 保底 = {@code elastic - ragCap - memoryCap - summaryCap}
 */
public record ContextBudget(
        int window,
        int reserve,
        int pinned,
        int elastic,
        int ragCap,
        int memoryCap,
        int summaryCap,
        int historyCap) {

    public ContextBudget {
        if (window <= 0) {
            throw new IllegalArgumentException("有效窗口必须为正数: " + window);
        }
        if (reserve < 0 || pinned < 0 || elastic < 0) {
            throw new IllegalArgumentException("预算分量不得为负: reserve=" + reserve
                    + " pinned=" + pinned + " elastic=" + elastic);
        }
        if (ragCap < 0 || memoryCap < 0 || summaryCap < 0 || historyCap < 0) {
            throw new IllegalArgumentException("配额不得为负: rag=" + ragCap
                    + " memory=" + memoryCap + " summary=" + summaryCap + " history=" + historyCap);
        }
    }

    /**
     * ⚠️ 本 record 所有投影必须经此唯一拷贝点（铁律 #11）；见类注释。
     */
    private @NonNull ContextBudget copy(
            int window, int reserve, int pinned, int elastic,
            int ragCap, int memoryCap, int summaryCap, int historyCap) {
        return new ContextBudget(window, reserve, pinned, elastic,
                ragCap, memoryCap, summaryCap, historyCap);
    }

    /**
     * 渲染阶段按 RAG / 记忆 / 摘要的<b>实际用量</b>回填历史保底配额（§5.2 未用额度回流）。
     * 这是唯一合法的历史配额写入口；分配时给的是「三块都吃满」的保底值。
     */
    public @NonNull ContextBudget withHistoryCap(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("历史配额不得为负: " + value);
        }
        return copy(window, reserve, pinned, elastic,
                ragCap, memoryCap, summaryCap, value);
    }

    /**
     * 二次硬顶校验的基准：窗口扣除预留后真正允许发给模型的输入上限（§4.2 步骤⑦）。
     */
    public int hardLimit() {
        return window - reserve;
    }
}
