package cn.studykid.multiagent.application.replay;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.ports.persistence.TraceReplayPort;
import cn.studykid.multiagent.ports.persistence.TraceReplayPort.MessageView;
import cn.studykid.multiagent.ports.persistence.TraceReplayPort.RunMeta;
import cn.studykid.multiagent.ports.persistence.TraceReplayPort.StepView;
import cn.studykid.multiagent.ports.persistence.TraceReplayPort.TokenTotal;

/**
 * 对话回放服务（P1 · G1 闭环）。
 *
 * <p><b>为什么需要它</b>：P1 之前，系统<b>没有任何一处持久化对话内容</b> ——
 * 指标能告诉你「这次辅导调了 3 次工具、1 次失败」，
 * 却<b>回答不了「模型究竟说了什么、哪一句开始跑偏」</b>。
 * 本服务把散落在 {@code run_message} / {@code run} / {@code run_step} /
 * {@code execution_snapshot} / {@code usage_record} 的事实
 * <b>组装成一条可读的时间线</b>。
 *
 * <p><b>⚠️ 跨租户 Run 一律视为「不存在」</b>：本服务的所有读取都带租户谓词，
 * 调用方必须传入<b>经过归属校验</b>的租户标识；
 * 跨租户的 runId 解析不出任何东西（表现为 404，不泄露归属）。
 *
 * <p><b>⚠️ 本服务不做鉴权</b>：它是领域服务，授权由端点层负责
 * （租户侧用令牌派生的租户，平台侧用平台权限闸门）。
 */
public class TraceReplayService {

    private final TraceReplayPort replay;

    public TraceReplayService(@NonNull TraceReplayPort replay) {
        this.replay = replay;
    }

    /**
     * 组装一次 Run 的完整回放。
     *
     * @param tenantId 租户标识（<b>必须来自认证上下文</b>）
     * @param runId    Run 标识
     * @return 回放记录；Run 不存在或不属于该租户时返回 {@code null}
     */
    public @Nullable Transcript buildTranscript(@NonNull String tenantId, @NonNull String runId) {
        RunMeta meta = replay.runMeta(tenantId, runId).orElse(null);
        if (meta == null) {
            // ⚠️ 「不存在」与「不属于该租户」**不区分**（与全站 404 口径一致）
            return null;
        }
        List<MessageView> messages = replay.messages(tenantId, runId);
        List<StepView> steps = replay.steps(tenantId, runId);
        TokenTotal tokens = replay.tokenTotal(tenantId, runId).orElse(null);
        Map<String, Object> snapshot = meta.snapshotId() == null
                ? null
                : replay.snapshot(tenantId, meta.snapshotId()).orElse(null);
        return new Transcript(meta, snapshot, messages, steps, tokens);
    }

    /**
     * 读取一次工具调用的原始详情（TOOL 消息下钻 · 详细设计 §8.3）。
     *
     * <p>⚠️ <b>为什么需要它</b>：{@code run_message} 的 TOOL 行只存结果摘要
     * （输出封顶 2000 字 + 原因文本），审查者要判断「这次工具调用到底传了什么参数、
     * 原始返回是什么、是不是被改过」必须回查 {@code tool_operation} 表。
     * 此前整条链断在第一环 —— {@code run_message.tool_operation_id} 恒为 NULL，
     * 而本方法没有对应的 HTTP 端点，审查者在 UI 上无处可查。
     *
     * <p>⚠️ <b>只按 (tenantId, operationId) 查</b>：{@code tool_operation} 是
     * FORCE RLS 的租户域表，跨租户查恒 0 行 ⇒ 表现为「不存在」，不泄露归属。
     *
     * <p>⚠️ <b>本方法不做鉴权</b>：授权由端点层负责（平台权限闸门）。
     *
     * @param tenantId    租户标识（<b>必须来自认证上下文</b>）
     * @param operationId 工具操作标识（{@code tool_operation.operation_id}）
     * @return 工具调用详情；不存在或不属于该租户时返回 {@code null}
     */
    public @Nullable Map<String, Object> toolOperation(@NonNull String tenantId,
                                                       @NonNull String operationId) {
        return replay.toolOperation(tenantId, operationId).orElse(null);
    }

    /**
     * 回放记录。
     *
     * @param run      Run 元信息
     * @param snapshot 配置快照；无快照时为 {@code null}
     * @param messages 对话消息时间线（按插入序）
     * @param steps    执行步骤
     * @param tokens   token 与成本汇总；无用量记录时为 {@code null}
     */
    public record Transcript(
            @NonNull RunMeta run,
            @Nullable Map<String, Object> snapshot,
            @NonNull List<MessageView> messages,
            @NonNull List<StepView> steps,
            @Nullable TokenTotal tokens
    ) {
        public Transcript {
            messages = messages == null ? List.of() : List.copyOf(messages);
            steps = steps == null ? List.of() : List.copyOf(steps);
        }

        /**
         * 对话消息（按受众过滤）。
         *
         * @param audience 受众
         * @return 可见消息
         */
        @NonNull
        public List<MessageView> messagesFor(@NonNull Audience audience) {
            if (audience == Audience.ADMIN) {
                return messages;
            }
            // ⚠️ 学生端**不展示工具内部消息**：工具入参/结果摘要虽已封顶，
            //    但那是运维视角的东西，对学生无价值且徒增困惑。
            return messages.stream()
                    .filter(m -> !"TOOL".equals(m.role()))
                    .toList();
        }
    }

    /**
     * 回放受众。
     *
     * <p>⚠️ <b>两种受众的差别只在「是否展示 TOOL 消息」</b>：
     * 其余消息（用户输入 / 模型输出 / 记忆召回摘要）在落库时<b>已经是摘要态</b>，
     * 两边看到的内容一致，无需二次脱敏。
     */
    public enum Audience {
        /** 管理员 / 审查者：完整时间线。 */
        ADMIN,
        /** 学生端：隐藏工具内部消息。 */
        STUDENT
    }
}
