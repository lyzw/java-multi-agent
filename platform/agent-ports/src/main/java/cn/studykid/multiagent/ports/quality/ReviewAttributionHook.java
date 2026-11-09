package cn.studykid.multiagent.ports.quality;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 审查归因结论的<b>回写钩子</b>（§8.1 · 2026-11-09 预留）。
 *
 * <p><b>⚠️ 为什么是「钩子」而不是直接回写</b>：
 * 详细设计 §8.1 承诺「{@code resolve} 时若 {@code root_cause} 指向
 * {@code PROMPT}/{@code CONFIG}/{@code MODEL}，回写 {@code agent_eval_sample}
 * （归因结论）并触发迭代工单」。但技术侧已就绪 ≠ 可以自行拍板 ——
 * <b>「回写什么结论值」需要产品定口径</b>：
 * {@code root_cause=PROMPT} 到底对应 {@code attribution_correct=true} 还是别的判定？
 * 迭代工单是外部系统还是本仓对象？这两个问题没有答案之前，
 * 任何直接回写都是在<b>编造结论</b>——与本项目「绝不编造归因正确率」的铁律冲突。
 *
 * <p><b>因此本端口的职责是把「接线」与「口径」解耦</b>：
 * <ul>
 *   <li>接线（本仓已完成）：resolve 成功后，把归因结论的完整上下文交给钩子；
 *       钩子失败 fail-soft，<b>绝不影响已关闭的任务</b>；</li>
 *   <li>口径（产品定）：产品确认结论值映射后，实现本接口注册为 Bean，
 *       默认 NoOp 自动让位，无需改编排。</li>
 * </ul>
 *
 * <p>⚠️ <b>只有 PROMPT/CONFIG/MODEL 会被调用</b>（§8.1 原文的三类）：
 * TOOL/DATA/UNKNOWN 不触发迭代工单语义，不应被喂进钩子制造假信号。
 */
public interface ReviewAttributionHook {

    /**
     * 任务归因关闭后回调。
     *
     * @param traceId    被归因的 Trace
     * @param tenantId   所属租户
     * @param runId      所属 Run
     * @param rootCause  归因根因（大写枚举名：MODEL / PROMPT / CONFIG）
     * @param note       reviewer 备注（可空）
     * @param reviewer   执行归因的平台主体
     */
    void onAttributionResolved(@NonNull String traceId,
                               @NonNull String tenantId,
                               @NonNull String runId,
                               @NonNull String rootCause,
                               @Nullable String note,
                               @NonNull String reviewer);
}
