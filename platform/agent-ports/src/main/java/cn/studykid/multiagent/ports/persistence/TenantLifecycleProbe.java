package cn.studykid.multiagent.ports.persistence;

import java.util.Optional;

import org.jspecify.annotations.NonNull;

/**
 * 租户生命周期运行时探针（重规划 §14 / 验收矩阵 #4 · 运行时安全）。
 *
 * <p>供 Worker 在<b>执行前</b>与<b>执行中</b>对租户状态做<b>再验证</b>：
 * 平台暂停/退役租户后，Worker 不能只信入队时的检查，必须在真正执行前
 * 与每一轮迭代前重新确认租户仍处可执行状态。
 *
 * <p>⚠️ <b>状态以 DB 当前值为准，不信任消息体传入的租户状态</b>。
 * 状态取值与 {@link cn.studykid.multiagent.ports.tenant.TenantLifecycle} 完全一致
 * （{@code PENDING_SETUP}/{@code ACTIVE}/{@code SUSPENDED}/{@code CANCELLED}/
 * {@code DECOMMISSIONING}/{@code DECOMMISSIONED}），由 {@code public.tenant.status}
 * 镜像 {@code platform.tenant.lifecycle_state}（见 JdbcPlatformTenantRepository）。
 *
 * <p>⚠️ 本端口只读、无副作用；不抛出「租户不存在」之外的业务异常，
 * 缺失即视为「不可执行」（fail-closed），由调用方决定如何处置。
 */
public interface TenantLifecycleProbe {

    /**
     * 读取租户当前生命周期状态。
     *
     * @param tenantId 租户标识（来自可信任务记录，非请求参数）
     * @return 当前状态；租户不存在返回 {@link Optional#empty()}
     */
    @NonNull
    Optional<String> lifecycleState(@NonNull String tenantId);
}
