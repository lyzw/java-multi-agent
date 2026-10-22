package cn.studykid.multiagent.core.cap;

import java.time.Instant;

import org.jspecify.annotations.NonNull;

/**
 * Run 级能力闭包快照（T2 绑定快照，§6.1 / 06 §1.6）。
 *
 * <p><b>四概念分离，不可合并</b>（06 §4）：
 * <ul>
 *   <li>{@code agentVersion} —— 对象版本（{@code cap_agent_def.version}）</li>
 *   <li>{@code configVersion} —— 配置发布版本（<b>序号</b>，追溯发布事件）</li>
 *   <li>{@code configFingerprint} —— 能力闭包指纹（<b>内容</b>，检测一致性）</li>
 *   <li>{@code authzVersion} —— 授权版本（主体授权状态）</li>
 * </ul>
 *
 * <p>⚠️ {@code configVersion} 与 {@code configFingerprint} <b>语义不同、不可相互替代</b>：
 * 同内容因回滚重新生效时指纹相同但发布事件/审计不同；版本递增也不一定代表权限变化。
 * 快照须同时保存两者，分别承担「版本追溯」与「内容一致性校验」。
 *
 * <p>⚠️ {@code configFingerprint} 是<b>本 Run 依赖闭包级</b>摘要，
 * <b>不是全局聚合</b>——无关对象变更<b>不</b>触发本 Run 失效（§6.4 / S5a）。
 *
 * @param runId             Run 标识
 * @param tenantId          租户标识
 * @param agentId           Agent 标识
 * @param effectiveClosure  有效能力闭包（JSON 文本）
 * @param agentVersion      Agent 定义版本
 * @param configVersion     配置发布版本（单调序号）
 * @param configFingerprint 能力闭包指纹
 * @param authzVersion      生成时快照的授权版本
 * @param createdAt         快照生成时间
 */
public record CapabilitySnapshot(
        @NonNull String runId,
        @NonNull String tenantId,
        @NonNull String agentId,
        @NonNull String effectiveClosure,
        int agentVersion,
        long configVersion,
        @NonNull String configFingerprint,
        long authzVersion,
        @NonNull Instant createdAt
) {

    public CapabilitySnapshot {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 不能为空");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId 不能为空");
        }
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId 不能为空");
        }
        if (effectiveClosure == null || effectiveClosure.isBlank()) {
            throw new IllegalArgumentException("effectiveClosure 不能为空");
        }
        if (agentVersion <= 0) {
            throw new IllegalArgumentException("agentVersion 必须为正数，收到: " + agentVersion);
        }
        if (configFingerprint == null || configFingerprint.isBlank()) {
            throw new IllegalArgumentException("configFingerprint 不能为空");
        }
        if (authzVersion < AuthzVersion.INITIAL_VERSION) {
            throw new IllegalArgumentException(
                    "authzVersion 不得小于 " + AuthzVersion.INITIAL_VERSION);
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt 不能为空");
        }
    }
}
