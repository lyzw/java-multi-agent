package cn.studykid.multiagent.ports.persistence;

import java.time.Instant;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 执行步骤写入端口（P7-2）。
 *
 * <p><b>为什么需要它</b>：{@code public.run_step} 表从 P2 起就存在，但<b>全库零生产写入方</b>
 * （仅 {@code JdbcErasureRepository} 的删除语句与 {@code JdbcResultQueryRepository} 的读取提及它），
 * 导致「解题步骤」恒空、P7 的 {@code tool_rounds / think_ms_total / clarification_count}
 * 只能退化为近似或 NULL。本端口补全写入方，使：
 * <ul>
 *   <li>执行循环能把每一轮「思考 / 工具」落为一条 {@code run_step}；</li>
 *   <li>P7 采集器从 {@code run_step} 算出<b>真实口径</b>的步数类指标；</li>
 *   <li>进度页 / 结果页的「解题步骤」从空态变为真实数据。</li>
 * </ul>
 *
 * <p><b>⚠️ 与 {@link RunEventPort} 的分工</b>：{@code RunEventPort} 是<b>观测事件流</b>
 * （轻量、白名单标量、可丢失）；{@code RunStepPort} 是<b>结构化执行事实</b>
 * （带时长、轮次号、fencing token，是 P7 指标与结果页的<b>真实数据源</b>，不可丢失）。
 * 两者都遵循同一铁律：<b>写入失败绝不影响主执行流程</b>。
 *
 * <p><b>⚠️ RLS</b>：{@code run_step} 带 FORCE RLS，实现必须在<b>事务内绑定租户上下文</b>后写入
 * （与 {@code JdbcTraceFactRepository.openTenant} 同机制），用系统连接直写会静默失败。
 *
 * <p><b>⚠️ fencing token</b>：必须传入执行循环的 {@code fencingToken}——
 * 旧世代 Worker 若回写步骤，DB 层的 {@code enforce_fencing_on_write} 触发器会拒绝
 * （红线 5：旧世代不得写入）。{@code step_type} 取值见 {@link Types}。
 */
public interface RunStepPort {

    /**
     * 追加一条执行步骤。
     *
     * <p>实现应保证<b>幂等</b>（按 {@code (tenant_id, run_id, step_number)} 冲突时覆盖），
     * 以便执行循环重试/重放不重复造步骤。
     *
     * @param tenantId     租户（RLS 绑定 + 外键）
     * @param runId        Run 标识
     * @param fencingToken 执行循环世代号（红线 5）
     * @param stepNumber   单调轮次号（同 Run 内唯一，从 1 开始）
     * @param stepType     {@link Types} 之一
     * @param startedAt    步骤开始时刻
     * @param completedAt  步骤结束时刻（与 startedAt 同时传入，单条 INSERT 落库）
     */
    void append(@NonNull String tenantId,
                @NonNull String runId,
                long fencingToken,
                int stepNumber,
                @NonNull String stepType,
                @NonNull Instant startedAt,
                @NonNull Instant completedAt);

    /** 步骤类型常量（与 {@code run_step.step_type} 写入值一致）。 */
    final class Types {
        private Types() {
        }

        /** 模型推理（规划 / 思考）。 */
        public static final String THINK = "THINK";
        /** 工具调用（一个模型→工具周期）。 */
        public static final String TOOL = "TOOL";
        /** 向用户追问（索要上下文）。当前执行循环无此路径 → 指标恒 NULL（未采集）。 */
        public static final String ASK_USER = "ASK_USER";
        /** 终态步骤（成功 / 失败收尾）。 */
        public static final String TERMINAL = "TERMINAL";
    }
}
