package cn.studykid.multiagent.ports.persistence;

import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 对话消息写入端口（P1 · G1 回放底座）。
 *
 * <p><b>为什么需要它</b>：P1 之前，系统<b>没有任何一处持久化对话内容</b> ——
 * {@code run} / {@code run_step} / {@code run_event} / {@code tool_operation}
 * 存的都是结构化事实（状态、时长、工具名、状态码），
 * <b>模型究竟说了什么、为什么这样说，一个字节都没有</b>。
 * 于是「这次辅导质量如何」只能被压缩成一个数字
 * （例如 {@code tool_call_invalid=1}），<b>无法回答「它错在哪一句」</b>。
 *
 * <p><b>与既有端口的分工</b>：
 * <ul>
 *   <li>{@link RunEventPort}：观测事件流（轻量、白名单标量、<b>可丢失</b>）；</li>
 *   <li>{@link RunStepPort}：结构化执行事实（带时长与轮次号、<b>不可丢失</b>）；</li>
 *   <li><b>本端口</b>：对话内容本体（<b>不可丢失</b>，是回放与内容级评估的唯一底座）。</li>
 * </ul>
 *
 * <p><b>⚠️ 内容合规红线</b>（与 P7 一致）：{@code content} 落库前必须是<b>摘要态</b>：
 * <ul>
 *   <li>知识库召回<b>只存 id 与 label</b>，绝不存知识库原文；</li>
 *   <li>工具入参/结果走长度封顶 + 不可信标记，命中敏感模式时以 digest 替代；</li>
 *   <li>学生输入与模型输出可存原文（属产品交付物），但回放给学生端时按受众过滤。</li>
 * </ul>
 *
 * <p><b>⚠️ RLS</b>：{@code run_message} 带 FORCE RLS，实现必须在<b>事务内绑定租户上下文</b>后写入
 * （与 {@link RunStepPort} 同机制），用系统连接直写会静默失败。
 *
 * <p><b>⚠️ 写入失败绝不冒泡到主流程</b>：一次本可成功的辅导不能因「消息没记下」而变成失败。
 * 该纪律由调用方（执行循环）与实现方（仓储吞异常）<b>双重</b>保证。
 */
public interface RunMessagePort {

    /**
     * 追加一条对话消息。
     *
     * <p><b>⚠️ 幂等</b>：实现应保证<b>同一 Run 的同一 (stepNumber, role) 重复写入不产生重复行</b>
     * （执行循环重试/重放会重新走一遍埋点），否则回放会出现重复对话。
     *
     * @param tenantId        租户（RLS 绑定 + 隔离谓词）
     * @param runId           Run 标识
     * @param fencingToken    执行循环世代号（红线 5：旧世代不得写入）
     * @param stepNumber      所属轮次（与 {@code run_step.step_number} 对齐；首轮用户输入用 0）
     * @param role            消息角色：{@link Roles} 之一
     * @param content         内容（jsonb 语义）；结构与合规约定见本接口类注释
     * @param toolOperationId 关联的工具操作（{@code role=TOOL} 时有值）
     * @param modelId         模型标识（{@code role=ASSISTANT} 时有值）
     * @param inputTokens     输入 token（{@code role=ASSISTANT} 时可采集）
     * @param outputTokens    输出 token（{@code role=ASSISTANT} 时可采集）
     * @param latencyMs       该条消息产生的耗时（毫秒）
     */
    void append(@NonNull String tenantId,
                @NonNull String runId,
                long fencingToken,
                int stepNumber,
                @NonNull String role,
                @NonNull Map<String, Object> content,
                @Nullable String toolOperationId,
                @Nullable String modelId,
                @Nullable Long inputTokens,
                @Nullable Long outputTokens,
                @Nullable Long latencyMs);

    /** 消息角色常量（与 {@code run_message.role} 的 CHECK 约束一致）。 */
    final class Roles {
        private Roles() {
        }

        /** 系统提示 / 记忆召回摘要（只存 id+label，绝不存知识库原文）。 */
        public static final String SYSTEM = "SYSTEM";
        /** 学生输入。 */
        public static final String USER = "USER";
        /** 模型输出（含工具调用意图摘要）。 */
        public static final String ASSISTANT = "ASSISTANT";
        /** 工具调用结果摘要。 */
        public static final String TOOL = "TOOL";
    }
}
