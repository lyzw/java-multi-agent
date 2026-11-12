package cn.studykid.multiagent.ports.agentchat;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * L2 SSE 请求侧压缩态校验所需的<b>策略指纹裁决端口</b>（ADR-013 D17 / D49 / S3-M8-a）。
 *
 * <p>⚠️ <b>为什么单独成端口</b>：六项不可信校验要求拿「当前已裁决的策略指纹」比对客户端
 * 回传的不可信状态；但 {@code policyVersion} 只能在持有「策略全字段 + 模型能力与窗口 +
 * Agent 固定版本 + 全局硬顶」的边界由唯一裁决内核派生（D17）。控制器与编排器都不该、
 * 也不能自己拼一个全局常量。本端口把「查得到当前指纹」这件事收成一个中性契约，
 * 由组合根用与统一装配链路<b>同一个裁决支持</b>实现。
 *
 * <p>⚠️ <b>{@code null} 是 fail-closed 信号</b>：全局熔断关闭、策略/模型配置不可用、
 * 裁决异常等情况一律返回 {@code null}；调用方必须把客户端状态当无效状态静默丢弃，
 * ⛔ 绝不用信封自身声称的版本反推「当前策略」，也绝不因此拒绝 HTTP 请求 / 发错误帧。
 *
 * <p>⚠️ 纯端口：不引用算法层类型、零 Spring / 零 JDBC / 零 IO；全参构造唯一，
 * 不提供兼容构造器（铁律 #10）。
 */
public interface ContextPolicyVersionPort {

    /**
     * 按固定版本事实裁决当前策略指纹。
     *
     * @param input 冻结身份与模型事实
     * @return 当前有效指纹；{@code null} = 无法裁决 / 不应接受任何客户端压缩态
     */
    @Nullable String resolve(@NonNull Input input);

    /**
     * 裁决输入。四个输入缺一不可，任一变化都会改变指纹（D17）。
     *
     * @param tenantId       租户 id（冻结策略按租户 + 固定版本读取）
     * @param agentId        Agent 标识
     * @param agentVersion   Agent 固定 release（⛔ 不读「当前已发布版本」）
     * @param modelId        实际送模型的模型标识（能力精确匹配，不回落别的模型）
     * @param maxOutputTokens 本轮输出上限（参与总窗口溢出判定）
     */
    record Input(
            @NonNull String tenantId,
            @NonNull String agentId,
            int agentVersion,
            @NonNull String modelId,
            int maxOutputTokens) {

        public Input {
            require(tenantId, "tenantId");
            require(agentId, "agentId");
            require(modelId, "modelId");
            if (agentVersion <= 0) {
                throw new IllegalArgumentException(
                        "ContextPolicyVersionPort.Input.agentVersion 必须为正（固定 release）");
            }
            if (maxOutputTokens <= 0) {
                throw new IllegalArgumentException(
                        "ContextPolicyVersionPort.Input.maxOutputTokens 必须为正（总窗口判定缺输出上界）");
            }
        }

        private static void require(@Nullable String value, @NonNull String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        "ContextPolicyVersionPort.Input." + field + " 不得为空（fail-closed）");
            }
        }
    }
}
